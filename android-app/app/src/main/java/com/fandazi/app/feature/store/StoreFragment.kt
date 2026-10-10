// 门店UI03/04/05入口：单纵向RV承载摘要/页签/业务行，事实留在ViewModel，视图仅在可见生命周期订阅。
package com.fandazi.app.feature.store

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentStoreBinding
import kotlinx.coroutines.launch
import com.fandazi.app.core.cache.*
import com.fandazi.app.core.image.CatalogImageMetadataRefresh

/** 所有点击携带完整公开UUID，页签返回恢复锚点，不增加外卖加购或交易写入。 */
class StoreFragment:Fragment(R.layout.fragment_store) {
    private val application get()=requireActivity().application as FandaziApplication
    private val model:StoreBrowseViewModel by viewModels{StoreBrowseViewModel.Factory(application.catalogRepository,application.groupBuyingRepository)}
    private var resumedBefore=false
    /** 首次进入由initialize并行读取；再次恢复可见性代表业务/页面返回，需要刷新后端事实。 */
    override fun onResume() {
        super.onResume()
        if(resumedBefore)model.refreshAfterBusinessReturn() else resumedBefore=true
    }
    /** 创建有界纵向容器；摘要/页签全宽，内容区原12dp边距由装饰保留。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        val binding=FragmentStoreBinding.bind(view)
        // 列表入口只导航选择页，不预选第一项，也不发送AI或产生交易请求。
        view.findViewById<View>(R.id.store_compare_open).setOnClickListener{view.findNavController().navigate(R.id.compareSelectionFragment)}
        // 输入系统状态栏Insets，固定根容器安全区；Header滚动离屏时仍不能让菜品文字进入系统栏。
        ViewCompat.setOnApplyWindowInsetsListener(view) { root,insets ->
            root.updatePadding(top=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top)
            // 原样返回Insets，不吞掉其他窗口/底栏处理，也不重复给每一行加入安全区。
            insets
        }
        ViewCompat.requestApplyInsets(view)
        val list=binding.storeContent
        val adapter=StoreDetailListAdapter({view.findNavController().navigateUp()},{tab->
            model.rememberAnchor(BusinessListAnchor.capture(list){(list.adapter as? StoreDetailListAdapter)?.rowKey(it)})
            model.select(tab)
        },{model.loadSummary()},{model.select(model.state.value.tab,true)},{deal->
            view.findNavController().navigate(R.id.dealDetailFragment,Bundle().apply{putString("storePublicId",deal.storePublicId);putString("dealPublicId",deal.publicId)})
        })
        list.layoutManager=LinearLayoutManager(requireContext());list.adapter=adapter
        list.addItemDecoration(object:RecyclerView.ItemDecoration(){
            override fun getItemOffsets(outRect:Rect,child:View,parent:RecyclerView,state:RecyclerView.State) {
                val position=parent.getChildAdapterPosition(child);val type=adapter.currentList.getOrNull(position)?.type ?: return
                if(type>=2){val d=resources.displayMetrics.density;outRect.left=(12*d).toInt();outRect.right=(12*d).toInt();if(position==2)outRect.top=(10*d).toInt()}
            }
        })
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(list:RecyclerView,dx:Int,dy:Int){if(!model.state.value.loading)model.rememberAnchor(BusinessListAnchor.capture(list,adapter::rowKey))}
        })
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect {state->
                    val source=if(!state.summaryMetadata.canTransact)state.summaryMetadata else state.metadata
                    // 页签刷新只更新列表内的加载状态；顶部瞬态提示会改变列表高度，造成整页上下跳动。
                    // 保留已有离线提示直到权威结果到达，失败与恢复仍按真实来源更新，不能隐藏离线风险。
                    if(source.status!=CatalogReadStatus.REFRESHING) {
                        CatalogCacheBanner.bind(view,source,retry={model.refreshAfterBusinessReturn()})
                    }
                    CatalogImageMetadataRefresh.refreshVisible(view)
                    val anchor=model.anchor
                    adapter.submitState(state){if(!state.loading)BusinessListAnchor.restore(list,anchor,adapter.currentList.map{it.key})}
                }
            }
        }
        CatalogRecoveryRefresh.attach(this,{CatalogRecoveryRefresh.needed(model.state.value.metadata) || CatalogRecoveryRefresh.needed(model.state.value.summaryMetadata)},{model.refreshAfterBusinessReturn()})
        model.initialize(arguments?.getString("storePublicId").orEmpty(),CatalogQuery(
            latitude=arguments?.takeIf{it.containsKey("latitude")}?.getDouble("latitude"),longitude=arguments?.takeIf{it.containsKey("longitude")}?.getDouble("longitude")))
    }
    /** 离开先记录薄引用，再清理附着图片及回收池，旧视图不接收网络回调。 */
    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.store_content)?.let{list->
            (list.adapter as? StoreDetailListAdapter)?.let{adapter->
                if(!model.state.value.loading)model.rememberAnchor(BusinessListAnchor.capture(list,adapter::rowKey))
                for(i in 0 until list.childCount)adapter.release(list.getChildViewHolder(list.getChildAt(i)))
            }
            list.clearOnScrollListeners();list.adapter=null;list.recycledViewPool.clear()
        }
        // 根视图销毁时解除Insets回调，旧安全区容器不再接收窗口尺寸变化。
        view?.let { ViewCompat.setOnApplyWindowInsetsListener(it,null) }
        super.onDestroyView()
    }
}
