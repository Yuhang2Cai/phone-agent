// 团购订单UI10：真实订单有界回收列表，四分类及交易返回实时刷新，认证失效先隐藏私人事实。
package com.fandazi.app.feature.order

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.*
import androidx.navigation.findNavController
import androidx.recyclerview.widget.*
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.feature.store.*
import com.fandazi.app.feature.voucher.*
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.launch

/** 页面只消费当前认证版本的订单，空态/网络/普通错误沿用已确认原界面。 */
class OrdersFragment:Fragment(R.layout.fragment_orders) {
    private val application get()=requireActivity().application as FandaziApplication
    private val model:OrdersViewModel by viewModels{OrdersViewModel.Factory(application.voucherRepository,application.sessionRepository.state)}
    /** 只装配一次当前视图；onResume统一首次及交易/售后返回读取，不引入第二次首屏读取。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        val list=view.findViewById<RecyclerView>(R.id.order_list)
        val adapter=GroupOrderListAdapter{id->
            if(model.currentOwnerMatches())view.findNavController().navigate(R.id.groupOrderDetailFragment,Bundle().apply{putString("orderId",id)})
        }
        list.layoutManager=LinearLayoutManager(requireContext());list.adapter=adapter
        list.addOnScrollListener(object:RecyclerView.OnScrollListener(){override fun onScrolled(list:RecyclerView,dx:Int,dy:Int){if(adapter.itemCount>0 && !model.state.value.loading)model.rememberAnchor(BusinessListAnchor.capture(list,adapter::rowKey))}})
        val tabs=view.findViewById<TabLayout>(R.id.orders_segments)
        tabs.setTabTextColors(resources.getColor(R.color.group_buy_muted,null),resources.getColor(R.color.group_buy_orange,null))
        tabs.setSelectedTabIndicatorColor(resources.getColor(R.color.group_buy_orange,null))
        listOf("全部","待使用","已完成","退款/售后").forEach{tabs.addTab(tabs.newTab().setText(it))}
        tabs.getTabAt(model.state.value.segment.ordinal)?.select()
        tabs.addOnTabSelectedListener(object:TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab:TabLayout.Tab){model.select(OrderSegment.entries[tab.position])}
            override fun onTabUnselected(tab:TabLayout.Tab)=Unit
            override fun onTabReselected(tab:TabLayout.Tab)=Unit
        })
        view.findViewById<View>(R.id.orders_empty_browse).setOnClickListener{it.findNavController().navigate(R.id.homeFragment)}
        view.findViewById<View>(R.id.orders_error_retry).setOnClickListener{model.refresh(force=false)}
        view.findViewById<View>(R.id.orders_network_retry).setOnClickListener{model.refresh(force=false)}
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {application.sessionRepository.state.collect {
                    // 身份改变时立即隐藏旧私人列表，不等待后台Diff、网络回调或导航动画完成。
                    if(!model.currentOwnerMatches()){list.isVisible=false;adapter.submitOrders(emptyList());view.findViewById<TextView>(R.id.order_status).text=""}
                }}
                launch {model.state.collect {state->
                    val authorized=model.currentOwnerMatches() && !state.authRequired
                    val rows=if(authorized)state.orders.filter{VoucherPresentation.matches(it,state.segment)} else emptyList()
                    val anchor=model.anchor
                    adapter.submitOrders(rows){if(authorized && !state.loading)BusinessListAnchor.restore(list,anchor,adapter.currentList.map{"order:${it.orderId}"})}
                    list.isVisible=authorized && rows.isNotEmpty()
                    val status=view.findViewById<TextView>(R.id.order_status)
                    status.text=when{state.loading->"正在加载订单…";authorized && state.orders.isNotEmpty() && rows.isEmpty()->"该状态暂无订单";else->""}
                    status.isVisible=status.text.isNotEmpty()
                    val network=state.error is ApiError.Network || state.error is ApiError.Timeout
                    view.findViewById<View>(R.id.orders_content).isVisible=authorized && (state.loading || state.error==null && state.orders.isNotEmpty())
                    view.findViewById<View>(R.id.orders_empty_state).isVisible=authorized && !state.loading && state.error==null && state.orders.isEmpty()
                    view.findViewById<View>(R.id.orders_error_state).isVisible=authorized && state.error!=null && !network
                    view.findViewById<View>(R.id.orders_network_state).isVisible=authorized && network
                    view.findViewById<TextView>(R.id.orders_error_description).text="加载暂时失败，请稍后重试"
                    if(state.authRequired && view.findNavController().currentDestination?.id==R.id.ordersFragment)
                        (requireActivity() as MainActivity).openPurchaseLogin(R.id.ordersFragment,Bundle(),true)
                }}
            }
        }
    }
    /** 回到订单必须读取最新付款、券、评价和售后资格，不能仅沿用保留的列表快照。 */
    override fun onResume(){super.onResume();model.refresh(force=true)}
    /** 退出视图先留薄锚点，再移除附着及池内旧公开订单号闭包；账号退出时锚点不保存。 */
    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.order_list)?.let{list->
            (list.adapter as? GroupOrderListAdapter)?.let{adapter->if(adapter.itemCount>0)model.rememberAnchor(BusinessListAnchor.capture(list,adapter::rowKey));for(i in 0 until list.childCount)adapter.release(list.getChildViewHolder(list.getChildAt(i)))}
            list.clearOnScrollListeners();list.adapter=null;list.recycledViewPool.clear()
        }
        super.onDestroyView()
    }
}
