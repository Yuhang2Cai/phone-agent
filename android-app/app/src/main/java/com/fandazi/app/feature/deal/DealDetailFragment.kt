// 套餐详情模块：UI06在原区域订阅分阶段可信详情/选项，依赖统一只读状态模型和真实图片加载器。
package com.fandazi.app.feature.deal

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.viewModels
import androidx.navigation.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.core.image.CatalogImageMetadataRefresh
import kotlinx.coroutines.launch
import com.fandazi.app.core.cache.*

/**
 * 展示后端返回的团购套餐详情。
 *
 * 页面通过公开套餐 ID 查询详情，并用同一门店的真实套餐列表生成横向切换项；因此标题、金额、
 * 规则和明细都跟随用户点击的套餐变化，不依赖固定下标或默认套餐文案。
 */
class DealDetailFragment : Fragment(R.layout.fragment_deal_detail) {
    private val application get()=requireActivity().application as FandaziApplication
    private val model:DealDetailViewModel by viewModels { DealDetailViewModel.Factory(application.groupBuyingRepository) }
    private val selectedDeal:DealPackage? get()=model.state.value.detail
    private var renderedDetail:DealPackage?=null
    private var renderedOptions:List<DealPackage>?=null
    private var renderedSelectedId:String?=null
    private var renderedOptionsStatus:String?=null

    /** 绑定当前套餐的返回、门店与购买入口；加载完成前购买禁用，防止旧金额或旧 ID 被使用。 */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 套餐详情页只保留返回操作，不重新引入已从设计稿移除的顶部功能按钮。
        view.findViewById<View>(R.id.deal_back).setOnClickListener {
            view.findNavController().navigateUp()
        }
        view.findViewById<View>(R.id.deal_store).setOnClickListener {
            selectedDeal?.let { deal -> view.findNavController().navigate(R.id.storeFragment, Bundle().apply { putString("storePublicId", deal.storePublicId) }) }
        }
        // 当前详情仅贡献公开ID，用户可返回跨店继续选择；第四项拒绝后原选择不变。
        view.findViewById<View>(R.id.deal_compare_add).setOnClickListener {
            val deal=selectedDeal ?: return@setOnClickListener
            val selection=androidx.lifecycle.ViewModelProvider(requireActivity())[com.fandazi.app.feature.compare.CompareSelectionViewModel::class.java]
            if(selection.add(deal.publicId))view.findNavController().navigate(R.id.compareSelectionFragment)
            else android.widget.Toast.makeText(requireContext(),"最多对比3项，请先移除一项",android.widget.Toast.LENGTH_SHORT).show()
        }
        // 购买栏作为一个整体接收点击，避免底部栏中的展示型 TextView 抢占触摸事件导致购买无响应。
        view.findViewById<View>(R.id.deal_purchase_bar).setOnClickListener {
            val currentDealId = selectedDeal?.publicId.orEmpty()
            // 点击时再次核对当前模型门槛，避免视图上一帧仍可点击或旧监听拿到另一套餐。
            if (currentDealId.isBlank() || !model.state.value.purchaseReady) return@setOnClickListener

            // 团购报价和下单是受保护接口；游客只能浏览目录，点击购买时必须先完成登录。
            val mainActivity = requireActivity() as MainActivity
            if (mainActivity.applicationSession().state.value !is SessionState.Authenticated) {
                // 游客登录后重建原套餐并打开购买弹窗，保留真实资源和当前数量。
                mainActivity.openPurchaseLogin(R.id.dealDetailFragment, Bundle().apply {
                    putString("dealPublicId", currentDealId); putString("storePublicId", selectedDeal?.storePublicId)
                    putBoolean("resumePurchase", true); putInt("quantity", 1)
                })
                return@setOnClickListener
            }

            // 公开套餐 ID 来自当前选中对象，确认页继续通过该 ID 请求后端，不使用首次打开的旧 ID。
            view.findNavController().navigate(R.id.dealCheckoutBottomSheetFragment, Bundle().apply {
                putString("dealPublicId", currentDealId)
                putString("storePublicId", selectedDeal?.storePublicId)
                putInt("quantity", 1)
            })
        }
        // 按可见生命周期订阅真实快照；首阶段立即绘详情，选项状态更新不重建无关明细。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state -> renderState(view,state) }
            }
        }
        CatalogRecoveryRefresh.attach(this,{CatalogRecoveryRefresh.needed(model.state.value.metadata) || CatalogRecoveryRefresh.needed(model.state.value.optionsMetadata)},{model.retryDetail()})
        model.initialize(arguments?.getString("dealPublicId").orEmpty(),arguments?.getString("storePublicId").orEmpty())
    }

    /** 当前快照按详情/选项/购买三段绘制；恢复购买只有归属及可售门槛通过后才能消费标记。 */
    private fun renderState(view:View,state:DealDetailLoadState) {
        val source=if(!state.metadata.canTransact)state.metadata else state.optionsMetadata
        CatalogCacheBanner.bind(view,source,detail=true,retry={model.retryDetail()})
        CatalogImageMetadataRefresh.refreshVisible(view)
        // 网络失败撤销登录继续标志；恢复不能自动重放报价或导航，用户需再次明确点购买。
        if(CatalogRecoveryRefresh.needed(source))arguments?.putBoolean("resumePurchase",false)
        if(state.detail==null) {
            showLoadError(view,state.detailError ?: "正在加载套餐",state.detailError!=null)
            renderedDetail=null
        } else if(renderedDetail!=state.detail) {
            renderSelectedDeal(view);renderedDetail=state.detail
        }
        renderDealOptions(view,state)
        view.findViewById<View>(R.id.deal_purchase_bar).isEnabled=state.purchaseReady
        view.findViewById<TextView>(R.id.deal_purchase).apply {
            text=if(!source.canTransact)"联网后购买"else state.detail?.let{"立即购买  ${it.priceText()}"} ?: "立即购买"
            backgroundTintList=android.content.res.ColorStateList.valueOf(ContextCompat.getColor(context,if(state.purchaseReady)R.color.group_buy_orange else R.color.group_buy_muted))
        }
        view.findViewById<View>(R.id.deal_compare_add).isEnabled=state.detail!=null
        // 只读首屏不能自动恢复购买；重新登录后仍需成功加载同店可售列表并重新报价。
        val deal=state.detail
        if(state.purchaseReady && deal!=null && arguments?.getBoolean("resumePurchase")==true &&
            (requireActivity() as MainActivity).applicationSession().state.value is SessionState.Authenticated) {
            arguments?.putBoolean("resumePurchase",false)
            view.findNavController().navigate(R.id.dealCheckoutBottomSheetFragment,Bundle().apply {
                putString("dealPublicId",deal.publicId);putString("storePublicId",deal.storePublicId)
                putInt("quantity",arguments?.getInt("quantity",1) ?: 1)
            })
        }
    }

    /** 将当前接口对象渲染到标题、规则、价格和明细区域。 */
    private fun renderSelectedDeal(view: View) {
        val deal = selectedDeal ?: return
        // 这里只绘可信内容；购买启用由renderState统一读完整归属门槛，不在详情首阶段解锁。
        view.findViewById<TextView>(R.id.deal_title).apply {
            text = deal.title
            // 使用公开 ID作为无障碍和自动化验收标识，确认当前内容确实跟随套餐切换。
            contentDescription = "selected-deal-${deal.publicId}"
            setOnClickListener(null)
        }
        view.findViewById<TextView>(R.id.deal_meta).text = "库存${deal.inventory}份  ·  每人限购${deal.purchaseLimit}份"
        view.findViewById<TextView>(R.id.deal_rule).text = "${deal.validFrom} 至 ${deal.validTo}可用  ·  购买后请按门店规则使用"
        view.findViewById<TextView>(R.id.deal_price).apply {
            text = deal.priceText()
            contentDescription = "deal-price-${deal.priceCents}"
        }
        view.findViewById<TextView>(R.id.deal_purchase).text = "立即购买  ${deal.priceText()}"
        renderDealDetails(view.findViewById(R.id.deal_detail_list), deal)
    }

    /** 根据已校验同店列表生成选项；点击只选择公开ID，再重新查询并校验单详情。 */
    private fun renderDealOptions(view: View,state:DealDetailLoadState) {
        val status=if(state.optionsLoading)"正在加载同店套餐"else state.optionsError
        val selectedId=state.detail?.publicId
        // 列表/选中ID/局部状态均未变化时不重建图片和选项，详情加载时间不被重复渲染放大。
        if(renderedOptions==state.options && renderedSelectedId==selectedId && renderedOptionsStatus==status)return
        renderedOptions=state.options;renderedSelectedId=selectedId;renderedOptionsStatus=status
        val container = view.findViewById<LinearLayout>(R.id.deal_option_list)
        val repository=application.groupBuyingRepository
        container.removeAllViews()
        if(status!=null) {
            container.addView(createOptionText(status,14f,Typeface.NORMAL).apply {
                gravity=Gravity.CENTER_VERTICAL
                layoutParams=LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,78.dp)
                contentDescription=if(state.optionsLoading)"deal-options-loading"else "deal-options-retry"
                // 只读选项失败只重试本门店列表，不能重读已校验标题/价或借默认项解锁购买。
                if(!state.optionsLoading)setOnClickListener { model.retryOptions() }
            })
        }
        state.options.forEach { deal ->
            val item = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(12.dp, 8.dp, 12.dp, 8.dp)
                layoutParams = LinearLayout.LayoutParams(164.dp, 78.dp).apply { marginEnd = 10.dp }
                contentDescription = "deal-option-${deal.publicId}"
                setOnClickListener {
                    // 列表只提供公开选项；切换后重新读取单详情，不能把该列表对象直接当可信详情。
                    arguments?.putString("dealPublicId",deal.publicId)
                    arguments?.putString("storePublicId",deal.storePublicId)
                    model.initialize(deal.publicId,deal.storePublicId)
                }
            }
            item.background = ContextCompat.getDrawable(
                requireContext(),
                if (deal.publicId == selectedDeal?.publicId) R.drawable.deal_option_selected else R.drawable.deal_option_default,
            )
            item.addView(ImageView(requireContext()).apply {
                layoutParams = LinearLayout.LayoutParams(44.dp, 48.dp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                // 缺图仅读取本对象的图片字段，不重绘资格/表单，也不准备报价或确认凭证。
                CatalogImageMetadataRefresh.bind(this, deal.imageUrl, deal.imageAlt, thumbnail=true) {
                    (repository.loadDeal(deal.publicId) as? ApiResult.Success)?.value?.let { it.imageUrl to it.imageAlt }
                }
            })
            item.addView(LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 6.dp }
                addView(createOptionText(deal.title, 12f, Typeface.BOLD).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(createOptionText(deal.priceText(), 14f, Typeface.NORMAL))
            })
            container.addView(item)
        }
    }

    /** 将接口描述、有效期和库存组成可审计的套餐明细，不补写接口不存在的菜品。 */
    private fun renderDealDetails(container: LinearLayout, deal: DealPackage) {
        container.removeAllViews()
        addDetailGroup(container, "套餐内容", listOf("说明" to deal.description))
        addDetailGroup(
            container,
            "使用信息",
            listOf(
                "有效期" to "${deal.validFrom} 至 ${deal.validTo}",
                "剩余库存" to "${deal.inventory}份",
                "购买限制" to "每人限购${deal.purchaseLimit}份",
            ),
        )
    }

    /** 按分类标题和字段值生成详情区域，保证接口数据的结构在 UI 中可追踪。 */
    private fun addDetailGroup(container: LinearLayout, title: String, rows: List<Pair<String, String>>) {
        container.addView(TextView(requireContext()).apply {
            text = title
            textSize = 17f
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_text))
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 14.dp, 0, 6.dp)
        })
        rows.forEach { (label, value) ->
            container.addView(createDetailRow(label, value))
        }
    }

    /** 创建套餐详情的一行标签和值。 */
    private fun createDetailRow(label: String, value: String): LinearLayout =
        LinearLayout(requireContext()).apply {
            // 描述与有效期可能较长，采用上下排列避免实际内容挤出固定宽度。
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 8.dp, 0, 8.dp)
            addView(TextView(context).apply {
                text = "•  $label"
                textSize = 15f
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_text))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(ContextCompat.getColor(context, R.color.group_buy_muted))
            })
        }

    /** 创建横向选项中的文字，统一字号与颜色。 */
    private fun createOptionText(content: String, size: Float, style: Int): TextView =
        TextView(requireContext()).apply {
            text = content
            textSize = size
            setTextColor(ContextCompat.getColor(requireContext(), R.color.group_buy_text))
            setTypeface(typeface, style)
            setPadding(0, 2.dp, 0, 2.dp)
        }

    /** 无可信详情时清空金额；错误点击只重试原ID，加载态不提供重复请求入口。 */
    private fun showLoadError(view: View, message: String,retry:Boolean) {
        // 模型已无可信对象，此处清空视图金额并禁购；错误重试仍使用原完整导航 UUID。
        view.findViewById<View>(R.id.deal_purchase_bar).isEnabled = false
        view.findViewById<TextView>(R.id.deal_price).apply { text="";contentDescription=null }
        view.findViewById<TextView>(R.id.deal_purchase).text = "立即购买"
        view.findViewById<TextView>(R.id.deal_title).apply {
            text=message;contentDescription=if(retry)"deal-detail-retry"else "deal-detail-loading"
            setOnClickListener(if(retry)View.OnClickListener { model.retryDetail() }else null)
        }
        view.findViewById<TextView>(R.id.deal_meta).text = ""
        view.findViewById<TextView>(R.id.deal_rule).text = ""
        view.findViewById<LinearLayout>(R.id.deal_detail_list).removeAllViews()
    }

    /** 页面退出清事实并取消旧读取；图片元数据协程使用已捕获仓库，不访问已销毁Activity。 */
    override fun onDestroyView() {
        model.cancelReads();renderedDetail=null;renderedOptions=null;renderedSelectedId=null;renderedOptionsStatus=null
        super.onDestroyView()
    }

    /** 将设计稿 dp 数值转换为当前设备像素。 */
    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
