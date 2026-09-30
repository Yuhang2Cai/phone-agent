// 团购首页：渲染 UI-01 的发现页结构，并将门店卡片导航到新的团购门店详情页。
package com.fandazi.app.feature.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.findNavController
import com.fandazi.app.R
import com.fandazi.app.databinding.FragmentHomeBinding

/**
 * 展示餐饮团购首页。
 *
 * 页面当前使用确定性演示数据保证视觉验收稳定；门店公开 ID 与后端种子数据保持一致。
 * 搜索、分类和排序只改变浏览入口，不在本地猜测业务结果。
 */
class HomeFragment : Fragment(R.layout.fragment_home) {
    private var binding: FragmentHomeBinding? = null

    /** 绑定首页搜索、分类、排序和门店导航。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val viewBinding = FragmentHomeBinding.bind(view)
        binding = viewBinding

        // 搜索框和所有分类都进入搜索结果页，并通过参数保留用户选择。
        viewBinding.homeSearchButton.setOnClickListener { openSearch(view, null) }
        val categoryBindings = listOf(
            viewBinding.categoryRecommend to "推荐",
            viewBinding.categoryDrink to "奶茶咖啡",
            viewBinding.categoryFastFood to "快餐小吃",
            viewBinding.categoryHotpot to "火锅",
            viewBinding.categoryBarbecue to "烧烤烤肉",
            viewBinding.categoryLocal to "地方菜系"
        )
        categoryBindings.forEach { (categoryView, category) ->
            categoryView.setOnClickListener { openSearch(view, category) }
        }

        // 排序和筛选的真实查询由搜索页统一执行；首页只负责明确入口和定位能力提示。
        viewBinding.sortAverage.setOnClickListener { openSearch(view, "人均") }
        viewBinding.sortDistance.setOnClickListener {
            Toast.makeText(requireContext(), "允许定位后可按距离排序", Toast.LENGTH_SHORT).show()
            openSearch(view, "距离")
        }
        viewBinding.openFilter.setOnClickListener { openSearch(view, "筛选") }
        viewBinding.homeLocation.setOnClickListener {
            Toast.makeText(requireContext(), "首版支持城市选择，暂不提供商圈筛选", Toast.LENGTH_SHORT).show()
        }

        renderStores(viewBinding)
    }

    /**
     * 渲染经过审核的演示门店卡片。
     * 每张卡片只展示到店团购字段，禁止出现配送费、骑手、收货地址和起送价。
     */
    private fun renderStores(viewBinding: FragmentHomeBinding) {
        val stores = listOf(
            StorePreview("store-001", "蜀香老火锅", "4.8", "2867", "1.2km", "经典双人牛肉套餐", "168", "298", "5.6折", R.drawable.home_merchant_1, R.drawable.home_merchant_2),
            StorePreview("store-002", "北城烧烤", "4.6", "1890", "2.4km", "招牌烤串双人餐", "128", "228", "5.6折", R.drawable.home_merchant_2, R.drawable.home_merchant_3),
            StorePreview("store-003", "一风堂拉面", "4.7", "3205", "3.1km", "经典豚骨拉面双人餐", "98", "176", "5.6折", R.drawable.home_merchant_3, R.drawable.home_merchant_1),
            StorePreview("store-004", "M Stand", "4.5", "932", "1.8km", "拿铁+蛋糕双人餐", "58", "98", "5.9折", R.drawable.home_merchant_1, R.drawable.home_merchant_2)
        )
        val inflater = LayoutInflater.from(requireContext())
        viewBinding.homeStoreList.removeAllViews()
        stores.forEach { store ->
            val card = inflater.inflate(R.layout.item_group_buy_store_card, viewBinding.homeStoreList, false)
            card.findViewById<ImageView>(R.id.store_image).setImageResource(store.image)
            card.findViewById<ImageView>(R.id.deal_image).setImageResource(store.dealImage)
            card.findViewById<TextView>(R.id.store_name).text = store.name
            card.findViewById<TextView>(R.id.store_rating).text = "★ ${store.rating}"
            card.findViewById<TextView>(R.id.store_reviews).text = "${store.reviewCount}条评价"
            card.findViewById<TextView>(R.id.store_distance).text = store.distance
            card.findViewById<TextView>(R.id.deal_name).text = store.dealName
            card.findViewById<TextView>(R.id.deal_price).text = "¥${store.price}"
            card.findViewById<TextView>(R.id.deal_original_price).text = "¥${store.originalPrice}"
            card.findViewById<TextView>(R.id.deal_discount).text = store.discount
            card.contentDescription = "${store.name}，评分${store.rating}，${store.dealName}"
            card.setOnClickListener {
                val arguments = Bundle().apply { putString("storePublicId", store.publicId) }
                requireView().findNavController().navigate(R.id.storeFragment, arguments)
            }
            viewBinding.homeStoreList.addView(card)
        }
    }

    /** 将可选查询词传入搜索结果页，空值表示由用户自行输入。 */
    private fun openSearch(view: View, query: String?) {
        val arguments = Bundle().apply { query?.let { putString("initialQuery", it) } }
        view.findNavController().navigate(R.id.searchFragment, arguments)
    }

    /** 页面销毁后释放 ViewBinding，避免持有失效视图。 */
    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    /** 首页门店卡片所需的稳定展示字段。 */
    private data class StorePreview(
        val publicId: String,
        val name: String,
        val rating: String,
        val reviewCount: String,
        val distance: String,
        val dealName: String,
        val price: String,
        val originalPrice: String,
        val discount: String,
        val image: Int,
        val dealImage: Int
    )
}
