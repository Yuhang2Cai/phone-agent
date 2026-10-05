// 对比选择/购买卡片列表：单骨架ListAdapter使用公开UUID及不可变事实，图片与回调在复用时完全覆盖。
package com.fandazi.app.feature.compare
import android.view.*
import android.widget.*
import androidx.recyclerview.widget.*
import androidx.recyclerview.widget.ListAdapter
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.deal.GroupBuyingRepository
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
/** 已校验只读事实与选择状态，操作文案属于UI，金额/名称只能来自接口。 */
data class CompareChoice(val facts:CompareDealFacts,val selected:Boolean=false,val available:Boolean=true)
/** 列表点击详情与选择按钮互相独立；购买模式只传ID，不能从此处创建订单。 */
class CompareChoiceAdapter(private val buying:GroupBuyingRepository,private val purchase:Boolean=false,private val toggle:(CompareChoice)->Unit,private val detail:(CompareChoice)->Unit):ListAdapter<CompareChoice,CompareChoiceAdapter.Holder>(DIFF) {
    /** Holder只保留骨架；每次绑定重新安装本资源动作。 */
    class Holder(view:View):RecyclerView.ViewHolder(view)
    /** 按已确认104dp图片/128dp卡片创建布局，禁止业务行无限ScrollView展开。 */
    override fun onCreateViewHolder(parent:ViewGroup,type:Int)=Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_compare_selection,parent,false))
    /** 全量覆盖真实文字/图/选择/动作，缺图重试由统一加载器管理。 */
    override fun onBindViewHolder(holder:Holder,position:Int) {
        val row=getItem(position);val v=holder.itemView;val deal=row.facts.deal
        v.findViewById<TextView>(R.id.compare_item_title).text=deal.title
        v.findViewById<TextView>(R.id.compare_item_store).text=row.facts.storeName
        v.findViewById<TextView>(R.id.compare_item_price).text=compareMoney(deal.priceCents)
        // 点击缺图只重读当前公开套餐的图片元数据；身份及归属一致才接受，不更新价格/选择或发起交易。
        CatalogImageMetadataRefresh.bind(v.findViewById(R.id.compare_item_image),deal.imageUrl,deal.imageAlt){
            when(val result=buying.loadDeal(deal.publicId)) {
                is ApiResult.Success->result.value.takeIf{it.publicId==deal.publicId && it.storePublicId==deal.storePublicId}?.let{it.imageUrl to it.imageAlt}
                is ApiResult.Failure->null
            }
        }
        v.findViewById<View>(R.id.compare_item_card).apply{contentDescription="compare-card-${deal.publicId}";setOnClickListener{detail(row)}}
        v.findViewById<TextView>(R.id.compare_item_toggle).apply {
            text=if(purchase)if(row.available)"去套餐详情"else "已失效 · 购买不可用" else if(row.selected)"已加入 · 移除"else "加入对比"
            contentDescription="compare-toggle-${deal.publicId}";compareEnabled(!purchase || row.available,row.selected)
            setOnClickListener{if(isEnabled)toggle(row)}
        }
    }
    /** 清图片和监听，池中的旧套餐不保留任何点击目标。 */
    override fun onViewRecycled(holder:Holder){val v=holder.itemView;CatalogImageMetadataRefresh.release(v.findViewById(R.id.compare_item_image));v.findViewById<View>(R.id.compare_item_card).setOnClickListener(null);v.findViewById<View>(R.id.compare_item_toggle).setOnClickListener(null);super.onViewRecycled(holder)}
    companion object {
        /** 同UUID复用，快照事实或选择有变化才触发重新绑定。 */
        val DIFF=object:DiffUtil.ItemCallback<CompareChoice>() {
            override fun areItemsTheSame(a:CompareChoice,b:CompareChoice)=a.facts.deal.publicId==b.facts.deal.publicId
            override fun areContentsTheSame(a:CompareChoice,b:CompareChoice)=a==b
        }
    }
}
