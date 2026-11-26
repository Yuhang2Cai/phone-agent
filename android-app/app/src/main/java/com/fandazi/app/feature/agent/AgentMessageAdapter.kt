// 聊天差量适配器：四类回收池、纯数据后台比较、稳定展示ID与正文payload，不承担业务调用。
package com.fandazi.app.feature.agent
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fandazi.app.R

/** 计数只描述本Adapter实测创建/绑定，不据四类数量推断性能收益。 */
class AgentRenderMetrics {
    val creates=LongArray(4)
    val binds=LongArray(4)
    var textPayloads=0L
    var stepSlots=0L
    var renderedFrames=0L
    var renderNanos=0L
    var maxRenderNanos=0L
    /** 测量一帧主线程投影/提交耗时，后台Diff和网络耗时须分别测量。 */
    fun frame(nanos:Long){renderedFrames++;renderNanos+=nanos;maxRenderNanos=maxOf(maxRenderNanos,nanos)}
}

/** 公开ID/摘要交回Fragment，后台比较只接触行数据；每个key的数值ID在Adapter生命周期内固定。 */
class AgentMessageAdapter(private val navigate:(AgentNavigationRequest,AgentCardPayload)->Unit,private val retry:()->Unit,
    private val login:()->Unit,private val later:()->Unit,private val quick:(String)->Unit):ListAdapter<AgentRenderRow,RecyclerView.ViewHolder>(DIFF) {
    val metrics=AgentRenderMetrics()
    private val ids=mutableMapOf<String,Long>()
    private var nextId=1L
    init {setHasStableIds(true)}
    /** 提交前在主线程分配展示数值ID；不使用位置或hash，账号切换由Fragment更换Adapter。 */
    fun submitRows(rows:List<AgentRenderRow>,committed:()->Unit) {rows.forEach{ids.getOrPut(it.key){nextId++}};submitList(rows,committed)}
    /** 行定位返回预先分配的无冲突数值，不把正文hash或当前列表位置当作稳定身份。 */
    override fun getItemId(position:Int)=ids.getValue(getItem(position).key)
    /** 四个枚举值是唯一回收类型，卡片子类和消息角色不会产生额外池。 */
    override fun getItemViewType(position:Int)=getItem(position).type.ordinal
    /** 每个Holder仅在创建时inflate共用骨架；绑定期间不重建整个业务布局。 */
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):RecyclerView.ViewHolder {
        val inflater=LayoutInflater.from(parent.context);metrics.creates[viewType]++
        return when(AgentRowType.entries[viewType]) {
            AgentRowType.CARD->AgentCardViewHolder(inflater.inflate(R.layout.item_agent_reusable_card,parent,false),navigate)
            AgentRowType.MESSAGE->AgentMessageViewHolder(inflater.inflate(R.layout.item_agent_reusable_message,parent,false),retry)
            AgentRowType.GUIDE->AgentGuideViewHolder(inflater.inflate(R.layout.item_agent_reusable_guide,parent,false),login,later,quick)
            AgentRowType.PROGRESS->AgentProgressViewHolder(inflater.inflate(R.layout.item_agent_progress,parent,false)){metrics.stepSlots++}
        }
    }
    /** 离屏或payload未交付均走完整绑定，所有样式和动作由对应Holder完全重置。 */
    override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int) {
        val row=getItem(position);metrics.binds[row.type.ordinal]++
        when(holder){is AgentCardViewHolder->holder.bind(row as AgentRenderRow.Card);is AgentMessageViewHolder->holder.bind(row as AgentRenderRow.Message);is AgentGuideViewHolder->holder.bind(row as AgentRenderRow.Guide);is AgentProgressViewHolder->holder.bind(row as AgentRenderRow.Progress)}
    }
    /** 只有正文改变且样式/资格相同才局部更新，业务卡和错误从不借文字payload跳过重置。 */
    override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int,payloads:MutableList<Any>) {
        if(holder is AgentMessageViewHolder && payloads.isNotEmpty() && payloads.all{it===TEXT_PAYLOAD}) {metrics.textPayloads++;holder.bindText(getItem(position) as AgentRenderRow.Message)}else onBindViewHolder(holder,position)
    }
    /** 回收清图像、监听和活动步骤；销毁时Fragment同时移除Adapter与回收池。 */
    override fun onViewRecycled(holder:RecyclerView.ViewHolder){release(holder);super.onViewRecycled(holder)}
    /** 销毁页面时可显式释放仍附着的Holder，不依赖回收回调的时机。 */
    fun release(holder:RecyclerView.ViewHolder){when(holder){is AgentCardViewHolder->holder.recycle();is AgentMessageViewHolder->holder.recycle();is AgentGuideViewHolder->holder.recycle();is AgentProgressViewHolder->holder.recycle()}}
    companion object {
        /** 纯正文变化标记；不包含可变闭包或授权对象。 */
        val TEXT_PAYLOAD=Any()
        /** 纯数据差量规则，后台不读取ViewModel或认证状态。 */
        val DIFF=object:DiffUtil.ItemCallback<AgentRenderRow>() {
            /** 稳定身份与骨架一致才是同一行；业务资源ID不承担整轮消息身份。 */
            override fun areItemsTheSame(oldItem:AgentRenderRow,newItem:AgentRenderRow)=oldItem.key==newItem.key && oldItem.type==newItem.type
            /** 内容含动作有效性快照，确认版本失效即产生差量，不在后台访问认证仓库。 */
            override fun areContentsTheSame(oldItem:AgentRenderRow,newItem:AgentRenderRow)=oldItem==newItem
            /** 仅正文变化时允许局部绑定，其他任一样式/动作变化都完整重置。 */
            override fun getChangePayload(oldItem:AgentRenderRow,newItem:AgentRenderRow):Any? =
                if(oldItem is AgentRenderRow.Message && newItem is AgentRenderRow.Message && oldItem.style in setOf(AgentMessageStyle.USER,AgentMessageStyle.ASSISTANT) && oldItem.copy(body=newItem.body)==newItem)TEXT_PAYLOAD else null
        }
    }
}
