// 进度共用Holder：只在槽位不足时新增步骤控件，终态隐藏动画，旧步骤槽不再整组重建。
package com.fandazi.app.feature.agent
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.fandazi.app.R

/** 进度槽创建回调用于实际计数；步骤公开标签来自已冻结投影。 */
class AgentProgressViewHolder(view:View,private val onSlotCreated:()->Unit):RecyclerView.ViewHolder(view) {
    private val title=view.findViewById<TextView>(R.id.agent_progress_title)
    private val container=view.findViewById<LinearLayout>(R.id.agent_progress_steps)
    private data class Slot(val row:LinearLayout,val spinner:ProgressBar,val mark:TextView,val label:TextView,val status:TextView)
    private val slots=mutableListOf<Slot>()
    /** 不够时追加一次性槽位，已有步骤只更新文字和动画状态；多余旧槽隐藏。 */
    fun bind(row:AgentRenderRow.Progress) {
        while(slots.size<row.steps.size) slots+=createSlot()
        title.text=row.title;slots.forEachIndexed { index, slot ->
            val step=row.steps.getOrNull(index);slot.row.isVisible=step!=null
            slot.spinner.isVisible=step?.active==true;slot.mark.isVisible=step!=null && !step.active
            slot.mark.text=if(step?.status=="已完成")"✓" else "○";slot.mark.setTextColor(ContextCompat.getColor(itemView.context,if(step?.status=="已完成")R.color.group_buy_green else R.color.group_buy_orange))
            slot.label.text=step?.label.orEmpty();slot.status.text=step?.status.orEmpty();slot.row.tag=step?.key
        };itemView.tag=row.key
    }
    /** 只有槽容量增长才创建四个子控件，不按每次事件inflate完整业务树。 */
    private fun createSlot():Slot {
        val c=itemView.context;val row=LinearLayout(c).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(38)}
        val spinner=ProgressBar(c).apply{isIndeterminate=true};val mark=TextView(c)
        val label=TextView(c).apply{textSize=13f;setTextColor(ContextCompat.getColor(c,R.color.group_buy_text));setPadding(dp(8),0,dp(8),0)}
        val status=TextView(c).apply{textSize=12f;setTextColor(ContextCompat.getColor(c,R.color.group_buy_muted))}
        row.addView(spinner,LinearLayout.LayoutParams(dp(18),dp(18)));row.addView(mark,LinearLayout.LayoutParams(dp(18),dp(24)))
        row.addView(label,LinearLayout.LayoutParams(0,-2,1f));row.addView(status);container.addView(row);onSlotCreated()
        return Slot(row,spinner,mark,label,status)
    }
    /** 离屏/销毁时停止活动显示并清除步骤文字；保留槽位供下一绑定复用。 */
    fun recycle(){slots.forEach{it.spinner.isVisible=false;it.row.isVisible=false;it.row.tag=null;it.label.text="";it.status.text=""};title.text="";itemView.tag=null}
    private fun dp(n:Int)=(n*itemView.resources.displayMetrics.density).toInt()
}
