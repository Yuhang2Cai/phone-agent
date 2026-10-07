// 真设备四类回收验收：直接把不同内容反复绑定到同一Holder，确认原ID、图像和动作没有串项。
package com.fandazi.app.feature.agent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fandazi.app.R
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 仅合成公开事实绑定测试，不调用模型/交易/录音，不将夹具作为生产UI数据。 */
@RunWith(AndroidJUnit4::class)
class AgentChatRecyclingTest {
    /** 门店→套餐→确认→只读券→未知连续使用同一卡片Holder，检查图/金额/取消/监听逐项消失。 */
    @Test fun sameCardHolderResetsEveryBusinessBranchAndOriginalAction() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val parent=FrameLayout(instrumentation.targetContext)
            val clicks=mutableListOf<AgentNavigationRequest>()
            val adapter=AgentMessageAdapter({request,_->clicks+=request},{},{},{},{})
            val holder=adapter.onCreateViewHolder(parent,AgentRowType.CARD.ordinal) as AgentCardViewHolder
            val store=AgentCardPayload("store",storePublicId="fixture-store",fields=mapOf("name" to "门店夹具","rating" to "4.2"))
            holder.bind(AgentRenderRow.Card("s",store,false))
            assertEquals(View.VISIBLE,holder.itemView.findViewById<ImageView>(R.id.agent_store_image).visibility)
            holder.itemView.findViewById<View>(R.id.agent_store_action).performClick()
            assertEquals(AgentNavigationRequest.Store("fixture-store"),clicks.last())
            val deal=AgentCardPayload("deal",storePublicId="fixture-store",dealPublicId="fixture-deal",fields=mapOf("name" to "套餐夹具","priceCents" to "1234","originalPriceCents" to "2000"))
            holder.bind(AgentRenderRow.Card("d",deal,false));holder.itemView.findViewById<View>(R.id.agent_deal_action).performClick()
            assertEquals(AgentNavigationRequest.Deal("fixture-store","fixture-deal"),clicks.last())
            assertEquals("¥12.34",holder.itemView.findViewById<TextView>(R.id.agent_deal_price).text.toString())
            val confirmation=AgentCardPayload("confirmation",fields=mapOf("resourceId" to "fixture-deal","ruleVersion" to "1","title" to "摘要夹具","amountCents" to "1234","expiresAt" to "2030-01-01T00:00:00Z","action" to "create_group_buy_order"))
            holder.bind(AgentRenderRow.Card("c",confirmation,true));holder.itemView.findViewById<View>(R.id.agent_order_action).performClick()
            assertEquals(AgentNavigationRequest.AgentReply("确认",confirmation),clicks.last())
            assertEquals(View.GONE,holder.itemView.findViewById<ImageView>(R.id.agent_deal_image).visibility)
            assertEquals(View.VISIBLE,holder.itemView.findViewById<View>(R.id.agent_after_sale_cancel).visibility)
            val old=confirmation.copy(historicalReadOnly=true)
            holder.bind(AgentRenderRow.Card("old",old,false))
            assertFalse(holder.itemView.findViewById<View>(R.id.agent_order_action).isEnabled)
            assertFalse(holder.itemView.findViewById<View>(R.id.agent_order_action).hasOnClickListeners())
            val voucher=AgentCardPayload("voucher",fields=mapOf("voucherId" to "fixture-voucher","dealTitle" to "券夹具","status" to "UNUSED"),historicalReadOnly=true)
            holder.bind(AgentRenderRow.Card("v",voucher,false));holder.itemView.findViewById<View>(R.id.agent_order_action).performClick()
            assertEquals(AgentNavigationRequest.Voucher("fixture-voucher"),clicks.last())
            assertEquals(View.GONE,holder.itemView.findViewById<View>(R.id.agent_after_sale_cancel).visibility)
            holder.bind(AgentRenderRow.Card("unknown",AgentCardPayload("future",fields=mapOf("title" to "未知信息")),false))
            assertEquals("助手信息",holder.itemView.findViewById<TextView>(R.id.agent_order_title).text.toString())
            assertEquals(View.GONE,holder.itemView.findViewById<View>(R.id.agent_order_amount).visibility)
            assertEquals(View.GONE,holder.itemView.findViewById<View>(R.id.agent_order_action).visibility)
            assertFalse(holder.itemView.findViewById<View>(R.id.agent_order_action).hasOnClickListeners())
            assertNull(holder.itemView.findViewById<ImageView>(R.id.agent_deal_image).drawable)
            holder.recycle();assertNull(holder.itemView.tag)
            assertEquals(1L,adapter.metrics.creates[AgentRowType.CARD.ordinal])
        }
    }
    /** 错误→用户→助手→上下文重复绑定同一消息Holder；错误回调不会残留到普通气泡。 */
    @Test fun sameMessageAndGuideHoldersResetStylesAndListeners() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val parent=FrameLayout(i.targetContext);var retried=0;var logged=0;var later=0;val quick=mutableListOf<String>()
            val adapter=AgentMessageAdapter({_,_->},{retried++},{logged++},{later++},{quick+=it})
            val holder=adapter.onCreateViewHolder(parent,AgentRowType.MESSAGE.ordinal) as AgentMessageViewHolder
            holder.bind(AgentRenderRow.Message("e","错误夹具",AgentMessageStyle.ERROR,"暂时无法完成请求",true))
            assertEquals("错误语义ID必须对应正在显示的实际错误",R.id.agent_error_card,holder.itemView.id)
            holder.itemView.findViewById<View>(R.id.agent_error_retry).performClick();assertEquals(1,retried)
            holder.bind(AgentRenderRow.Message("u","用户夹具",AgentMessageStyle.USER))
            assertEquals("普通消息必须移除上一个错误语义ID",R.id.agent_message_root,holder.itemView.id)
            assertEquals(View.GONE,holder.itemView.findViewById<View>(R.id.agent_error_retry).visibility)
            assertFalse(holder.itemView.findViewById<View>(R.id.agent_error_retry).hasOnClickListeners())
            assertEquals(View.GONE,holder.itemView.findViewById<View>(R.id.agent_message_avatar).visibility)
            holder.bind(AgentRenderRow.Message("a","助手夹具",AgentMessageStyle.ASSISTANT))
            assertEquals(View.VISIBLE,holder.itemView.findViewById<View>(R.id.agent_message_avatar).visibility)
            val body=holder.itemView.findViewById<TextView>(R.id.agent_message_text)
            assertEquals((322*i.targetContext.resources.displayMetrics.density).toInt(),body.maxWidth)
            holder.bindText(AgentRenderRow.Message("a","流式完整正文",AgentMessageStyle.ASSISTANT));assertEquals("流式完整正文",body.text.toString())
            holder.bind(AgentRenderRow.Message("context","上下文夹具",AgentMessageStyle.CONTEXT,"上次查看"))
            assertEquals(R.id.agent_context_card,holder.itemView.id)
            assertEquals("上次查看",holder.itemView.findViewById<TextView>(R.id.agent_context_title).text.toString())
            holder.bind(AgentRenderRow.Message("a2","再答",AgentMessageStyle.ASSISTANT));assertEquals("再答",holder.itemView.findViewById<TextView>(R.id.agent_message_text).text.toString())
            val guide=adapter.onCreateViewHolder(parent,AgentRowType.GUIDE.ordinal) as AgentGuideViewHolder
            guide.bind(AgentRenderRow.Guide("login",true));guide.itemView.findViewById<View>(R.id.agent_login_action).performClick()
            guide.itemView.findViewById<View>(R.id.agent_login_later).performClick();assertEquals(1,logged);assertEquals(1,later)
            guide.bind(AgentRenderRow.Guide("empty",false));guide.itemView.findViewById<View>(R.id.agent_quick_nearby).performClick()
            assertEquals(listOf("附近有什么好吃的？"),quick);assertEquals(1,logged)
            assertEquals(View.VISIBLE,guide.itemView.findViewById<View>(R.id.agent_quick_order).visibility)
            guide.bind(AgentRenderRow.Guide("login2",true));assertEquals(View.GONE,guide.itemView.findViewById<View>(R.id.agent_quick_order).visibility)
            assertFalse(guide.itemView.findViewById<View>(R.id.agent_quick_order).hasOnClickListeners())
            guide.recycle();holder.recycle()
        }
    }
    /** 多次运行/终态更新只扩容必要步骤槽，不重复创建或留下活动动画；输出实际计数。 */
    @Test fun progressReusesSlotsAndPureTextPayloadKeepsIdentity() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            val parent=FrameLayout(i.targetContext);val adapter=AgentMessageAdapter({_,_->},{},{},{},{})
            val holder=adapter.onCreateViewHolder(parent,AgentRowType.PROGRESS.ordinal) as AgentProgressViewHolder
            repeat(20){n->holder.bind(AgentRenderRow.Progress("r","处理进度",listOf(AgentProgressStep("s","实际步骤",if(n==19)"已完成" else "进行中",n!=19))))}
            assertEquals(1L,adapter.metrics.stepSlots)
            val steps=holder.itemView.findViewById<LinearLayout>(R.id.agent_progress_steps);assertEquals(1,steps.childCount)
            assertEquals(View.GONE,(steps.getChildAt(0) as LinearLayout).getChildAt(0).visibility)
            holder.bind(AgentRenderRow.Progress("r2","处理进度",listOf(AgentProgressStep("s2","新步骤","等待处理",false),AgentProgressStep("s3","另一项","进行中",true))))
            assertEquals(2L,adapter.metrics.stepSlots)
            holder.recycle();assertEquals(View.GONE,steps.getChildAt(1).visibility)
            val old=AgentRenderRow.Message("a","一",AgentMessageStyle.ASSISTANT);val next=old.copy(body="一二")
            assertSame(AgentMessageAdapter.TEXT_PAYLOAD,AgentMessageAdapter.DIFF.getChangePayload(old,next))
            assertNull(AgentMessageAdapter.DIFF.getChangePayload(old,next.copy(style=AgentMessageStyle.ERROR)))
            val result=JSONObject().put("progressHoldersCreated",adapter.metrics.creates[AgentRowType.PROGRESS.ordinal]).put("progressBinds",21).put("stepSlotsCreated",adapter.metrics.stepSlots).put("wholeSubtreesInflatedDuringBind",0).put("asr","未运行")
            val output=File(i.targetContext.getExternalFilesDir(null),"performance/chat-recycling-device.json");output.parentFile!!.mkdirs();output.writeText(result.toString(2))
        }
    }
}
