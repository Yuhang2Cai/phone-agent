// 售后卡片展示测试：以真实渲染入口验证只读状态、显式确认和取消，禁止客户端计算资格。
package com.fandazi.app.feature.agent

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.fandazi.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 本测试检查卡片实际文字和点击输出，不用模型构造断言冒充View展示验证。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AgentAfterSaleCardTest {
    @Test
    /** 五种权威状态必须中文展示；日期再异常也不自行改写AVAILABLE，且没有领取/抵扣按钮。 */
    fun couponUsesBackendStatusAndHasNoBusinessAction() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = Fragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "test").commitNow()
        val states = mapOf("AVAILABLE" to "可用", "USED" to "已使用", "EXPIRED" to "已过期",
            "NOT_YET_VALID" to "未生效", "DISABLED" to "已停用")
        states.forEach { (status, label) ->
            val container = LinearLayout(activity)
            AgentCardRenderer.render(fragment, container, listOf(AgentCardPayload("coupon", fields = mapOf(
                "name" to "本人优惠券", "status" to status, "thresholdCents" to "10001", "discountCents" to "3333",
                "validFrom" to "2000-01-01", "validTo" to "2000-01-02", "scopeDescription" to "指定门店"
            )))) { error("只读券不得产生操作") }
            val view = container.getChildAt(0)
            val text = view.findViewById<TextView>(R.id.agent_order_meta).text.toString()
            assertTrue(text.contains("状态：$label"))
            assertTrue(text.contains("满¥100.01减¥33.33"))
            assertTrue(text.contains("指定门店"))
            assertEquals(View.GONE, view.findViewById<View>(R.id.agent_order_action).visibility)
        }
    }

    @Test
    /** 缺退款金额、原因或有效期时不能确认；完整摘要只在点击后发明确确认且能取消。 */
    fun refundRequiresCompleteSummaryAndOnlyClickConfirms() {
        val activity = Robolectric.buildActivity(FragmentActivity::class.java).setup().get()
        val fragment = Fragment()
        activity.supportFragmentManager.beginTransaction().add(fragment, "test").commitNow()
        val container = LinearLayout(activity)
        val replies = mutableListOf<AgentNavigationRequest>()
        val fields = mapOf("voucherId" to "public-voucher", "orderId" to "public-order", "dealTitle" to "真实套餐",
            "amountCents" to "3333", "reason" to "行程变更", "expiresAt" to "2026-10-02T01:00:00Z")
        val card = AgentCardPayload("after_sale_refund", fields = fields)
        // 本测试隔离渲染，当前性由回调显式注入；原事件身份与期限在状态机回归中真实校验。
        AgentCardRenderer.render(fragment, container, listOf(card), { it == card }, replies::add)
        assertTrue(replies.isEmpty())
        val view = container.getChildAt(0)
        assertTrue(view.findViewById<TextView>(R.id.agent_order_meta).text.contains("2026-10-02 09:00"))
        view.findViewById<View>(R.id.agent_order_action).performClick()
        assertEquals(AgentNavigationRequest.AgentReply("确认退款", card), replies.single())
        // 取消按钮是新受控动作，因此按可读文案查找，避免测试依赖布局内部生成编号。
        val cancel = (0 until (view as LinearLayout).childCount).map { view.getChildAt(it) }
            .filterIsInstance<TextView>().single { it.text == "取消本次申请" }
        cancel.performClick()
        assertEquals(AgentNavigationRequest.AgentReply("取消退款", card), replies.last())
        container.removeAllViews()
        AgentCardRenderer.render(fragment, container, listOf(AgentCardPayload("after_sale_refund", fields = fields - "amountCents")), replies::add)
        assertFalse(container.getChildAt(0).findViewById<View>(R.id.agent_order_action).isEnabled)
        // 新契约以resourceId标识券，不承诺orderId/voucherId别名；不得因缺可选摘要禁用真实确认。
        container.removeAllViews()
        AgentCardRenderer.render(fragment, container, listOf(AgentCardPayload("after_sale_refund",
            fields = (fields - "orderId" - "voucherId") + ("resourceId" to "public-voucher"))), { true }, replies::add)
        assertTrue(container.getChildAt(0).findViewById<View>(R.id.agent_order_action).isEnabled)
        // 历史卡仍展示摘要，但无当前性校验时确认和取消都关闭，点击也不发送命令。
        container.removeAllViews()
        AgentCardRenderer.render(fragment, container, listOf(card), replies::add)
        val old = container.getChildAt(0)
        assertFalse(old.findViewById<View>(R.id.agent_order_action).isEnabled)
        assertFalse(old.findViewById<View>(R.id.agent_after_sale_cancel).isEnabled)
        old.findViewById<View>(R.id.agent_order_action).performClick()
        assertEquals(2, replies.size)
    }
}
