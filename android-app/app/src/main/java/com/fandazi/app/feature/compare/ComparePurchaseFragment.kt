// UI16J查看购买入口：仅列当前用户选择的最新公开事实，显式点击才回详情进行新报价。
package com.fandazi.app.feature.compare
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.*
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import com.fandazi.app.*
import kotlinx.coroutines.launch
/** 无购买仓库依赖，禁止用上一页面价格创建订单或默认选第一项。 */
class ComparePurchaseFragment:Fragment(R.layout.fragment_compare_purchase) {
    private val app get()=requireActivity().application as FandaziApplication
    private val selection:CompareSelectionViewModel by activityViewModels()
    private val model:DealCompareViewModel by viewModels{DealCompareViewModel.Factory(HttpDealCompareRepository(app.groupBuyingRepository,app.catalogRepository))}
    /** 为每个选择安装同ID详情导航；失效/未完成读取时操作关闭。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        view.compareInsets();view.findViewById<View>(R.id.compare_back).setOnClickListener{findNavController().navigateUp()};view.findViewById<View>(R.id.compare_purchase_return).setOnClickListener{findNavController().navigateUp()}
        fun open(row:CompareChoice){if(row.available)findNavController().navigate(R.id.dealDetailFragment,Bundle().apply{putString("dealPublicId",row.facts.deal.publicId);putString("storePublicId",row.facts.deal.storePublicId)})}
        val adapter=CompareChoiceAdapter(app.groupBuyingRepository,true,::open,::open)
        view.findViewById<RecyclerView>(R.id.compare_purchase_list).apply{layoutManager=LinearLayoutManager(requireContext());this.adapter=adapter}
        viewLifecycleOwner.lifecycleScope.launch{viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED){model.state.collect{state->
            adapter.submitList(state.columns.mapNotNull{column->column.facts?.let{CompareChoice(it,available=column.canPurchase)}})
            view.findViewById<TextView>(R.id.compare_purchase_notice).text=when{state.columns.any{it.loading}->"正在读取最新价格…";state.columns.any{it.error!=null}->"部分套餐读取失败，请返回对比重试。";else->"当前价格已重新读取；请到详情核对最新报价并确认购买。"}
        }}}
    }
    /** 详情返回必须重读，不能复活旧价或旧可售资格。 */
    override fun onResume(){super.onResume();model.initialize(selection.selection.value.ids)}
    override fun onDestroyView(){model.cancelReads();view?.findViewById<RecyclerView>(R.id.compare_purchase_list)?.adapter=null;super.onDestroyView()}
}
