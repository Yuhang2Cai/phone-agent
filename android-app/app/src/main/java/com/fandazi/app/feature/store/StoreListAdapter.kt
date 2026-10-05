// 首页/搜索回收列表：复用既有卡片XML，后台Diff只读取不可变行值，导航由当前Fragment承担。
package com.fandazi.app.feature.store

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fandazi.app.R
import com.fandazi.app.feature.home.CatalogState

/** 行身份由后端UUID决定；状态行与业务行隔离，不让加载文本冒充可点击门店。 */
sealed interface StoreListRow {
    /** 固定结构键或完整公开UUID，供差量比较及薄锚点恢复。 */
    val key:String
    /** 查询随卡片保存，点击沿用本条所对应的定位参数，而不是迟到的外部状态。 */
    data class Store(val value:CatalogStore,val query:CatalogQuery):StoreListRow {override val key="store:${value.publicId}"}
    /** 状态文案是页面结构，retry只表示是否允许当前查询重试，无业务事实。 */
    data class Status(val message:String,val retry:Boolean=false):StoreListRow {override val key="status"}
}

/** 实际创建/绑定诊断，供固定设备测量；不能由viewType数量推导帧或内存收益。 */
class BusinessListMetrics(types:Int) {
    /** 各骨架实际创建次数；数组长度来自固定结构类型数，仅在主线程更新。 */
    val creates=LongArray(types)
    /** 各骨架完整绑定次数，用于区分回收复用与整表重新绘制。 */
    val binds=LongArray(types)
}

/** 两个Holder池服务首页与搜索；既有图片状态、筛选及后端顺序保持。 */
class StoreListAdapter(private val open:(CatalogStore,CatalogQuery)->Unit,private val retry:()->Unit):ListAdapter<StoreListRow,RecyclerView.ViewHolder>(DIFF) {
    /** 当前Adapter实例的实际创建/绑定计数，不代表帧或内存收益。 */
    val metrics=BusinessListMetrics(2)
    init {stateRestorationPolicy=RecyclerView.Adapter.StateRestorationPolicy.PREVENT}
    /** 条件变化只提交新状态，成功集合预先复制，失败没有可执行旧卡片。 */
    fun submitState(state:CatalogState,committed:()->Unit={}) {
        val rows:List<StoreListRow> = when {
            state.loading->listOf(StoreListRow.Status("正在加载门店"))
            state.error!=null->listOf(StoreListRow.Status(state.error,true))
            state.stores.isEmpty()->listOf(StoreListRow.Status("没有符合条件的门店"))
            else->CatalogRowIdentity.stores(state.stores).map{StoreListRow.Store(it,state.query)}
        }
        submitList(rows,committed)
    }
    /** 外部锚点引用当前集合的完整公开行键，不返回显示名称或位置号。 */
    fun rowKey(position:Int):String?=currentList.getOrNull(position)?.key
    /** 卡片与状态使用不同骨架，不能让文字状态Holder持有原卡片点击监听。 */
    override fun getItemViewType(position:Int)=if(getItem(position) is StoreListRow.Store)0 else 1
    /** 骨架只在创建时inflate；bind期间全部重置事实和监听，不整体重建子树。 */
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):RecyclerView.ViewHolder {
        metrics.creates[viewType]++
        val layout=if(viewType==0)R.layout.item_group_buy_store_card else R.layout.item_catalog_list_status
        val view=LayoutInflater.from(parent.context).inflate(layout,parent,false)
        return if(viewType==0)CardHolder(view) else StatusHolder(view)
    }
    /** 完整绑定使用当前行值，复用时旧图片任务及事件由绑定器取消/覆盖。 */
    override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int) {
        metrics.binds[getItemViewType(position)]++
        when(val row=getItem(position)) {
            is StoreListRow.Store->CatalogRenderer.bindStore(holder.itemView,row.value,row.query){open(row.value,row.query)}
            is StoreListRow.Status->(holder.itemView as TextView).apply {
                text=row.message;setOnClickListener(if(row.retry)View.OnClickListener{retry()} else null);isClickable=row.retry
            }
        }
    }
    /** 滑出并被回收时释放元数据、Glide和点击，页面销毁也调用相同路径。 */
    fun release(holder:RecyclerView.ViewHolder) {if(holder is CardHolder)CatalogRenderer.releaseStore(holder.itemView) else holder.itemView.setOnClickListener(null)}
    /** 回收池保留骨架而不保留旧请求；新绑定重新安装当前资源事件。 */
    override fun onViewRecycled(holder:RecyclerView.ViewHolder){release(holder);super.onViewRecycled(holder)}
    private class CardHolder(view:View):RecyclerView.ViewHolder(view)
    private class StatusHolder(view:View):RecyclerView.ViewHolder(view)
    companion object {
        /** 纯数据身份和内容比较，变化行完整重绑；不在后台访问UI、账号或网络。 */
        val DIFF=object:DiffUtil.ItemCallback<StoreListRow>() {
            override fun areItemsTheSame(oldItem:StoreListRow,newItem:StoreListRow)=oldItem.key==newItem.key
            override fun areContentsTheSame(oldItem:StoreListRow,newItem:StoreListRow)=oldItem==newItem
        }
    }
}
