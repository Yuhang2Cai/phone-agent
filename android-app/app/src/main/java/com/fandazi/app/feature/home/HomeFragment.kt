// 团购首页模块：按已确认 UI-01 绑定真实 Spring 目录，依赖目录 ViewModel 与共享浏览控件。
package com.fandazi.app.feature.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentHomeBinding
import com.fandazi.app.feature.store.CatalogQuery
import com.fandazi.app.feature.store.CatalogRenderer
import kotlinx.coroutines.launch

/** 游客可浏览；分类、排序及城市改变独立查询条件，所有卡片事实只来自服务器。 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private val repository get() = (requireActivity().application as FandaziApplication).catalogRepository
    private val model: CatalogViewModel by viewModels { CatalogViewModel.Factory(repository) }
    // 权限启动器必须在 Fragment 创建前注册，不能等用户点击后才注册。
    private val controls = CatalogControls(this, { repository }, { model.state.value.query }, { model.load(it) })

    /** 绑定既有 UI 入口并观察生命周期内状态，页面离开后不保留旧视图的网络回调。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = FragmentHomeBinding.bind(view)
        val categories = listOf(binding.categoryRecommend, binding.categoryDrink, binding.categoryFastFood,
            binding.categoryHotpot, binding.categoryBarbecue, binding.categoryLocal)
        categories.zip(CatalogControls.CATEGORIES).forEach { (item, category) ->
            item.setOnClickListener { model.load(model.state.value.query.copy(category = category)) }
        }
        binding.sortAverage.setOnClickListener { controls.average() }
        binding.sortDistance.setOnClickListener { controls.distance() }
        binding.openFilter.setOnClickListener { controls.filter() }
        binding.homeLocation.setOnClickListener { controls.cities() }
        binding.homeSearchButton.setOnClickListener {
            val query = model.state.value.query
            view.findNavController().navigate(R.id.searchFragment, Bundle().apply {
                // 搜索继承明确条件，排序、城市和分类不混入用户输入框。
                putString("initialCategory", query.category); putString("initialSort", query.sort)
                putString("cityCode", query.cityCode)
                if (query.latitude != null && query.longitude != null) {
                    putDouble("latitude", query.latitude); putDouble("longitude", query.longitude)
                }
            })
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    categories.zip(CatalogControls.CATEGORIES).forEach { (item, label) -> CatalogRenderer.selection(item, label == state.query.category) }
                    binding.homeCity.text = state.query.cityCode ?: "选择城市"
                    binding.sortAverage.text = when (state.query.sort) { "PRICE_ASC" -> "人均↑"; "PRICE_DESC" -> "人均↓"; else -> "人均⌄" }
                    when {
                        state.loading -> CatalogRenderer.status(binding.homeStoreList, "正在加载门店")
                        state.error != null -> CatalogRenderer.status(binding.homeStoreList, state.error) { model.load() }
                        state.stores.isEmpty() -> CatalogRenderer.status(binding.homeStoreList, "没有符合条件的门店")
                        else -> {
                            CatalogRenderer.stores(binding.homeStoreList, state.stores, state.query) { model.load() }
                            binding.homeScroll.post { binding.homeScroll.scrollTo(0, model.scrollY) }
                        }
                    }
                }
            }
        }
        model.initialize(CatalogQuery())
    }

    /** 详情/搜索离开前记录真实偏移，返回时由相同 ViewModel 恢复，不重新生成业务结果。 */
    override fun onDestroyView() {
        view?.findViewById<android.widget.ScrollView>(R.id.home_scroll)?.let { model.rememberScroll(it.scrollY) }
        super.onDestroyView()
    }
}
