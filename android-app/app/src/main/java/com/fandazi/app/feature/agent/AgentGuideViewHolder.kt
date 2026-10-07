// 引导共用Holder：登录和空态的按钮在绑定时全部解绑并重新配置，不显示旧账号正文。
package com.fandazi.app.feature.agent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.fandazi.app.R

/** 三个操作槽复用；登录、稍后和快捷问题由Fragment接收，Holder不访问认证仓库。 */
class AgentGuideViewHolder(view:View,private val login:()->Unit,private val later:()->Unit,private val quick:(String)->Unit):RecyclerView.ViewHolder(view) {
    private val root=view as LinearLayout
    private val avatar=view.findViewById<TextView>(R.id.agent_reusable_guide_avatar)
    private val title=view.findViewById<TextView>(R.id.agent_reusable_guide_title)
    private val body=view.findViewById<TextView>(R.id.agent_reusable_guide_body)
    private val actions=listOf<TextView>(view.findViewById(R.id.agent_quick_nearby),view.findViewById(R.id.agent_quick_deal),view.findViewById(R.id.agent_quick_order))
    /** 复用原已确认文案/尺寸；登录头像和第三槽隐藏，前两槽保留显式登录/稍后操作。 */
    fun bind(row:AgentRenderRow.Guide) {
        recycle();root.id=if(row.login)R.id.agent_login_gate else R.id.agent_chat_empty_state
        root.background=if(row.login)ContextCompat.getDrawable(itemView.context,R.drawable.group_buy_card_background) else null
        root.setPadding(dp(if(row.login)28 else 0),dp(if(row.login)28 else 64),dp(if(row.login)28 else 0),dp(if(row.login)28 else 32))
        (root.layoutParams as? RecyclerView.LayoutParams)?.topMargin=dp(if(row.login)180 else 0)
        avatar.isVisible=!row.login;title.text=if(row.login)"登录后即可使用 AI 助手" else "你好，我是饭搭子"
        title.textSize=if(row.login)20f else 22f;(title.layoutParams as LinearLayout.LayoutParams).topMargin=dp(if(row.login)0 else 34)
        body.text=if(row.login)"登录后可以查询订单、购买团购和使用团购券。" else "可以帮你找餐厅、选团购和查看订单";body.textSize=if(row.login)13f else 14f
        val ids=if(row.login)listOf(R.id.agent_login_action,R.id.agent_login_later,R.id.agent_quick_order) else listOf(R.id.agent_quick_nearby,R.id.agent_quick_deal,R.id.agent_quick_order)
        val labels=if(row.login)listOf("立即登录","稍后再说","") else listOf("附近有什么好吃的？","帮我找性价比高的团购","查看我的订单")
        actions.forEachIndexed { i, action ->
            action.id=ids[i];action.text=labels[i];action.isVisible=!row.login || i<2;action.isEnabled=true;action.alpha=1f
            action.setTextColor(ContextCompat.getColor(itemView.context,if(row.login && i==0)R.color.surface_card else if(row.login)R.color.group_buy_orange else R.color.group_buy_text))
            action.background=if(row.login && i==1)null else ContextCompat.getDrawable(itemView.context,if(row.login)R.drawable.group_buy_primary_button else R.drawable.agent_chat_input_background)
            (action.layoutParams as LinearLayout.LayoutParams).apply { height=dp(if(row.login && i==0)44 else 38);topMargin=dp(if(i==0){if(row.login)28 else 52}else{if(row.login)10 else 14});marginStart=dp(if(row.login)0 else 16);marginEnd=marginStart }
            if(action.isVisible) action.setOnClickListener { if(row.login){if(i==0)login() else later()}else quick(labels[i]) }
        }
        itemView.tag=row.key
    }
    /** 清除所有操作捕获，登录→空态和空态→登录均不沿用旧回调。 */
    fun recycle(){actions.forEach{it.setOnClickListener(null);it.isEnabled=false;it.text=""};itemView.tag=null}
    private fun dp(n:Int)=(n*itemView.resources.displayMetrics.density).toInt()
}
