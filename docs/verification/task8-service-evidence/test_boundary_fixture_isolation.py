"""售后边界测试准备器的离线守卫：禁止为获取测试券扣减已有套餐库存。

使用内存数据库模拟目录，覆盖正常退出和异常退出，不能触碰真实接口或 MySQL。
"""
import importlib.util
from pathlib import Path

import pytest
from sqlalchemy import create_engine, text


@pytest.fixture
def subject():
    """加载待修复的边界辅助；仅取得函数，不执行在线 pytest 夹具。"""
    path = Path(__file__).with_name('test_live_write_boundaries.py')
    spec = importlib.util.spec_from_file_location('boundary_subject', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def engine():
    """构造与隔离逻辑有关的最小字段及一个原套餐，退出后销毁内存数据库。"""
    value = create_engine('sqlite://')
    with value.begin() as connection:
        connection.execute(text('CREATE TABLE deal_packages (id INTEGER PRIMARY KEY AUTOINCREMENT, '
                                'public_id TEXT UNIQUE NOT NULL, title TEXT NOT NULL, inventory INTEGER NOT NULL, '
                                'active BOOLEAN NOT NULL, price_cents INTEGER NOT NULL)'))
        connection.execute(text("INSERT INTO deal_packages(public_id,title,inventory,active,price_cents) "
                                "VALUES ('existing','原目录套餐',71,1,5600)"))
    yield value
    value.dispose()


@pytest.mark.parametrize('fail', [False, True], ids=['正常结束', '请求异常'])
def test_clone_only_inventory_and_cleanup(subject, engine, fail):
    """只允许克隆记录发生库存变化；无论成功还是异常都下架克隆，保留测试事实。"""
    isolated_deal = subject.isolated_deal
    original = None
    with engine.connect() as connection:
        original = dict(connection.execute(text('SELECT * FROM deal_packages WHERE id=1')).mappings().one())
    try:
        with isolated_deal(engine) as public_id:
            assert public_id != 'existing'
            with engine.begin() as connection:
                row = connection.execute(text('SELECT * FROM deal_packages WHERE public_id=:id'), {'id': public_id}).mappings().one()
                assert row['active'] == 1 and row['inventory'] == 2
                assert row['title'].startswith('Task8隔离边界-')
                connection.execute(text('UPDATE deal_packages SET inventory=inventory-1 WHERE public_id=:id'), {'id': public_id})
            if fail:
                raise RuntimeError('模拟公开接口异常')
    except RuntimeError:
        assert fail
    with engine.connect() as connection:
        after = dict(connection.execute(text('SELECT * FROM deal_packages WHERE id=1')).mappings().one())
        clone = connection.execute(text('SELECT * FROM deal_packages WHERE id!=1')).mappings().one()
        assert after == original
        assert clone['inventory'] == 1 and clone['active'] == 0


def test_empty_catalog_cannot_fall_back_to_public_purchase(subject, engine):
    """没有可复制的来源即拒绝准备，不能改为直接购买任意现有套餐。"""
    with engine.begin() as connection:
        connection.execute(text('DELETE FROM deal_packages'))
    with pytest.raises(AssertionError, match='来源'):
        with subject.isolated_deal(engine):
            pytest.fail('无来源时不得产生可写套餐')
