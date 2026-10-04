// AI 卡片渲染器：把网关返回的版本化字段转换为聊天页中的完整业务卡片。
package com.fandazi.app.feature.agent

import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.fandazi.app.R
import com.fandazi.app.core.image.CatalogImageUrls
import com.fandazi.app.core.time.BusinessDisplay
import java.time.LocalDate

/**
 * 负责渲染助手返回的门店、套餐、订单和未知类型卡片。
 *
 * 渲染器不保存业务样例，也不根据本地下标生成门店或套餐；卡片标题、价格、地址和状态均来自后端。
 * 只有字段标签、颜色、布局和点击行为属于客户端静态 UI 规范。
 */
object AgentCardRenderer {
    /** 将卡片列表追加到聊天内容容器，并只为具备公开 ID 的卡片注册受控点击行为。 */
    fun render(
        fragment: Fragment,
        container: LinearLayout,
        cards: List<AgentCardPayload>,
        onNavigate: (AgentNavigationRequest) -> Unit
    ) = render(fragment, container, cards, { false }, onNavigate)

    /** 正式聊天传入当前动作摘要校验；未提供校验的调用不能开放确认、取消或选券。 */
    fun render(
        fragment: Fragment,
        container: LinearLayout,
        cards: List<AgentCardPayload>,
        isCurrentConfirmation: (AgentCardPayload) -> Boolean,
        onNavigate: (AgentNavigationRequest) -> Unit
    ) {
        val inflater = LayoutInflater.from(fragment.requireContext())
        cards.forEach { card ->
            when (card.type.lowercase()) {
                "store" -> renderStore(fragment, inflater, container, card, onNavigate)
                "deal" -> renderDeal(fragment, inflater, container, card, onNavigate)
                "order" -> renderOrder(inflater, container, card, onNavigate)
                "voucher" -> renderVoucher(inflater, container, card, onNavigate)
                "confirmation" -> renderConfirmation(inflater, container, card, isCurrentConfirmation, onNavigate)
                "coupon" -> renderCoupon(inflater, container, card)
                "after_sale_voucher_choice" -> renderVoucherChoice(inflater, container, card, isCurrentConfirmation, onNavigate)
                "after_sale_refund", "after_sale_complaint" -> renderAfterSale(inflater, container, card, isCurrentConfirmation, onNavigate)
                else -> renderUnknown(inflater, container, card)
            }
        }
    }

    /** 本人券查询卡只展示后端事实；独立公开券号绑定详情，区别于 Task8 的选券回复动作。 */
    private fun renderVoucher(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload,
                              onNavigate: (AgentNavigationRequest) -> Unit) {
        val voucherId = card.fields["voucherId"]?.takeIf(String::isNotBlank)
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        view.findViewById<TextView>(R.id.agent_order_title).text = listOfNotNull(card.fields["storeName"], card.fields["dealTitle"]).joinToString(" · ")
        view.findViewById<TextView>(R.id.agent_order_meta).text = listOf(
            com.fandazi.app.feature.voucher.VoucherPresentation.voucherStatus(card.fields["status"].orEmpty()),
            "有效期：${BusinessDisplay.time(card.fields["expiresAt"])}", "券号末8位：${voucherId?.takeLast(8).orEmpty()}").joinToString("\n")
        view.findViewById<TextView>(R.id.agent_order_amount).apply {
            visibility = View.VISIBLE
            text = card.fields["amountCents"]?.toIntOrNull()?.let { "本券实付 ${BusinessDisplay.money(it)}" } ?: "本券尚无付款记录"
        }
        // 查询卡资格不影响只读详情权限；真正退款入口仍由券详情重读 canRefund 控制。
        view.findViewById<TextView>(R.id.agent_order_action).apply {
            text = "查看团购券"
            isEnabled = voucherId != null
            setOnClickListener { voucherId?.let { onNavigate(AgentNavigationRequest.Voucher(it)) } }
        }
        container.addView(view)
    }

    /** 待选券卡展示本人券事实；只有后端明确canRefund=true时用户才能点击完整公开券号。 */
    private fun renderVoucherChoice(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload,
                                    isCurrent: (AgentCardPayload) -> Boolean,
                                    onNavigate: (AgentNavigationRequest) -> Unit) {
        val voucherId = card.fields["voucherId"]?.takeIf(String::isNotBlank) ?: return
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        view.findViewById<TextView>(R.id.agent_order_title).text = card.fields["dealTitle"].orEmpty().ifBlank { "团购券" }
        view.findViewById<TextView>(R.id.agent_order_meta).text = listOfNotNull(
            card.fields["storeName"]?.takeIf(String::isNotBlank),
            card.fields["expiresAt"]?.takeIf(String::isNotBlank)?.let { "有效期：${BusinessDisplay.time(it)}" },
            card.fields["amountCents"]?.toIntOrNull()?.let { "金额：${BusinessDisplay.money(it)}" },
            when (card.fields["canRefund"]) { "true" -> "可申请退款"; "false" -> "不可申请退款"; else -> "资格未取得" },
            "券号末8位：${voucherId.takeLast(8).uppercase()}"
        ).joinToString("\n")
        view.findViewById<TextView>(R.id.agent_order_action).apply {
            text = "选择此券"
            isEnabled = card.fields["canRefund"] == "true" && isCurrent(card)
            // 点击时再次检查，避免页面重绘之前当前任务已被后端事件替换。
            setOnClickListener { if (isCurrent(card)) onNavigate(AgentNavigationRequest.AgentReply(voucherId, card)) }
        }
        container.addView(view)
    }

    /** 渲染门店卡，并把门店公开 ID只绑定到用户主动点击的详情按钮。 */
    private fun renderStore(
        fragment: Fragment,
        inflater: LayoutInflater,
        container: LinearLayout,
        card: AgentCardPayload,
        onNavigate: (AgentNavigationRequest) -> Unit
    ) {
        val view = inflater.inflate(R.layout.item_agent_store_card, container, false)
        val name = card.fields.display("name", "title")
        loadImage(fragment, view.findViewById(R.id.agent_store_image), card.fields.displayOrNull("imageUrl", "image_url"))
        view.findViewById<TextView>(R.id.agent_store_name).text = name
        view.findViewById<TextView>(R.id.agent_store_meta).text = listOfNotNull(
            card.fields.displayOrNull("rating")?.let { "★ $it" },
            card.fields.displayOrNull("distance")
        ).joinToString("  ·  ").ifBlank { "门店信息" }
        view.findViewById<TextView>(R.id.agent_store_address).text = listOf(
            card.fields.displayOrNull("reviewCount")?.let { "评价 $it" } ?: "评价数未取得",
            card.fields.displayOrNull("dealSummary") ?: "团购摘要未取得"
        ).joinToString("\n")
        view.findViewById<View>(R.id.agent_store_action).isEnabled = !card.storePublicId.isNullOrBlank()
        card.storePublicId?.takeIf(String::isNotBlank)?.let { storeId ->
            view.findViewById<TextView>(R.id.agent_store_action).setOnClickListener {
                onNavigate(AgentNavigationRequest.Store(storeId))
            }
        }
        container.addView(view)
    }

    /** 渲染套餐卡，套餐名称和价格均由服务端字段决定，避免所有套餐显示同一个固定名称。 */
    private fun renderDeal(
        fragment: Fragment,
        inflater: LayoutInflater,
        container: LinearLayout,
        card: AgentCardPayload,
        onNavigate: (AgentNavigationRequest) -> Unit
    ) {
        val view = inflater.inflate(R.layout.item_agent_deal_card, container, false)
        loadImage(fragment, view.findViewById(R.id.agent_deal_image), card.fields.displayOrNull("imageUrl", "image_url"))
        view.findViewById<TextView>(R.id.agent_deal_name).text = card.fields.display("name", "title")
        view.findViewById<TextView>(R.id.agent_deal_meta).text = listOfNotNull(
            card.fields.displayOrNull("storeName"),
            card.fields.displayOrNull("description"),
            card.fields.displayOrNull("purchaseLimit")?.let { "限购 $it" }
        ).joinToString("  ·  ").ifBlank { "套餐详情" }
        view.findViewById<TextView>(R.id.agent_deal_price).text = card.fields.displayPrice()
        // 仅以真实分值计算展示折扣；缺值、原价为零或价格反常时明确提示未取得，不补造促销。
        val price = card.fields["priceCents"]?.toBigDecimalOrNull()
        val original = card.fields["originalPriceCents"]?.toBigDecimalOrNull()
        val discount = if (price != null && original != null && original > java.math.BigDecimal.ZERO && price >= java.math.BigDecimal.ZERO && price <= original)
            price.multiply(java.math.BigDecimal.TEN).divide(original, 1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "折" else "折扣未取得"
        view.findViewById<TextView>(R.id.agent_deal_original_discount).text =
            "${original?.let { "原价 ¥${it.movePointLeft(2).setScale(2).toPlainString()}" } ?: "原价未取得"}  ·  $discount"
        view.findViewById<View>(R.id.agent_deal_action).isEnabled = !card.storePublicId.isNullOrBlank() && !card.dealPublicId.isNullOrBlank()
        if (!card.storePublicId.isNullOrBlank() && !card.dealPublicId.isNullOrBlank()) {
            view.findViewById<TextView>(R.id.agent_deal_action).setOnClickListener {
                onNavigate(AgentNavigationRequest.Deal(card.storePublicId, card.dealPublicId))
            }
        }
        container.addView(view)
    }

    /** 订单卡只显示本人服务端事实，用户显式点击才打开已有订单详情，不在本任务执行订单写操作。 */
    private fun renderOrder(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload,
                            onNavigate: (AgentNavigationRequest) -> Unit) {
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        view.findViewById<TextView>(R.id.agent_order_title).text = listOfNotNull(card.fields.displayOrNull("storeName"),
            card.fields.displayOrNull("dealName", "dealTitle", "title")).joinToString(" · ").ifBlank { "订单信息未取得" }
        view.findViewById<TextView>(R.id.agent_order_meta).text = listOfNotNull(
            card.fields.displayOrNull("status")?.let { orderStatus(it) },
            card.fields.displayOrNull("expiresAt")?.let { "有效期：${BusinessDisplay.time(it)}" } ?: "有效期未取得"
        ).joinToString("\n").ifBlank { "订单信息暂不可用" }
        view.findViewById<TextView>(R.id.agent_order_amount).apply {
            visibility = View.VISIBLE
            text = card.fields.displayOrNull("amount")?.let { "合计 ¥$it" }
                ?: "合计 ${BusinessDisplay.money(card.fields["totalCents"]?.toIntOrNull())}"
        }
        view.findViewById<TextView>(R.id.agent_order_action).apply {
            isEnabled = !card.orderPublicId.isNullOrBlank()
            setOnClickListener { card.orderPublicId?.let { onNavigate(AgentNavigationRequest.Order(it)) } }
        }
        container.addView(view)
    }

    /** 机器状态只映射既有业务枚举，未知状态明确缺值，不根据本地时间推定订单资格。 */
    private fun orderStatus(raw: String): String = when (raw) {
        "PENDING_PAYMENT" -> "待支付"
        "PAID", "UNUSED" -> "待使用"
        "PARTIALLY_USED" -> "部分已使用"
        "COMPLETED", "REDEEMED", "USED" -> "已完成"
        "CANCELLED" -> "已取消"
        "REFUNDED" -> "已退款"
        "EXPIRED" -> "已过期"
        else -> "状态未取得"
    }

    /** 公共准备确认卡展示真实完整摘要；按钮只发送明确语句，渲染本身不消费任何凭证。 */
    private fun renderConfirmation(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload,
                                   isCurrentConfirmation: (AgentCardPayload) -> Boolean,
                                   onNavigate: (AgentNavigationRequest) -> Unit) {
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        val fields = card.fields
        val amount = fields["amountCents"]?.toIntOrNull()
        val action = fields["action"]
        val expiry = BusinessDisplay.time(fields["expiresAt"])
        val complete = !fields["resourceId"].isNullOrBlank() && !fields["ruleVersion"].isNullOrBlank() &&
            !fields["title"].isNullOrBlank() && amount != null && amount >= 0 && expiry != "时间未取得" &&
            action in setOf("create_group_buy_order", "pay_mock_order")
        view.findViewById<TextView>(R.id.agent_order_title).text = fields["title"].orEmpty().ifBlank { "请核对操作摘要" }
        view.findViewById<TextView>(R.id.agent_order_meta).text = listOfNotNull(fields["storeName"], fields["dealTitle"],
            fields["quantity"]?.let { "数量：$it" }, "合计：${BusinessDisplay.money(amount)}",
            "规则版本：${fields["ruleVersion"].orEmpty().ifBlank { "未取得" }}", "确认截止：$expiry").joinToString("\n")
        view.findViewById<TextView>(R.id.agent_order_action).apply {
            text = if (complete) "确认" else "摘要不完整，请重新查询"
            // 旧摘要仍显示原事实和按钮文案，但终态/被替换版本不可点击；动作入口还会再校验防重绘竞态。
            isEnabled = complete && isCurrentConfirmation(card)
            alpha = if (isEnabled) 1f else 0.4f
            // 使用网关已审核的确认整句；按钮文案不能被当成新增的关键词路由。
            setOnClickListener { onNavigate(AgentNavigationRequest.AgentReply(if (action == "pay_mock_order") "确认支付" else "确认", card)) }
        }
        view.findViewById<TextView>(R.id.agent_after_sale_cancel).apply {
            visibility = View.VISIBLE; text = "取消本次操作"
            isEnabled = complete && isCurrentConfirmation(card)
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener { onNavigate(AgentNavigationRequest.AgentReply("取消", card)) }
        }
        container.addView(view)
    }

    /** 优惠券只读：状态完全采用后端文本，不根据日期、设备时间或门槛计算资格。 */
    private fun renderCoupon(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload) {
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        val fields = card.fields
        view.findViewById<TextView>(R.id.agent_order_title).text = fields.display("name", "title").ifBlank { "优惠券" }
        val status = when (fields["status"]) {
            "AVAILABLE" -> "可用"
            "USED" -> "已使用"
            "EXPIRED" -> "已过期"
            "NOT_YET_VALID" -> "未生效"
            "DISABLED" -> "已停用"
            else -> "状态未取得"
        }
        val threshold = fields["thresholdCents"]?.toIntOrNull()
        val discount = fields["discountCents"]?.toIntOrNull()
        view.findViewById<TextView>(R.id.agent_order_meta).text = listOfNotNull(
            "状态：$status",
            if (threshold != null && discount != null) "满${BusinessDisplay.money(threshold)}减${BusinessDisplay.money(discount)}" else "金额规则未取得",
            fields["validFrom"]?.let { "生效日期：${couponDate(it)}" },
            fields["validTo"]?.let { "截止日期：${couponDate(it)}" },
            fields["scopeDescription"]?.takeIf(String::isNotBlank)
        ).joinToString("\n")
        view.findViewById<View>(R.id.agent_order_action).visibility = View.GONE
        container.addView(view)
    }

    /** 领域优惠券可能返回业务日期或UTC边界；仅格式化，不将日期用于推断可用性。 */
    private fun couponDate(value: String): String = runCatching { LocalDate.parse(value).toString() }
        .getOrElse { BusinessDisplay.time(value) }

    /** 售后确认卡只展示 Agent 公开摘要；用户点击按钮才发送确认语句。 */
    private fun renderAfterSale(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload,
                                isCurrent: (AgentCardPayload) -> Boolean,
                                onNavigate: (AgentNavigationRequest) -> Unit) {
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        val refund = card.type.lowercase() == "after_sale_refund"
        val fields = card.fields
        // 新契约只保证resourceId；voucherId是Spring公开兼容别名，订单号并非确认必需项。
        val voucherId = fields.displayOrNull("voucherId", "resourceId")
        val amount = fields["amountCents"]?.toIntOrNull()
        val expiry = BusinessDisplay.time(fields["expiresAt"])
        // 完整摘要只决定是否允许点击；后端仍校验资格、凭证与截止，客户端不自行授权。
        val complete = expiry != "时间未取得" && if (refund) {
            !voucherId.isNullOrBlank() &&
                !fields["dealTitle"].isNullOrBlank() && !fields["reason"].isNullOrBlank() && amount != null && amount > 0
        } else !fields["subject"].isNullOrBlank() && !fields["description"].isNullOrBlank()
        view.findViewById<TextView>(R.id.agent_order_title).text = if (refund) "退款申请确认" else "投诉与反馈确认"
        view.findViewById<TextView>(R.id.agent_order_meta).text = if (refund) {
            // 完整券号只用于受控操作，不在聊天卡片中展示长标识；金额和原因仍逐项供用户核对。
            listOfNotNull(fields["storeName"], fields["dealTitle"], voucherId?.let { "券号末8位：${it.takeLast(8).uppercase()}" },
                "金额：${BusinessDisplay.money(amount)}", fields["reason"]?.let { "原因：$it" },
                "确认截止：$expiry").joinToString("\n")
        } else {
            listOfNotNull(card.fields["subject"]?.let { "问题类型：$it" },
                card.fields["orderId"]?.takeIf(String::isNotBlank)?.let { "订单末8位：${it.takeLast(8).uppercase()}" },
                card.fields["description"]?.let { "问题描述：$it" }, "确认截止：$expiry").joinToString("\n")
        }
        view.findViewById<TextView>(R.id.agent_order_action).apply {
            text = if (refund) "确认退款" else "确认投诉"
            isEnabled = complete && isCurrent(card)
            if (!complete) text = "摘要不完整，请重新查询"
            setOnClickListener { if (complete && isCurrent(card)) onNavigate(AgentNavigationRequest.AgentReply(if (refund) "确认退款" else "确认投诉", card)) }
        }
        view.findViewById<TextView>(R.id.agent_after_sale_cancel).apply {
            visibility = View.VISIBLE
            isEnabled = isCurrent(card)
            setOnClickListener { if (isCurrent(card)) onNavigate(AgentNavigationRequest.AgentReply(if (refund) "取消退款" else "取消投诉", card)) }
        }
        container.addView(view)
    }

    /** 未知卡片降级为普通信息卡，保证协议扩展不会导致页面崩溃或渲染空白。 */
    private fun renderUnknown(inflater: LayoutInflater, container: LinearLayout, card: AgentCardPayload) {
        val view = inflater.inflate(R.layout.item_agent_order_card, container, false)
        view.findViewById<TextView>(R.id.agent_order_title).text = "助手信息"
        view.findViewById<TextView>(R.id.agent_order_meta).text = card.fields.display("title", "name").ifBlank { "收到新的助手信息" }
        view.findViewById<TextView>(R.id.agent_order_action).visibility = android.view.View.GONE
        container.addView(view)
    }

    /** 优先读取字段别名，兼容协议中名称和展示标题的不同来源。 */
    private fun Map<String, String>.display(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        get(key)?.takeIf(String::isNotBlank)
    }.orEmpty()

    /** 读取可选字段；空字段不参与拼接，避免页面出现多余分隔符。 */
    private fun Map<String, String>.displayOrNull(vararg keys: String): String? = display(*keys).takeIf(String::isNotBlank)

    /** 只在后端提供合法图片地址时加载图片；地址缺失时保留 XML 占位背景。 */
    private fun loadImage(fragment: Fragment, image: ImageView, url: String?) {
        if (url.isNullOrBlank()) return
        // AI 卡片与目录使用同一审核资源，不把站内相对路径交给 Glide 当设备文件。
        val source = CatalogImageUrls.resolve(url) ?: return
        Glide.with(fragment).load(source).placeholder(R.drawable.merchant_cover_placeholder)
            .error(R.drawable.merchant_cover_placeholder).into(image)
    }

    /** 将服务端价格字段转换为可读金额，优先使用已经格式化的 price 字段。 */
    private fun Map<String, String>.displayPrice(): String {
        displayOrNull("price")?.let { return it }
        val cents = displayOrNull("priceCents")?.toLongOrNull() ?: return "价格待确认"
        return "¥%.2f".format(java.util.Locale.ROOT, cents / 100.0)
    }

}
