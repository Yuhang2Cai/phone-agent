"""性能夹具单测：约束确定规模、公开身份、终态和非法输入，防止回放不可重建。"""
from pathlib import Path
import importlib.util
import unittest

SOURCE = Path(__file__).parent / 'Prepare-AdvancedPerformanceFixtures.py'
spec = importlib.util.spec_from_file_location('fixtures', SOURCE)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class PerformanceFixtureTest(unittest.TestCase):
    """只使用明确标记的后端合成种子；不写数据库，也不向Android产品注入假行。"""
    def test_fixed_scale_and_identity(self):
        """相同种子两次生成必须相同，三个规模和聊天消息数量精确符合合同。"""
        seed=[{'publicId':'73d0290b-d111-4b96-8992-3018d5f8d212','name':'夹具种子','cityCode':'fixture','category':'火锅','featuredDeal':None}]
        first=module.build_fixture(seed)
        self.assertEqual(set(first['catalog']), {'30','100','500'})
        for size in (30,100,500):
            rows=first['catalog'][str(size)]
            self.assertEqual(len(rows),size)
            self.assertEqual(len({x['publicId'] for x in rows}),size)
        self.assertEqual(first,module.build_fixture(seed))
        for size in (50,200):
            self.assertEqual(len(first['messages'][str(size)]),size)
    def test_sse_order_terminal_and_no_execution(self):
        """1000条事件含唯一递增游标与最后终态，不包含支付、退款或确认凭证。"""
        data=module.build_fixture([{'publicId':'73d0290b-d111-4b96-8992-3018d5f8d212','name':'夹具种子','featuredDeal':None}])
        events=data['events']
        self.assertEqual([x['id'] for x in events],list(range(1,1001)))
        self.assertEqual(events[-1]['type'],'completed')
        self.assertFalse(any('confirmationToken' in str(x) for x in events))
    def test_empty_source_rejected(self):
        """无后端来源时拒绝生成，不能填入Android或服务器内置业务兜底。"""
        with self.assertRaises(ValueError):
            module.build_fixture([])

if __name__=='__main__':
    unittest.main()
