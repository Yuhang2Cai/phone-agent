// 门店秒杀目录U01：直接读取门店活动，不使用普通套餐使用日期过滤，也不在目录预占库存。
package com.fandazi.app.feature.seckill

import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.purchase.*
import com.fandazi.app.feature.store.CatalogQuery
import kotlinx.coroutines.*

/** 列表每5秒只读刷新；卡片可提前进入详情，购买资格只在详情服务器核对。 */
class SeckillStoreFragment:Fragment(R.layout.fragment_seckill_store) {
    private val app get()=requireActivity().application as FandaziApplication
    private val storeId get()=requireArguments().getString("storePublicId").orEmpty()
    private var rows=emptyList<SeckillActivity>()
    private var adapter:Activities?=null
    private val clock=SeckillClock()
    /** 真实门店名与真实活动分别读取；任一失败都不能填入设计示例。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(view){root,insets->root.updatePadding(top=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top);insets}
        ViewCompat.requestApplyInsets(view)
        view.findViewById<View>(R.id.sk_back).setOnClickListener {findNavController().navigateUp()}
        view.findViewById<View>(R.id.sk_orders).setOnClickListener {findNavController().navigate(R.id.ordersFragment)}
        view.findViewById<View>(R.id.sk_tab_deals).setOnClickListener {openStore("DEALS")}
        view.findViewById<View>(R.id.sk_tab_dishes).setOnClickListener {openStore("DISHES")}
        view.findViewById<View>(R.id.sk_tab_reviews).setOnClickListener {openStore("REVIEWS")}
        val list=view.findViewById<RecyclerView>(R.id.sk_activity_list)
        val layout=LinearLayoutManager(requireContext());list.layoutManager=layout
        val cards=Activities(::openActivity,clock);adapter=cards;list.adapter=cards
        view.findViewById<TextView>(R.id.sk_primary).text="查看秒杀券"
        view.findViewById<TextView>(R.id.sk_hint).text="到点手动抢购 · 具体限购以活动为准"
        view.findViewById<View>(R.id.sk_primary).isEnabled=false
        view.findViewById<View>(R.id.sk_primary).setOnClickListener {rows.getOrNull(layout.findFirstVisibleItemPosition().coerceAtLeast(0))?.let(::openActivity)}
        val repository=HttpSeckillRepository(app.apiClient)
        viewLifecycleOwner.lifecycleScope.launch {viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {when(val result=app.catalogRepository.store(storeId,CatalogQuery())) {
                is ApiResult.Success->view.findViewById<TextView>(R.id.sk_nav_title).text=result.value.name
                is ApiResult.Failure->view.findViewById<TextView>(R.id.sk_nav_title).text="门店好券"
            }}
            launch {
                while(isActive) {
                    when(val result=repository.list(storeId)) {
                        is ApiResult.Failure->{view.findViewById<TextView>(R.id.sk_list_message).text=purchaseError(result.error)+"，正在等待恢复"}
                        is ApiResult.Success->{
                            rows=result.value.value
                            rows.firstOrNull()?.let {clock.synchronize(it.serverTime,result.value.started,result.value.received)}
                            cards.submitList(rows)
                            view.findViewById<TextView>(R.id.sk_list_message).text=if(rows.isEmpty())"这家店暂时没有秒杀活动" else "提前看看，到点再抢"
                            view.findViewById<View>(R.id.sk_primary).isEnabled=rows.isNotEmpty()
                        }
                    }
                    delay(5000)
                }
            }
            launch {while(isActive){for(i in 0 until list.childCount)(list.getChildViewHolder(list.getChildAt(i)) as? ActivityHolder)?.tick(clock);delay(250)}}
        }}
    }
    /** 栏目实际导航回原门店的对应Tab，不绘制没有行为的装饰按钮。 */
    private fun openStore(tab:String)=findNavController().navigate(R.id.storeFragment,Bundle().apply {putString("storePublicId",storeId);putString("initialStoreTab",tab)})
    /** 资源来自点击卡片对应的服务器记录，不默认创建第一笔订单。 */
    private fun openActivity(activity:SeckillActivity)=findNavController().navigate(R.id.seckillFragment,Bundle().apply {putString("activityId",activity.id)})
    /** 列表销毁释放可见图片及适配器，后台不保留页面请求。 */
    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.sk_activity_list)?.let {list->for(i in 0 until list.childCount)SeckillImages.clear(list.getChildAt(i).findViewById(R.id.sk_hero));list.adapter=null}
        adapter=null;super.onDestroyView()
    }
    /** Diff只更新变化卡片，计时不提交整个列表。 */
    private class Activities(private val click:(SeckillActivity)->Unit,private val clock:SeckillClock):ListAdapter<SeckillActivity,ActivityHolder>(object:DiffUtil.ItemCallback<SeckillActivity>() {
        override fun areItemsTheSame(old:SeckillActivity,new:SeckillActivity)=old.id==new.id
        override fun areContentsTheSame(old:SeckillActivity,new:SeckillActivity)=old.copy(serverTime=new.serverTime)==new
    }) {
        /** 每个业务行从确认稿XML创建，禁止在适配器临时拼纯文本页面。 */
        override fun onCreateViewHolder(parent:ViewGroup,type:Int)=ActivityHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_seckill_activity,parent,false))
        /** 点击绑定当前公开对象，复用时不能引用上一个活动。 */
        override fun onBindViewHolder(holder:ActivityHolder,position:Int){holder.bind(getItem(position),click);holder.tick(clock)}
        /** 离屏清本行图片，公开缓存继续复用。 */
        override fun onViewRecycled(holder:ActivityHolder){holder.release();super.onViewRecycled(holder)}
    }
    /** 卡片事实和时钟分别更新，图片只有URL/版本变化才重新绑定。 */
    private class ActivityHolder(view:View):RecyclerView.ViewHolder(view) {
        private var value:SeckillActivity?=null
        private var imageKey:String?=null
        /** 回收时撤销图片绑定身份，下次同一业务行复用仍必须重新接上已清理的ImageView。 */
        fun release(){SeckillImages.clear(itemView.findViewById(R.id.sk_hero));imageKey=null;value=null}
        private fun text(id:Int,text:String){itemView.findViewById<TextView>(id).let {if(it.text.toString()!=text)it.text=text}}
        /** 所有商品文字、金额、库存来自后端，不使用Figma中的示例数字。 */
        fun bind(a:SeckillActivity,click:(SeckillActivity)->Unit) {
            value=a;val deal=a.deal
            text(R.id.sk_title,deal.title);text(R.id.sk_price,seckillMoney(deal.priceCents));text(R.id.sk_original,seckillMoney(deal.originalPriceCents))
            itemView.findViewById<TextView>(R.id.sk_original).apply {paintFlags=paintFlags or Paint.STRIKE_THRU_TEXT_FLAG}
            text(R.id.sk_saving,"省 ${seckillMoney(deal.originalPriceCents-deal.priceCents)}");text(R.id.sk_description,deal.description)
            text(R.id.sk_stock,"剩余 ${a.available} / ${a.total} 份　·　每人限购 ${a.limit} 份")
            itemView.findViewById<ProgressBar>(R.id.sk_stock_bar).apply {max=a.total;progress=a.available}
            text(R.id.sk_timer_title,if(a.state=="UPCOMING")"距开抢" else "距结束");text(R.id.sk_timer_caption,"到点进入详情核对资格")
            val key="${a.id}:${a.version}:${deal.imageUrl}"
            if(key!=imageKey){imageKey=key;SeckillImages.bind(itemView.findViewById(R.id.sk_hero),deal.imageUrl,deal.imageAlt,a.version.toString())}
            itemView.setOnClickListener {click(a)}
        }
        /** 归零后仍只导航查看详情，卡片本身不开放任何写入操作。 */
        fun tick(clock:SeckillClock) {
            val a=value ?: return;val remaining=clock.remainingMillis(if(a.state=="UPCOMING")a.startsAt else a.endsAt,SystemClock.elapsedRealtime())
            val sec=remaining?.let {(it+999)/1000}
            text(R.id.sk_hours,sec?.let {(it/3600).toString().padStart(2,'0')} ?: "--")
            text(R.id.sk_minutes,sec?.let {((it/60)%60).toString().padStart(2,'0')} ?: "--")
            text(R.id.sk_seconds,sec?.let {(it%60).toString().padStart(2,'0')} ?: "--")
        }
    }
}
