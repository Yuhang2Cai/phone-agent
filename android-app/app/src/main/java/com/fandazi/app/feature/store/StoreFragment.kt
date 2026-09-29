package com.fandazi.app.feature.store

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fandazi.app.R

/**
 * 门店团购详情页的最小壳层。
 * 后续切片会将它接入统一 API 客户端；本阶段先确保导航参数和团购语义独立于旧菜品页。
 */
class StoreFragment : Fragment(R.layout.fragment_store) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 页面只展示门店入口提示，不创建外卖购物车或菜品加购控件。
        view.findViewById<TextView>(R.id.storePublicId).text = arguments?.getString("storePublicId").orEmpty()
    }
}
