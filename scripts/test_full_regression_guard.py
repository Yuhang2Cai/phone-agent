"""设备回归事实保护：再次运行不能继续购买已消费夹具或共享原目录。"""
import importlib.util
import unittest
from pathlib import Path
import httpx


def load_runner():
    """加载正式控制端模块，不运行main或读取私有夹具，便于验证候选查询约束。"""
    spec = importlib.util.spec_from_file_location("full_runner", Path(__file__).with_name("Run-FullRegression.py"))
    runner = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(runner)
    return runner


class BrowseSelectionTest(unittest.TestCase):
    """历史证据不属于运行依赖；测试只模拟控制端目录输入，不伪造App业务数据。"""
    def test_selects_live_store_with_all_browse_prerequisites(self):
        """跳过无菜单的真实候选，仅选择有两种套餐、菜品和评价的当前门店。"""
        payloads = {
            "/api/stores": [{"publicId": "empty"}, {"publicId": "eligible"}],
            "/api/stores/empty/deals": [{"publicId": "a"}, {"publicId": "b"}],
            "/api/stores/empty/dishes": [],
            "/api/stores/eligible/deals": [{"publicId": "a"}, {"publicId": "b"}],
            "/api/stores/eligible/dishes": [{"name": "测试菜品"}],
            "/api/stores/eligible/reviews": [{"content": "测试评价"}],
            "/api/stores/eligible": {"publicId": "eligible", "cityCode": "test"},
        }
        calls = []
        def response(request):
            """按真实路径返回候选；没有定义的路径使测试失败，防止偷偷读旧固定资源。"""
            calls.append(request.url.path)
            return httpx.Response(200, json=payloads[request.url.path])
        with httpx.Client(base_url="http://catalog.test", transport=httpx.MockTransport(response)) as client:
            store, deals = load_runner().select_browse_store(client)
        self.assertEqual(store["publicId"], "eligible")
        self.assertEqual(len(deals), 2)
        self.assertIn("/api/stores/eligible/reviews", calls)

    def test_missing_qualified_catalog_fails_without_static_fallback(self):
        """不存在合格候选时明确失败，不能生成样例门店或回读私有历史JSON。"""
        with httpx.Client(base_url="http://catalog.test", transport=httpx.MockTransport(
                lambda request: httpx.Response(200, json=[]))) as client:
            with self.assertRaises(RuntimeError):
                load_runner().select_browse_store(client)


class RegressionGuardTest(unittest.TestCase):
    """只测试受信控制端的资源核对，不模拟Android业务数据。"""
    def test_existing_order_and_shared_store_are_rejected(self):
        """已有订单、非新建门店或账号都必须拒绝，防止重跑再扣共享库存。"""
        spec = importlib.util.spec_from_file_location("full_runner", Path(__file__).with_name("Run-FullRegression.py"))
        runner = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runner)
        purchase = {"username": "t12_synthetic", "storeId": "new-store", "dealId": "new-deal", "quantity": 2}
        store = {"publicId": "new-store", "name": "新建集成验收门店"}
        deal = {"publicId": "new-deal", "storePublicId": "new-store", "inventory": 4, "active": True, "purchaseLimit": 4}
        # 既有单据拒绝，不允许运行器清单被误用为重复授权。
        with self.assertRaises(ValueError):
            runner.validate_purchase_fixture(purchase, store, deal, [{"orderId": "existing"}])
        with self.assertRaises(ValueError):
            runner.validate_purchase_fixture(purchase, {**store, "name": "公共门店"}, deal, [])
        runner.validate_purchase_fixture(purchase, store, deal, [])


if __name__ == "__main__":
    unittest.main()
