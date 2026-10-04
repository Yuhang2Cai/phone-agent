// 卡片共用Holder：复用一组公开事实控件，不持有列表下标，不读取或消费任何授权凭证。
package com.fandazi.app.feature.agent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.fandazi.app.R
import com.fandazi.app.core.image.CatalogImageUrls
import com.fandazi.app.core.time.BusinessDisplay
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.util.Locale

/** 所有业务卡使用同一骨架；导航回调携带原公开ID，确认回调携带原摘要供ViewModel复核。 */
class AgentCardViewHolder(view:View,private val navigate:(AgentNavigationRequest,AgentCardPayload)->Unit):RecyclerView.ViewHolder(view) {
    private val root=view as LinearLayout
    private val image=view.findViewById<ImageView>(R.id.agent_reusable_image)
    private val info=view.findViewById<LinearLayout>(R.id.agent_reusable_card_info)
    private val title=view.findViewById<TextView>(R.id.agent_reusable_card_title)
    private val meta=view.findViewById<TextView>(R.id.agent_reusable_card_meta)
    private val detail=view.findViewById<TextView>(R.id.agent_reusable_card_detail)
    private val amount=view.findViewById<TextView>(R.id.agent_reusable_card_amount)
    private val action=view.findViewById<TextView>(R.id.agent_reusable_card_action)
    private val discount=view.findViewById<TextView>(R.id.agent_reusable_card_discount)
    private val cancel=view.findViewById<TextView>(R.id.agent_after_sale_cancel)
    /** 全量绑定先清理图片及监听，再校验字段与资格，最后构造内容，缺值不补造事实。 */
    fun bind(row:AgentRenderRow.Card) {
        recycle();val card=row.card;val f=card.fields;val type=card.type.lowercase()
        // 同一控件恢复默认视觉和隐藏槽，门店/套餐以外都使用全宽纵向信息。
        val pictured=type=="store" || type=="deal"
        root.id=when(type){"store"->R.id.agent_store_card;"deal"->R.id.agent_deal_card;else->R.id.agent_order_card}
        image.id=if(type=="store")R.id.agent_store_image else R.id.agent_deal_image
        image.isVisible=pictured;image.setImageDrawable(null);image.clearColorFilter()
        (info.layoutParams as LinearLayout.LayoutParams).apply{width=if(pictured)0 else -1;weight=if(pictured)1f else 0f;marginStart=dp(if(pictured)12 else 0)}
        title.id=when(type){"store"->R.id.agent_store_name;"deal"->R.id.agent_deal_name;else->R.id.agent_order_title}
        meta.id=when(type){"store"->R.id.agent_store_meta;"deal"->R.id.agent_deal_meta;else->R.id.agent_order_meta}
        detail.id=R.id.agent_store_address;amount.id=if(type=="deal")R.id.agent_deal_price else R.id.agent_order_amount
        action.id=when(type){"store"->R.id.agent_store_action;"deal"->R.id.agent_deal_action;else->R.id.agent_order_action}
        discount.id=R.id.agent_deal_original_discount
        title.textSize=when(type){"store"->18f;"deal"->17f;else->15f};title.setTextColor(color(if(type=="deal")R.color.group_buy_text else R.color.group_buy_green))
        meta.textSize=if(type=="store")15f else 12f;meta.setTextColor(color(if(type=="store")R.color.group_buy_orange else R.color.group_buy_muted));meta.maxLines=if(pictured)2 else Int.MAX_VALUE
        amount.textSize=if(type=="deal")19f else 16f
        (action.layoutParams as LinearLayout.LayoutParams).height=dp(if(pictured)36 else 40)
        action.text="";cancel.text="";action.alpha=1f;cancel.alpha=1f
        // 真实图片仍来自目录接口，缺图清空上次请求后才使用既有中性占位；不把上次菜品当当前菜品。
        if(pictured) {
            image.contentDescription=if(type=="store")"门店图片" else "套餐图片"
            val source=CatalogImageUrls.resolve(f.value("imageUrl","image_url"))
            image.setImageResource(R.drawable.merchant_cover_placeholder)
            if(source!=null) Glide.with(image).load(source).placeholder(R.drawable.merchant_cover_placeholder).error(R.drawable.merchant_cover_placeholder).into(image)
        }
        when(type) {
            "store" -> {
                // 门店评分/评价/团购摘要都是后端字段；缺信息只显示明确缺值文案，详情绑定原公开ID。
                title.text=f.value("name","title");meta.text=listOfNotNull(f["rating"]?.let{"★ $it"},f["distance"]).joinToString("  ·  ").ifBlank{"门店信息"}
                detail.text=listOf(f["reviewCount"]?.let{"评价 $it"} ?: "评价数未取得",f["dealSummary"] ?: "团购摘要未取得").joinToString("\n");detail.isVisible=true
                button("查看详情",card.storePublicId?.takeIf{it.isNotBlank()}?.let{AgentNavigationRequest.Store(it)},card)
            }
            "deal" -> {
                // 价格仅格式化真实分值；原价为零或异常价格时不推算促销折扣。
                title.text=f.value("name","title");meta.text=listOfNotNull(f["storeName"],f["description"],f["purchaseLimit"]?.let{"限购 $it"}).joinToString("  ·  ").ifBlank{"套餐详情"}
                amount.text=f["price"] ?: f["priceCents"]?.toLongOrNull()?.let{String.format(Locale.ROOT,"¥%.2f",it/100.0)} ?: "价格待确认";amount.isVisible=true
                val price=f["priceCents"]?.toBigDecimalOrNull();val original=f["originalPriceCents"]?.toBigDecimalOrNull()
                val reduction=if(price!=null && original!=null && original>BigDecimal.ZERO && price>=BigDecimal.ZERO && price<=original)price.multiply(BigDecimal.TEN).divide(original,1,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()+"折" else "折扣未取得"
                discount.text=(original?.let{"原价 ¥${it.movePointLeft(2).setScale(2).toPlainString()}"} ?: "原价未取得")+"  ·  $reduction";discount.isVisible=true
                button("查看详情",if(!card.storePublicId.isNullOrBlank() && !card.dealPublicId.isNullOrBlank())AgentNavigationRequest.Deal(card.storePublicId,card.dealPublicId)else null,card)
            }
            "order" -> {
                // 未付款不写实付，状态只映射服务端枚举，不根据设备日期推定资格。
                title.text=listOfNotNull(f["storeName"],f.optional("dealName","dealTitle","title")).joinToString(" · ").ifBlank{"订单信息未取得"}
                meta.text=listOfNotNull(f["status"]?.let{orderStatus(it)},f["expiresAt"]?.let{"有效期：${BusinessDisplay.time(it)}"} ?: "有效期未取得").joinToString("\n")
                amount.text=f["amount"]?.let{"合计 ¥$it"} ?: "合计 ${BusinessDisplay.money(f["totalCents"]?.toIntOrNull())}";amount.isVisible=true
                button("查看订单",card.orderPublicId?.takeIf{it.isNotBlank()}?.let{AgentNavigationRequest.Order(it)},card)
            }
            "voucher" -> {
                // 只读历史券允许显式查看详情，退款资格仍由详情后端重新读取。
                val id=f["voucherId"]?.takeIf{it.isNotBlank()};title.text=listOfNotNull(f["storeName"],f["dealTitle"]).joinToString(" · ")
                meta.text=listOf(com.fandazi.app.feature.voucher.VoucherPresentation.voucherStatus(f["status"].orEmpty()),"有效期：${BusinessDisplay.time(f["expiresAt"])}","券号末8位：${id?.takeLast(8).orEmpty()}").joinToString("\n")
                amount.text=f["amountCents"]?.toIntOrNull()?.let{"本券实付 ${BusinessDisplay.money(it)}"} ?: "本券尚无付款记录";amount.isVisible=true
                button("查看团购券",id?.let{AgentNavigationRequest.Voucher(it)},card)
            }
            "after_sale_voucher_choice" -> {
                // 选择资格必须同时满足当前原事件版本和后端canRefund，不能以券可见性替代授权。
                val id=f["voucherId"]?.takeIf{it.isNotBlank()};title.text=f["dealTitle"].orEmpty().ifBlank{"团购券"}
                meta.text=listOfNotNull(f["storeName"],f["expiresAt"]?.let{"有效期：${BusinessDisplay.time(it)}"},f["amountCents"]?.toIntOrNull()?.let{"金额：${BusinessDisplay.money(it)}"},when(f["canRefund"]){"true"->"可申请退款";"false"->"不可申请退款";else->"资格未取得"},"券号末8位：${id?.takeLast(8)?.uppercase().orEmpty()}").joinToString("\n")
                button("选择此券",if(row.current && f["canRefund"]=="true" && id!=null)AgentNavigationRequest.AgentReply(id,card)else null,card)
            }
            "confirmation" -> {
                // 字段完整性决定摘要可用性，current快照决定当前等待版本；点击再由ViewModel实时复核。
                val cents=f["amountCents"]?.toIntOrNull();val expiry=BusinessDisplay.time(f["expiresAt"]);val operation=f["action"]
                val complete=!f["resourceId"].isNullOrBlank() && !f["ruleVersion"].isNullOrBlank() && !f["title"].isNullOrBlank() && cents!=null && cents>=0 && expiry!="时间未取得" && operation in setOf("create_group_buy_order","pay_mock_order")
                title.text=f["title"].orEmpty().ifBlank{"请核对操作摘要"};meta.text=listOfNotNull(f["storeName"],f["dealTitle"],f["quantity"]?.let{"数量：$it"},"合计：${BusinessDisplay.money(cents)}","规则版本：${f["ruleVersion"].orEmpty().ifBlank{"未取得"}}","确认截止：$expiry").joinToString("\n")
                button(if(complete)"确认" else "摘要不完整，请重新查询",if(complete && row.current)AgentNavigationRequest.AgentReply(if(operation=="pay_mock_order")"确认支付" else "确认",card)else null,card)
                cancelButton("取消本次操作",if(complete && row.current)AgentNavigationRequest.AgentReply("取消",card)else null,card)
            }
            "coupon" -> {
                // 优惠券完全只读，日期只作格式化，不按设备时间计算可用性，也不留下上项操作按钮。
                title.text=f.value("name","title").ifBlank{"优惠券"};val threshold=f["thresholdCents"]?.toIntOrNull();val reduction=f["discountCents"]?.toIntOrNull()
                val status=when(f["status"]){"AVAILABLE"->"可用";"USED"->"已使用";"EXPIRED"->"已过期";"NOT_YET_VALID"->"未生效";"DISABLED"->"已停用";else->"状态未取得"}
                meta.text=listOfNotNull("状态：$status",if(threshold!=null && reduction!=null)"满${BusinessDisplay.money(threshold)}减${BusinessDisplay.money(reduction)}" else "金额规则未取得",f["validFrom"]?.let{"生效日期：${date(it)}"},f["validTo"]?.let{"截止日期：${date(it)}"},f["scopeDescription"]).joinToString("\n")
            }
            "after_sale_refund","after_sale_complaint" -> {
                // 两种售后共享摘要槽但保留独立合法动作语句，不让同Holder复用混用退款与投诉。
                val refund=type=="after_sale_refund";val id=f.optional("voucherId","resourceId");val cents=f["amountCents"]?.toIntOrNull();val expiry=BusinessDisplay.time(f["expiresAt"])
                val complete=expiry!="时间未取得" && if(refund)!id.isNullOrBlank() && !f["dealTitle"].isNullOrBlank() && !f["reason"].isNullOrBlank() && cents!=null && cents>0 else !f["subject"].isNullOrBlank() && !f["description"].isNullOrBlank()
                title.text=if(refund)"退款申请确认" else "投诉与反馈确认"
                meta.text=if(refund)listOfNotNull(f["storeName"],f["dealTitle"],id?.let{"券号末8位：${it.takeLast(8).uppercase()}"},"金额：${BusinessDisplay.money(cents)}",f["reason"]?.let{"原因：$it"},"确认截止：$expiry").joinToString("\n") else listOfNotNull(f["subject"]?.let{"主题：$it"},f["description"]?.let{"内容：$it"},"确认截止：$expiry").joinToString("\n")
                button(if(refund)"确认退款" else "确认投诉",if(complete && row.current)AgentNavigationRequest.AgentReply(if(refund)"确认退款" else "确认投诉",card)else null,card)
                cancelButton(if(refund)"取消退款" else "取消投诉",if(row.current)AgentNavigationRequest.AgentReply(if(refund)"取消退款" else "取消投诉",card)else null,card)
            }
            // 未知扩展只呈现安全公开说明，默认隐藏金额/图像/全部动作，避免错误执行旧监听。
            else -> { title.text="助手信息";meta.text=f.value("title","name").ifBlank{"收到新的助手信息"} }
        }
        itemView.tag=row.key
    }
    /** 缺少公开ID或当前资格时按钮保留原文案但禁用，没有监听、透明度明确。 */
    private fun button(label:String,request:AgentNavigationRequest?,card:AgentCardPayload) {action.text=label;action.isVisible=true;action.isEnabled=request!=null;action.alpha=if(request!=null)1f else .4f;if(request!=null)action.setOnClickListener{navigate(request,card)}}
    /** 取消槽同样只接受当前原摘要；只读卡不因可见性恢复授权。 */
    private fun cancelButton(label:String,request:AgentNavigationRequest?,card:AgentCardPayload) {cancel.text=label;cancel.isVisible=true;cancel.isEnabled=request!=null;cancel.alpha=if(request!=null)1f else .4f;if(request!=null)cancel.setOnClickListener{navigate(request,card)}}
    /** 清除上一次图像请求、正文、金额、可见性和全部动作；绑定不inflate任何完整子树。 */
    fun recycle() {
        Glide.with(image).clear(image);image.setImageDrawable(null);image.isVisible=false
        listOf(title,meta,detail,amount,discount,action,cancel).forEach{it.text="";it.setOnClickListener(null);it.alpha=1f}
        listOf(detail,amount,discount,action,cancel).forEach{it.isVisible=false};action.isEnabled=false;cancel.isEnabled=false;itemView.tag=null
    }
    private fun Map<String,String>.value(vararg keys:String)=keys.firstNotNullOfOrNull{get(it)?.takeIf(String::isNotBlank)}.orEmpty()
    private fun Map<String,String>.optional(vararg keys:String)=value(*keys).takeIf(String::isNotBlank)
    private fun date(s:String)=runCatching{LocalDate.parse(s).toString()}.getOrElse{BusinessDisplay.time(s)}
    private fun orderStatus(s:String)=when(s){"PENDING_PAYMENT"->"待支付";"PAID","UNUSED"->"待使用";"PARTIALLY_USED"->"部分已使用";"COMPLETED","REDEEMED","USED"->"已完成";"CANCELLED"->"已取消";"REFUNDED"->"已退款";"EXPIRED"->"已过期";else->"状态未取得"}
    private fun dp(n:Int)=(n*itemView.resources.displayMetrics.density).toInt()
    private fun color(id:Int)=ContextCompat.getColor(itemView.context,id)
}
