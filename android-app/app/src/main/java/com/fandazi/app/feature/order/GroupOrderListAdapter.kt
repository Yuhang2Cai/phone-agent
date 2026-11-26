// 团购订单回收列表：只展示当前账号不可变权威快照，复用UI10卡片，点击返回公开订单号。
package com.fandazi.app.feature.order

import android.view.*
import android.widget.TextView
import androidx.recyclerview.widget.*
import com.fandazi.app.R
import com.fandazi.app.core.time.BusinessDisplay
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.voucher.*

/** 订单私有事实无持久缓存，Diff只读字段；退出时Fragment立即隐藏列表并清空Adapter。 */
class GroupOrderListAdapter(private val open:(String)->Unit):ListAdapter<GroupOrderSummary,GroupOrderListAdapter.Holder>(DIFF) {
    /** 本实例订单骨架统计，退出账号后不会作为业务缓存保留。 */
    val metrics=BusinessListMetrics(1)
    init{stateRestorationPolicy=RecyclerView.Adapter.StateRestorationPolicy.PREVENT}
    /** 保留后端原始顺序并复制券集合，分类已由页面按现有权威枚举筛选。 */
    fun submitOrders(rows:List<GroupOrderSummary>,committed:()->Unit={}){submitList(CatalogRowIdentity.orders(rows),committed)}
    /** 锚点使用本人的公开订单资源ID，不使用展示标题或列表位置。 */
    fun rowKey(position:Int):String?=currentList.getOrNull(position)?.let{"order:${it.orderId}"}
    /** 仅创建已确认订单卡片骨架，不请求或准备交易。 */
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):Holder{metrics.creates[0]++;return Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_group_order_card,parent,false))}
    /** 文字、金额、数量、状态、日期及监听全部重置，不残留旧订单事实。 */
    override fun onBindViewHolder(holder:Holder,position:Int) {
        metrics.binds[0]++;val order=getItem(position);val card=holder.itemView
        card.contentDescription="order-card-${order.orderId}"
        card.findViewById<TextView>(R.id.group_order_title).text=order.storeName
        card.findViewById<TextView>(R.id.group_order_deal).text=order.dealTitle
        card.findViewById<TextView>(R.id.group_order_state).text=VoucherPresentation.orderStatus(order.status)
        card.findViewById<TextView>(R.id.group_order_time).text=BusinessDisplay.time(order.createdAt)
        card.findViewById<TextView>(R.id.group_order_summary).text="数量 ${order.quantity} · ${order.voucherCount} 张团购券"
        card.findViewById<TextView>(R.id.group_order_total).text=VoucherPresentation.amount(order)
        card.setOnClickListener{open(order.orderId)}
    }
    /** 回收或销毁时移除旧订单号闭包，复用后只响应新行。 */
    fun release(holder:RecyclerView.ViewHolder){holder.itemView.setOnClickListener(null);holder.itemView.contentDescription=null}
    /** 池内不保留旧订单点击闭包，完整绑定后才重新开放导航。 */
    override fun onViewRecycled(holder:Holder){release(holder);super.onViewRecycled(holder)}
    /** 只持有当前骨架View，不持有账号令牌、订单仓库或数据库。 */
    class Holder(view:View):RecyclerView.ViewHolder(view)
    companion object {
        /** 身份和完整事实比较，资格变化须产生差量，不从状态文字反推后台许可。 */
        val DIFF=object:DiffUtil.ItemCallback<GroupOrderSummary>() {
            override fun areItemsTheSame(oldItem:GroupOrderSummary,newItem:GroupOrderSummary)=oldItem.orderId==newItem.orderId
            override fun areContentsTheSame(oldItem:GroupOrderSummary,newItem:GroupOrderSummary)=oldItem==newItem
        }
    }
}
