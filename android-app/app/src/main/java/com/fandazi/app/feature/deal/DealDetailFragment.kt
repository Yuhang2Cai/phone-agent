package com.fandazi.app.feature.deal

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.fandazi.app.R

/** 固定团购套餐详情页，不复用旧外卖购物车流程。 */
class DealDetailFragment : Fragment(R.layout.fragment_deal_detail) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 公开套餐 ID 由导航参数传入，价格和库存必须由后端重新查询。
        view.findViewById<TextView>(R.id.dealPublicId).text = arguments?.getString("dealPublicId").orEmpty()
    }
}
