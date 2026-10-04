// 共用消息Holder：完整绑定重置所有样式，只有相同普通气泡的纯正文差量允许局部刷新。
package com.fandazi.app.feature.agent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.fandazi.app.R

/** 文字、错误、上下文共享一组控件；retry委托Fragment，不持有消息下标。 */
class AgentMessageViewHolder(view: View, private val retryAction: () -> Unit) : RecyclerView.ViewHolder(view) {
    private val root=view as LinearLayout
    private val avatar=view.findViewById<TextView>(R.id.agent_message_avatar)
    private val title=view.findViewById<TextView>(R.id.agent_reusable_message_title)
    private val body=view.findViewById<TextView>(R.id.agent_message_text)
    private val retry=view.findViewById<TextView>(R.id.agent_error_retry)
    /** 完整重置后配置样式，防止错误按钮、居中正文或用户背景残留到下一项。 */
    fun bind(row: AgentRenderRow.Message) {
        recycle()
        // 保留原错误/上下文语义ID，使辅助定位和“错误已经消失”断言可检查实际状态，不能因共用骨架丢掉身份。
        root.id=when(row.style){AgentMessageStyle.ERROR->R.id.agent_error_card;AgentMessageStyle.CONTEXT->R.id.agent_context_card;else->R.id.agent_message_root}
        root.gravity=if(row.style==AgentMessageStyle.USER) Gravity.END else Gravity.START
        root.minimumHeight=0;root.setPadding(0,0,0,0);root.background=null
        body.id=R.id.agent_message_text;body.text=row.body;body.textSize=14f;body.setTypeface(null,Typeface.NORMAL)
        body.gravity=Gravity.START;body.maxWidth=dp(if(row.style==AgentMessageStyle.USER)285 else 322)
        body.minimumHeight=dp(46);body.setPadding(dp(16),dp(12),dp(16),dp(12))
        body.setTextColor(color(R.color.group_buy_text));body.layoutParams=LinearLayout.LayoutParams(-2,-2)
        body.setBackgroundResource(if(row.style==AgentMessageStyle.USER)R.drawable.agent_chat_user_bubble else R.drawable.agent_chat_bot_bubble)
        title.id=R.id.agent_reusable_message_title;title.text="";title.isVisible=false;title.setTypeface(null,Typeface.BOLD)
        title.setTextColor(color(R.color.group_buy_green));title.gravity=Gravity.START
        avatar.isVisible=row.style==AgentMessageStyle.ASSISTANT
        retry.text="重新尝试";retry.alpha=1f;retry.isEnabled=row.retry;retry.isVisible=row.retry
        // 错误与上下文使用同一标题正文控件，但取消气泡内背景并恢复全宽排版。
        if(row.style==AgentMessageStyle.ERROR || row.style==AgentMessageStyle.CONTEXT) {
            val error=row.style==AgentMessageStyle.ERROR
            root.setBackgroundResource(R.drawable.group_buy_card_background)
            root.minimumHeight=dp(if(error)172 else 128);val padding=dp(if(error)22 else 16)
            root.setPadding(padding,padding,padding,padding);root.gravity=if(error)Gravity.CENTER_HORIZONTAL else Gravity.START
            title.id=if(error)R.id.agent_reusable_message_title else R.id.agent_context_title
            title.isVisible=true;title.text=row.title;title.textSize=if(error)19f else 14f;title.gravity=if(error)Gravity.CENTER else Gravity.START
            body.id=if(error)R.id.agent_error_text else R.id.agent_context_body
            body.background=null;body.setPadding(0,dp(if(error)14 else 12),0,0);body.minimumHeight=0
            body.maxWidth=Int.MAX_VALUE;body.textSize=13f;body.setTextColor(color(R.color.group_buy_muted))
            body.gravity=if(error)Gravity.CENTER else Gravity.START;body.layoutParams=LinearLayout.LayoutParams(-1,-2)
        }
        if(row.retry) retry.setOnClickListener{retryAction()}
        itemView.tag=row.key
    }
    /** 仅同款普通气泡正文更新；Adapter保证样式/身份等所有其他字段一致。 */
    fun bindText(row: AgentRenderRow.Message) { body.text=row.body;itemView.tag=row.key }
    /** 回收解除操作监听和旧内容；下次完整绑定必须覆盖全部视觉参数。 */
    fun recycle() { retry.setOnClickListener(null);retry.isVisible=false;retry.isEnabled=false;body.text="";title.text="";itemView.tag=null }
    private fun dp(n:Int)=(n*itemView.resources.displayMetrics.density).toInt()
    private fun color(id:Int)=ContextCompat.getColor(itemView.context,id)
}
