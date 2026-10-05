// 搜索浏览模块：按 UI-02 执行 Spring 关键词/分类/排序查询，依赖同一目录仓库与真实状态。
package com.fandazi.app.feature.search

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentSearchBinding
import com.fandazi.app.feature.home.CatalogControls
import com.fandazi.app.feature.home.CatalogViewModel
import com.fandazi.app.feature.store.CatalogQuery
import com.fandazi.app.feature.store.CatalogRenderer
import kotlinx.coroutines.launch
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.fandazi.app.feature.store.*

/** 搜索与筛选分别表达；返回及视图重建保留 ViewModel 查询，没有演示名单或本地补排。 */
class SearchFragment : Fragment(R.layout.fragment_search) {
    private val repository get() = (requireActivity().application as FandaziApplication).catalogRepository
    private val model: CatalogViewModel by viewModels { CatalogViewModel.Factory(repository) }
    private val controls = CatalogControls(this, { repository }, { model.state.value.query }, { model.load(it) })

    /** 输入提交才查询，避免每个字都发请求；分类/排序操作只修改各自参数。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentSearchBinding.bind(view)
        // Adapter只存数据与当前导航闭包；视图销毁时释放回收池和附着图片。
        val adapter=StoreListAdapter({ store,query ->
            view.findNavController().navigate(R.id.storeFragment,Bundle().apply {
                putString("storePublicId",store.publicId)
                if(query.latitude!=null && query.longitude!=null){putDouble("latitude",query.latitude);putDouble("longitude",query.longitude)}
            })
        },{model.load()})
        binding.searchResults.layoutManager=LinearLayoutManager(requireContext())
        binding.searchResults.adapter=adapter
        binding.searchResults.addOnScrollListener(object:RecyclerView.OnScrollListener(){
            override fun onScrolled(list:RecyclerView,dx:Int,dy:Int) {
                if(adapter.currentList.any{it is StoreListRow.Store})model.rememberAnchor(BusinessListAnchor.capture(list,adapter::rowKey))
            }
        })
        val initial = CatalogQuery(keyword = arguments?.getString("initialQuery").orEmpty(),
            category = arguments?.getString("initialCategory") ?: "推荐", sort = arguments?.getString("initialSort") ?: "RECOMMENDED",
            cityCode = arguments?.getString("cityCode"), latitude = arguments?.takeIf { it.containsKey("latitude") }?.getDouble("latitude"),
            longitude = arguments?.takeIf { it.containsKey("longitude") }?.getDouble("longitude"))
        // 首次初始化接受导航条件，返回时保留既有提交词，不能用初始空词覆盖当前查询显示。
        model.initialize(initial)
        binding.searchInput.setText(model.state.value.query.keyword)
        binding.searchBack.setOnClickListener { view.findNavController().navigateUp() }
        fun submit() {
            model.load(model.state.value.query.copy(keyword = binding.searchInput.text.toString().trim()))
            (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        }
        binding.clearSearch.setOnClickListener { binding.searchInput.text.clear(); submit() }
        binding.searchButton.setOnClickListener { submit() }
        binding.searchInput.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
        val categoryViews = CatalogControls.CATEGORIES.map { category ->
            TextView(requireContext()).apply {
                text = category; textSize = 13f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply { marginEnd = 22.dp }
                setOnClickListener { model.load(model.state.value.query.copy(category = category)) }
                binding.searchCategories.addView(this)
            }
        }
        listOf("人均⌄", "距离⌄", "筛选").forEachIndexed { index, label ->
            binding.filterRow.addView(TextView(requireContext()).apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 44.dp).apply { marginStart = 24.dp }
                if (index == 2) setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.ic_filter, 0)
                setOnClickListener { when (index) { 0 -> controls.average(); 1 -> controls.distance(); else -> controls.filter() } }
            })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    categoryViews.zip(CatalogControls.CATEGORIES).forEach { (item, label) -> CatalogRenderer.selection(item, state.query.category == label) }
                    binding.resultCount.text = if (state.loading || state.error != null) "" else "找到相关门店 ${state.stores.size} 个"
                    binding.searchStatus.visibility = View.GONE
                    (binding.filterRow.getChildAt(0) as TextView).text = when(state.query.sort) { "PRICE_ASC" -> "人均↑"; "PRICE_DESC" -> "人均↓"; else -> "人均⌄" }
                    val anchor=model.anchor
                    adapter.submitState(state) {
                        // 差量提交后按公开键恢复；加载/错误不把状态行位置覆盖成功列表锚点。
                        if(!state.loading && state.error==null && state.stores.isNotEmpty())
                            BusinessListAnchor.restore(binding.searchResults,anchor,adapter.currentList.map{it.key})
                    }
                }
            }
        }
    }

    /** 明确保存结果列表偏移，门店返回时恢复已浏览卡片而不是强制跳回首项。 */
    override fun onDestroyView() {
        view?.findViewById<RecyclerView>(R.id.search_results)?.let { list ->
            (list.adapter as? StoreListAdapter)?.let { adapter ->
                if(adapter.currentList.any{it is StoreListRow.Store})model.rememberAnchor(BusinessListAnchor.capture(list,adapter::rowKey))
                for(i in 0 until list.childCount)adapter.release(list.getChildViewHolder(list.getChildAt(i)))
            }
            list.clearOnScrollListeners();list.adapter=null;list.recycledViewPool.clear()
        }
        super.onDestroyView()
    }

    /** 原生固定布局采用 dp 密度，不依赖窗口宽度改变文字大小。 */
    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()
}
