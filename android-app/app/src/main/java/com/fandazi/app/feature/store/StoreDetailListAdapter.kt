// 门店单纵向回收列表：摘要、固定页签和真实业务行处于同一个有界RV，复用原UI03/04/05骨架。
package com.fandazi.app.feature.store

import android.graphics.Paint
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.DealPackage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 行键显式命名空间隔离固定结构与后端UUID；同名、同价、同作者不会合并资源。 */
sealed interface StoreDetailRow {
    /** 公开业务ID或固定结构命名空间，不能用名称/位置替代。 */
    val key:String
    /** 实际布局骨架类型；同类型内容变化仅影响行差量。 */
    val type:Int
    /** 独立摘要行保留门店路径及坐标上下文，加载/失败不会伪造店名。 */
    data class Header(val id:String,val query:CatalogQuery,val store:CatalogStore?,val loading:Boolean,val error:Boolean):StoreDetailRow {override val key="header:$id";override val type=0}
    /** 三页签固定骨架，仅保存当前可见页签，不执行业务写入。 */
    data class Tabs(val tab:StoreTab):StoreDetailRow {override val key="tabs";override val type=1}
    /** 全量评分统计来自摘要，不能用当前最近评价条目重新计数。 */
    data class Rating(val store:CatalogStore):StoreDetailRow {override val key="rating:${store.publicId}";override val type=2}
    /** 套餐完整快照及其公开UUID，点击后详情仍重新读取权威事实。 */
    data class Deal(val value:DealPackage):StoreDetailRow {override val key="deal:${value.publicId}";override val type=3}
    /** 菜单公开UUID和所属请求门店，用于身份精确图片刷新。 */
    data class Dish(val value:CatalogDish,val storeId:String):StoreDetailRow {override val key="dish:${value.publicId}";override val type=4}
    /** 评价独立公开UUID，时间已在协议边界验证。 */
    data class Review(val value:CatalogReview):StoreDetailRow {override val key="review:${value.publicId}";override val type=5}
    /** 局部加载/失败/空态结构，retry只表示当前页签重试入口。 */
    data class Status(val message:String,val retry:Boolean):StoreDetailRow {override val key="status";override val type=6}
}

/** 结构不同才分池；七个类型覆盖摘要、页签、评分、三种业务骨架与状态，池大小由实测决定。 */
class StoreDetailListAdapter(private val back:()->Unit,private val select:(StoreTab)->Unit,private val summaryRetry:()->Unit,
    private val retry:()->Unit,private val open:(DealPackage)->Unit):ListAdapter<StoreDetailRow,RecyclerView.ViewHolder>(DIFF) {
    /** 七类骨架的实际创建/绑定统计，需结合固定设备测量解释。 */
    val metrics=BusinessListMetrics(7)
    init{stateRestorationPolicy=RecyclerView.Adapter.StateRestorationPolicy.PREVENT}
    /** 摘要晚到只更新Header/Rating，业务条目同身份同内容不重新绑定，也不再次查询评价。 */
    fun submitState(state:StoreBrowseState,committed:()->Unit={}) {
        val rows=buildList {
            add(StoreDetailRow.Header(state.storeId,state.query,state.summary,state.summaryLoading,state.summaryError));add(StoreDetailRow.Tabs(state.tab))
            if(state.tab==StoreTab.REVIEWS && state.summary!=null)add(StoreDetailRow.Rating(state.summary))
            when {
                state.loading->add(StoreDetailRow.Status("正在加载${state.tab.label}",false))
                state.error->add(StoreDetailRow.Status("${state.tab.label} 加载失败，点击重试",true))
                state.rows.isEmpty()->add(StoreDetailRow.Status(when(state.tab){StoreTab.DEALS->"暂无可售套餐";StoreTab.DISHES->"暂无菜品";StoreTab.REVIEWS->"暂无评价"},false))
                else->addAll(state.rows)
            }
        }
        submitList(rows,committed)
    }
    /** 锚点仅消费当前不可变行键，不基于名称或数组索引生成业务身份。 */
    fun rowKey(position:Int):String?=currentList.getOrNull(position)?.key
    /** 返回冻结行的结构类型，无网络、认证或动态业务判断。 */
    override fun getItemViewType(position:Int)=getItem(position).type
    /** 创建一次原骨架；评分面板只有五个固定星级条，生命周期内不重建其子树。 */
    override fun onCreateViewHolder(parent:ViewGroup,viewType:Int):RecyclerView.ViewHolder {
        metrics.creates[viewType]++
        if(viewType==2)return RatingHolder(parent)
        val layout=when(viewType){0->R.layout.item_store_header;1->R.layout.item_store_tabs;3->R.layout.item_store_deal;4->R.layout.item_store_dish;5->R.layout.item_store_review;else->R.layout.item_catalog_list_status}
        return RowHolder(LayoutInflater.from(parent.context).inflate(layout,parent,false))
    }
    /** 每次完整绑定覆盖文字、图片、描述、金额与监听；图片刷新只接受同公开身份资源。 */
    override fun onBindViewHolder(holder:RecyclerView.ViewHolder,position:Int) {
        val row=getItem(position);metrics.binds[row.type]++
        val view=holder.itemView;val application=view.context.applicationContext as FandaziApplication
        when(row) {
            is StoreDetailRow.Header->{
                view.findViewById<View>(R.id.store_back).setOnClickListener{back()}
                view.findViewById<TextView>(R.id.store_title).apply {text=when{row.loading->"正在加载门店";row.error->"门店加载失败，点击重试";else->row.store?.name.orEmpty()};setOnClickListener(if(row.error)View.OnClickListener{summaryRetry()}else null)}
                view.findViewById<TextView>(R.id.store_meta).text=row.store?.let{"${it.rating?.let{r->"★ $r"} ?: "暂无评分"}  (${it.reviewCount}条评价)  ${CatalogRenderer.distance(it.distanceMeters)}"}.orEmpty()
                CatalogImageMetadataRefresh.bind(view.findViewById(R.id.store_cover),row.store?.imageUrl,row.store?.imageAlt){
                    when(val r=application.catalogRepository.store(row.id,row.query)){is ApiResult.Success->if(r.value.publicId==row.id)r.value.imageUrl to r.value.imageAlt else null;is ApiResult.Failure->null}
                }
            }
            is StoreDetailRow.Tabs->listOf(R.id.store_tab_deals,R.id.store_tab_dishes,R.id.store_tab_reviews).zip(StoreTab.entries).forEach{(id,tab)->view.findViewById<TextView>(id).apply{CatalogRenderer.selection(this,tab==row.tab);setOnClickListener{select(tab)}}}
            is StoreDetailRow.Rating->(holder as RatingHolder).bind(row.store)
            is StoreDetailRow.Deal->{
                val deal=row.value
                view.findViewById<TextView>(R.id.deal_title).text=deal.title;view.findViewById<TextView>(R.id.deal_description).text=deal.description
                view.findViewById<TextView>(R.id.deal_price).text=deal.priceText()
                view.findViewById<TextView>(R.id.deal_old_price).apply{text=deal.originalPriceText();paintFlags=paintFlags or Paint.STRIKE_THRU_TEXT_FLAG}
                view.findViewById<TextView>(R.id.deal_discount).text=CatalogRenderer.discount(deal.priceCents,deal.originalPriceCents)
                CatalogImageMetadataRefresh.bind(view.findViewById(R.id.deal_cover),deal.imageUrl,deal.imageAlt){when(val r=application.groupBuyingRepository.loadDeal(deal.publicId)){is ApiResult.Success->if(r.value.publicId==deal.publicId && r.value.storePublicId==deal.storePublicId)r.value.imageUrl to r.value.imageAlt else null;is ApiResult.Failure->null}}
                view.contentDescription="store-deal-${deal.publicId}";view.setOnClickListener{open(deal)};view.findViewById<View>(R.id.deal_action).setOnClickListener{open(deal)}
            }
            is StoreDetailRow.Dish->{
                val dish=row.value
                view.findViewById<TextView>(R.id.dish_title).text=dish.name;view.findViewById<TextView>(R.id.dish_description).text=dish.description;view.findViewById<TextView>(R.id.dish_price).text=CatalogRenderer.money(dish.priceCents)
                view.contentDescription="store-dish-${dish.publicId}";view.setOnClickListener(null)
                CatalogImageMetadataRefresh.bind(view.findViewById(R.id.dish_cover),dish.imageUrl,dish.name){when(val r=application.catalogRepository.dishes(row.storeId)){is ApiResult.Success->r.value.singleOrNull{it.publicId==dish.publicId}?.let{it.imageUrl to it.name};is ApiResult.Failure->null}}
            }
            is StoreDetailRow.Review->{
                val review=row.value
                view.findViewById<ImageView>(R.id.review_avatar).setImageResource(R.drawable.ic_tab_profile)
                view.findViewById<TextView>(R.id.review_name).text=review.reviewerName;view.findViewById<TextView>(R.id.review_stars).text="★ ${review.rating}"
                view.findViewById<TextView>(R.id.review_content).text=review.content
                view.findViewById<TextView>(R.id.review_date).text=DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.parse(review.createdAt))
                view.contentDescription="store-review-${review.publicId}";view.setOnClickListener(null)
            }
            is StoreDetailRow.Status->(view as TextView).apply{text=row.message;setOnClickListener(if(row.retry)View.OnClickListener{retry()}else null);isClickable=row.retry}
        }
    }
    /** 回收/销毁共同释放图片任务与嵌套按钮，池中骨架无旧交易目标或元数据读取。 */
    fun release(holder:RecyclerView.ViewHolder) {
        val view=holder.itemView;view.setOnClickListener(null);view.contentDescription=null
        listOf(R.id.store_cover,R.id.deal_cover,R.id.dish_cover).forEach{id->view.findViewById<ImageView>(id)?.let{CatalogImageMetadataRefresh.release(it)}}
        listOf(R.id.store_back,R.id.store_title,R.id.store_tab_deals,R.id.store_tab_dishes,R.id.store_tab_reviews,R.id.deal_action).forEach{id->view.findViewById<View>(id)?.setOnClickListener(null)}
    }
    /** 进入池前释放旧事件和图片；池只复用骨架，后续绑定安装新事实。 */
    override fun onViewRecycled(holder:RecyclerView.ViewHolder){release(holder);super.onViewRecycled(holder)}
    private class RowHolder(view:View):RecyclerView.ViewHolder(view)

    /** 五个星级固定骨架沿用原评分布局，bind只更新权威摘要及进度，零评价不造五星。 */
    private class RatingHolder(parent:ViewGroup):RecyclerView.ViewHolder(LinearLayout(parent.context).apply{
        layoutParams=RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT)
        orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        val d=resources.displayMetrics.density;setPadding((12*d).toInt(),(12*d).toInt(),(12*d).toInt(),(20*d).toInt())
    }) {
        private val panel=itemView as LinearLayout
        private val title=TextView(parent.context).apply{textSize=24f;setTextColor(ContextCompat.getColor(context,R.color.group_buy_orange));layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f)}
        private val bars=mutableListOf<ProgressBar>()
        private val counts=mutableListOf<TextView>()
        init {
            panel.addView(title)
            val d=parent.resources.displayMetrics.density
            val distribution=LinearLayout(parent.context).apply{orientation=LinearLayout.VERTICAL;layoutParams=LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1.5f)}
            // 五个固定评分条是结构，不是对服务器评价逐条创建View。
            for(stars in 5 downTo 1) {
                val line=LinearLayout(parent.context).apply{gravity=Gravity.CENTER_VERTICAL}
                line.addView(TextView(parent.context).apply{text="$stars 星";textSize=11f;layoutParams=LinearLayout.LayoutParams((32*d).toInt(),(24*d).toInt())})
                val bar=ProgressBar(parent.context,null,android.R.attr.progressBarStyleHorizontal).apply{progressTintList=android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context,R.color.group_buy_orange));layoutParams=LinearLayout.LayoutParams(0,(8*d).toInt(),1f)}
                val count=TextView(parent.context).apply{textSize=11f;gravity=Gravity.END;layoutParams=LinearLayout.LayoutParams((30*d).toInt(),(24*d).toInt())}
                bars.add(bar);counts.add(count);line.addView(bar);line.addView(count);distribution.addView(line)
            }
            panel.addView(distribution)
        }
        /** 只格式化当前摘要，所有进度及数量每次覆盖，不能残留上一门店的评价分布。 */
        fun bind(store:CatalogStore){title.text="${store.rating ?: "暂无评分"}\n${store.reviewCount}条评价";bars.forEachIndexed{i,bar->val count=store.reviewDistribution.getOrElse(4-i){0};bar.max=store.reviewCount.coerceAtLeast(1);bar.progress=count;counts[i].text=count.toString()}}
    }
    companion object {
        /** 行骨架和完整公开键一致才可复用；内容差量不执行请求或改变服务端排序。 */
        val DIFF=object:DiffUtil.ItemCallback<StoreDetailRow>() {
            override fun areItemsTheSame(oldItem:StoreDetailRow,newItem:StoreDetailRow)=oldItem.type==newItem.type && oldItem.key==newItem.key
            override fun areContentsTheSame(oldItem:StoreDetailRow,newItem:StoreDetailRow)=oldItem==newItem
        }
    }
}
