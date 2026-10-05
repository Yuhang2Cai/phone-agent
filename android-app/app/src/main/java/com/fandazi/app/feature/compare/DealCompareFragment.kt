// UI16C–H正式对比页：真实公开事实分列，三列可横滑；只允许导航，不具备报价/订单写入依赖。
package com.fandazi.app.feature.compare
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.*
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import com.fandazi.app.*
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.launch
import com.fandazi.app.core.cache.*

/** 独立列错误可重试，未知人数不推算；返回/重建重新读取实际价格与资格。 */
class DealCompareFragment:Fragment(R.layout.fragment_deal_compare) {
    private val app get()=requireActivity().application as FandaziApplication
    private val selection:CompareSelectionViewModel by activityViewModels()
    private val model:DealCompareViewModel by viewModels{DealCompareViewModel.Factory(HttpDealCompareRepository(app.groupBuyingRepository,app.catalogRepository))}
    private val cards=linkedMapOf<String,View>();private val rendered=mutableMapOf<String,CompareColumn>()
    /** 绑定明确操作，未得到全部事实时问AI关闭；购买仅对最新可售资源显示选择。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        view.compareInsets();view.findViewById<View>(R.id.compare_back).setOnClickListener{findNavController().navigateUp()}
        view.findViewById<View>(R.id.compare_return).setOnClickListener{findNavController().navigateUp()}
        view.findViewById<View>(R.id.compare_retry_all).setOnClickListener{model.state.value.ids.forEach(model::retry)}
        view.findViewById<View>(R.id.compare_ask).setOnClickListener{
            if(model.state.value.columns.all{!it.loading && it.error==null && it.facts?.metadata?.canTransact==true}) {
                // 问AI进入可编辑页，只带公开选择，无自动发送或价格授权。
                val main=requireActivity() as MainActivity
                if(main.applicationSession().state.value !is com.fandazi.app.feature.auth.SessionState.Authenticated)main.openProtectedDestination(R.id.compareQuestionFragment)
                else findNavController().navigate(R.id.compareQuestionFragment)
            }
        }
        view.findViewById<View>(R.id.compare_buy).setOnClickListener{if(model.state.value.columns.any{it.canPurchase})findNavController().navigate(R.id.comparePurchaseFragment)}
        CatalogRecoveryRefresh.attach(this,{model.state.value.columns.any{it.facts?.metadata?.let(CatalogRecoveryRefresh::needed)==true || it.error!=null}},{model.state.value.ids.forEach(model::retry)})
        viewLifecycleOwner.lifecycleScope.launch{viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{render(view,it)}}}
    }
    /** 每次真正回到页面重读权威事实，薄选择保存顺序，原列读不能赋予交易授权。 */
    override fun onResume(){super.onResume();model.initialize(selection.selection.value.ids)}
    /** 全局加载/全错与逐列成功分开，不按响应到达顺序移动列。 */
    private fun render(view:View,state:DealCompareState) {
        val snapshot=state.columns.firstOrNull{it.facts!=null && !it.facts.metadata.canTransact}?.facts?.metadata
        snapshot?.let{CatalogNetworkAccess.record(it)}
        CatalogImageMetadataRefresh.refreshVisible(view)
        val allLoading=state.columns.isNotEmpty() && state.columns.all{it.loading && it.facts==null}
        val allError=state.columns.isNotEmpty() && state.columns.all{!it.loading && it.error!=null}
        val validCount=state.ids.size in 2..3
        view.findViewById<TextView>(R.id.compare_hint).text=when{snapshot!=null->"离线快照 · 更新于${CatalogCacheBanner.time(snapshot)}";!validCount->"至少选择2个不同套餐";allLoading->"正在读取所选套餐最新信息…";allError->"暂时无法读取套餐信息";state.columns.any{it.facts!=null && !it.canPurchase && !it.loading && it.error==null}->"一项套餐已失效，暂不可购买。";state.ids.size==3->"3项 · 左右滑动查看第三项。";state.columns.any{it.facts?.peopleCount==null && it.facts!=null}->"未提供的字段不参与人均计算。";else->"2项 · 人均按分计算并四舍五入至2位。"}
        view.findViewById<View>(R.id.compare_error).isVisible=allError
        // UI16G/H是已确认的独立状态：加载只有不可操作提示，全错只提供重试/返回，不能残留购买动作。
        view.findViewById<View>(R.id.compare_actions).isVisible=!allLoading && !allError
        view.findViewById<View>(R.id.compare_footer_note).isVisible=!allLoading && !allError
        view.findViewById<TextView>(R.id.compare_error_message).text="网络请求失败。已选的${state.ids.size}个套餐仍保留，重试只重新读取事实。"
        view.findViewById<View>(R.id.compare_horizontal).isVisible=validCount && !allLoading && !allError
        val placeholders=view.findViewById<LinearLayout>(R.id.compare_loading);placeholders.isVisible=allLoading
        if(allLoading && placeholders.childCount!=state.ids.size*2+1) {
            placeholders.removeAllViews();state.ids.forEachIndexed{index,_->
                val loading=TextView(requireContext()).apply{text="读取套餐 ${index+1}";textSize=12f;setTextColor(androidx.core.content.ContextCompat.getColor(context,R.color.group_buy_muted));setPadding(0,8.dp,0,8.dp)}
                placeholders.addView(loading);placeholders.addView(View(requireContext()).apply{setBackgroundResource(R.drawable.compare_column);layoutParams=LinearLayout.LayoutParams(-1,96.dp).apply{bottomMargin=12.dp}})
            }
            placeholders.addView(layoutInflater.inflate(R.layout.item_compare_loading_footer,placeholders,false))
        }
        val container=view.findViewById<LinearLayout>(R.id.compare_columns)
        // 宽度随实际设备计算：两列均分，三列露出第三列提示横滑，不截断动态长文本。
        container.post{if(this.view===view){val width=(view.width-view.paddingLeft-view.paddingRight);val columnWidth=if(state.ids.size==3)((width-16.dp)*0.455f).toInt()else (width-12.dp)/2
            cards.values.forEach{it.layoutParams=(it.layoutParams as LinearLayout.LayoutParams).apply{this.width=columnWidth; marginEnd=if(state.ids.size==3)8.dp else 12.dp}}
        }}
        if(cards.keys.toList()!=state.ids) {
            cards.values.forEach{CatalogImageMetadataRefresh.release(it.findViewById(R.id.compare_photo))};container.removeAllViews();cards.clear();rendered.clear()
            state.ids.forEach{id->val card=layoutInflater.inflate(R.layout.item_compare_column,container,false);card.contentDescription="compare-column-$id";cards[id]=card;container.addView(card)}
        }
        state.columns.forEach{column->if(rendered[column.id]!=column){cards[column.id]?.let{bindColumn(it,column)};rendered[column.id]=column}}
        view.findViewById<TextView>(R.id.compare_ask).compareEnabled(validCount && state.columns.size==state.ids.size && state.columns.all{!it.loading && it.error==null && it.facts?.metadata?.canTransact==true},true)
        view.findViewById<TextView>(R.id.compare_buy).compareEnabled(state.columns.any{it.canPurchase})
        view.findViewById<TextView>(R.id.compare_ask).text=if(snapshot!=null)"问AI需联网"else "问AI"
        view.findViewById<TextView>(R.id.compare_buy).text=if(snapshot!=null)"联网后购买"else "查看购买"
        view.findViewById<TextView>(R.id.compare_footer_note).text=if(snapshot!=null)"联网后重新核对套餐信息与报价。"else "选择套餐后，到详情核对最新报价。"
    }
    /** 每列全字段覆盖；失效快照可读但购买关闭，局部错误不串另一套餐照片或金额。 */
    private fun bindColumn(view:View,column:CompareColumn) {
        fun text(id:Int,value:String){view.findViewById<TextView>(id).text=value}
        val facts=column.facts;val deal=facts?.deal
        text(R.id.compare_title,deal?.title ?: if(column.loading)"正在读取套餐"else "套餐读取失败")
        text(R.id.compare_store,facts?.storeName.orEmpty());text(R.id.compare_price,deal?.let{compareMoney(it.priceCents)}.orEmpty())
        text(R.id.compare_original,deal?.let{compareMoney(it.originalPriceCents)} ?: "未提供")
        text(R.id.compare_people,facts?.peopleCount?.let{"${it}人 · ${facts.personPriceText()}"} ?: "未提供")
        text(R.id.compare_contents,deal?.contents?.takeIf{it.isNotEmpty()}?.joinToString("，"){"${it.name}×${it.quantity}"} ?: "未提供")
        text(R.id.compare_validity,deal?.let{"${it.validFrom}—${it.validTo}"} ?: "未提供")
        text(R.id.compare_applicable,facts?.storeName ?: "未提供");text(R.id.compare_rules,deal?.usageRules?.takeIf{it.isNotBlank()} ?: "未提供")
        text(R.id.compare_column_status,when{facts!=null && !facts.metadata.canTransact->"离线快照 · 仅供浏览";column.loading->"正在读取最新信息…";column.error!=null->column.error;column.canPurchase->"可查看购买";else->"已失效 · 购买不可用"})
        view.findViewById<View>(R.id.compare_column_retry).apply{isVisible=column.error!=null;setOnClickListener{model.retry(column.id)}}
        // 图片局部刷新重新核对公开ID与门店，只接受图片字段；失效/缺失保持缺图入口，不借其他列照片。
        CatalogImageMetadataRefresh.bind(view.findViewById(R.id.compare_photo),deal?.imageUrl,deal?.imageAlt){
            if(deal==null)null else when(val result=app.groupBuyingRepository.loadDeal(deal.publicId)) {
                is ApiResult.Success->result.value.takeIf{it.publicId==column.id && it.storePublicId==deal.storePublicId}?.let{it.imageUrl to it.imageAlt}
                is ApiResult.Failure->null
            }
        }
    }
    /** 释放只读图片/任务，返回必重新拉取；选择及水平滚动由公开薄状态/视图树恢复。 */
    override fun onDestroyView(){model.cancelReads();cards.values.forEach{CatalogImageMetadataRefresh.release(it.findViewById(R.id.compare_photo))};cards.clear();rendered.clear();super.onDestroyView()}
    private val Int.dp get()=(this*resources.displayMetrics.density).toInt()
}
