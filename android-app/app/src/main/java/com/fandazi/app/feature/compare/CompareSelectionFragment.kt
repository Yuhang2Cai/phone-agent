// UI16A/B正式选择页：单RV与固定开始按钮，依赖后端公开目录与Activity公开选择状态。
package com.fandazi.app.feature.compare
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.*
import androidx.lifecycle.*
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.*
import com.fandazi.app.*
import kotlinx.coroutines.launch
/** 普通公开目录游客可读，选择不会获得交易资格或覆盖聊天草稿。 */
class CompareSelectionFragment:Fragment(R.layout.fragment_compare_selection) {
    private val app get()=requireActivity().application as FandaziApplication
    private val selected:CompareSelectionViewModel by activityViewModels()
    private val model:CompareCatalogViewModel by viewModels{CompareCatalogViewModel.Factory(app.catalogRepository,app.groupBuyingRepository)}
    /** 收集目录和选择的组合快照，第四项拒绝后保留原顺序并显示明确原因。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        view.compareInsets();view.findViewById<View>(R.id.compare_back).setOnClickListener{findNavController().navigateUp()}
        val list=view.findViewById<RecyclerView>(R.id.compare_selection_list)
        val adapter=CompareChoiceAdapter(buying=app.groupBuyingRepository,toggle={row->
            if(row.selected)selected.remove(row.facts.deal.publicId)
            else if(!selected.add(row.facts.deal.publicId))view.findViewById<TextView>(R.id.compare_selection_status).text="最多对比3个不同套餐，第四项保持未选；请先移除一项。"
        },detail={row->findNavController().navigate(R.id.dealDetailFragment,Bundle().apply{putString("dealPublicId",row.facts.deal.publicId);putString("storePublicId",row.facts.deal.storePublicId)})})
        list.layoutManager=LinearLayoutManager(requireContext());list.adapter=adapter;list.itemAnimator=null
        view.findViewById<View>(R.id.compare_selection_retry).setOnClickListener{model.load()}
        view.findViewById<View>(R.id.compare_start).setOnClickListener{if(selected.selection.value.canCompare)findNavController().navigate(R.id.dealCompareFragment)}
        viewLifecycleOwner.lifecycleScope.launch{viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED){
            kotlinx.coroutines.flow.combine(model.state,selected.selection){state,selection->state to selection}.collect{(state,selection)->
                view.findViewById<TextView>(R.id.compare_selection_status).text=when{state.loading->"正在读取套餐目录…";state.error->"部分套餐读取失败，目录不完整；已选${selection.ids.size}项";state.rows.isEmpty()->"暂无可选套餐";else->"已选${selection.ids.size}项；至少选择2项才可对比。"}
                view.findViewById<View>(R.id.compare_selection_retry).isVisible=state.error
                view.findViewById<TextView>(R.id.compare_start).apply{text="已选${selection.ids.size}项 · 开始对比";compareEnabled(selection.canCompare)}
                adapter.submitList(state.rows.map{CompareChoice(it,it.deal.publicId in selection.ids)})
            }
        }}
    }
    /** 返回页面读取新目录但不丢选择/锚点，失效选择仍允许进入对比查看明确失败。 */
    override fun onResume(){super.onResume();model.load()}
    /** 清视图引用和在途任务，持久恢复仅含公开选择ID。 */
    override fun onDestroyView(){model.cancel();view?.findViewById<RecyclerView>(R.id.compare_selection_list)?.adapter=null;super.onDestroyView()}
}
