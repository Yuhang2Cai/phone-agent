package com.fandazi.app.feature.merchant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.image.ImageLoader
import com.fandazi.app.databinding.FragmentDishOptionsBinding
import com.fandazi.app.feature.ordering.Dish
import com.fandazi.app.feature.ordering.DishOptionsPresentation
import com.fandazi.app.feature.ordering.OrderingViewModel
import com.fandazi.app.feature.ordering.OrderingViewModelFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.math.BigDecimal

class DishOptionsBottomSheet : BottomSheetDialogFragment(R.layout.fragment_dish_options) {
    private val viewModel: OrderingViewModel by activityViewModels {
        val activity = requireActivity() as MainActivity
        OrderingViewModelFactory(activity.orderingRepository(), activity.cartStateStore(), activity.cartOwner())
    }
    private var quantity = 1
    private lateinit var binding: FragmentDishOptionsBinding
    private lateinit var dish: Dish

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding = FragmentDishOptionsBinding.bind(view)
        dish = viewModel.uiState.value.menu.firstOrNull { it.id == requireArguments().getLong(ARG_DISH_ID) }
            ?: run { dismiss(); return }
        ImageLoader.load(binding.dishHero, dish.imageUrl, R.drawable.home_merchant_3, R.drawable.home_merchant_3)
        binding.dishName.text = dish.name
        binding.dishDescription.text = dish.description
        binding.dishClose.setOnClickListener { dismiss() }
        DishOptionsPresentation.groups(dish).forEach { group ->
            if (viewModel.uiState.value.selectedOptions[dish.id].orEmpty().none { it.groupName == group.name }) {
                group.options.firstOrNull { it.available }?.let { viewModel.selectOption(dish.id, it.id) }
            }
        }
        renderGroups()
        binding.quantityMinus.setOnClickListener { quantity = (quantity - 1).coerceAtLeast(1); renderTotal() }
        binding.quantityPlus.setOnClickListener { quantity = (quantity + 1).coerceAtMost(100); renderTotal() }
        binding.addToCart.setOnClickListener {
            viewModel.addSelectedDish(quantity)
            dismiss()
        }
        renderTotal()
    }

    private fun renderGroups() {
        binding.optionGroups.removeAllViews()
        DishOptionsPresentation.groups(dish).forEach { group ->
            binding.optionGroups.addView(TextView(requireContext()).apply {
                text = group.name
                textSize = 16f
                setTextColor(requireContext().getColor(R.color.brand_ink))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 18.dp, 0, 10.dp)
            })
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            group.options.forEach { option ->
                val selected = viewModel.uiState.value.selectedOptions[dish.id].orEmpty().any { it.id == option.id }
                row.addView(TextView(requireContext()).apply {
                    text = DishOptionsPresentation.optionLabel(option) + if (option.priceDelta > BigDecimal.ZERO) "  +¥${option.priceDelta}" else ""
                    textSize = 13f
                    gravity = android.view.Gravity.CENTER
                    minHeight = 42.dp
                    setPadding(14.dp, 0, 14.dp, 0)
                    isEnabled = option.available
                    contentDescription = if (option.available) "选择${option.optionName}" else "${option.optionName}已售罄"
                    setTextColor(requireContext().getColor(if (!option.available) R.color.brand_weak else if (selected) R.color.brand_orange else R.color.brand_ink))
                    background = ContextCompat.getDrawable(requireContext(), if (!option.available) R.drawable.dish_option_disabled else if (selected) R.drawable.dish_option_selected else R.drawable.dish_option_unselected)
                    setOnClickListener { viewModel.selectOption(dish.id, option.id); renderGroups(); renderTotal() }
                }, LinearLayout.LayoutParams(0, 42.dp, 1f).apply { marginEnd = 8.dp })
            }
            binding.optionGroups.addView(row)
        }
    }

    private fun renderTotal() {
        binding.quantityValue.text = quantity.toString()
        val optionDelta = viewModel.uiState.value.selectedOptions[dish.id].orEmpty().fold(BigDecimal.ZERO) { total, option -> total + option.priceDelta }
        val total = (dish.price + optionDelta) * quantity.toBigDecimal()
        binding.dishPrice.text = "¥${dish.price} 起"
        binding.addToCart.text = "¥$total  加入购物车"
    }

    private val Int.dp: Int get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val ARG_DISH_ID = "dish_id"
        fun newInstance(dishId: Long) = DishOptionsBottomSheet().apply { arguments = Bundle().apply { putLong(ARG_DISH_ID, dishId) } }
    }
}
