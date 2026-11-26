// 秒杀用户详情与结果页面：将确认稿U02—U13的视觉结构绑定到权威活动/订单状态，不在控件中执行业务推断。
package com.fandazi.app.feature.seckill

import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fandazi.app.FandaziApplication
import com.fandazi.app.MainActivity
import com.fandazi.app.R
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.feature.auth.SessionState
import com.fandazi.app.feature.auth.authenticationIdentity
import com.fandazi.app.feature.purchase.purchaseError
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChangedBy

/** 共用一套XML结构呈现活动、倒计时和订单结果；底部确认为真实模态窗口。 */
class SeckillFragment:Fragment(R.layout.fragment_seckill) {
    private val model:SeckillViewModel by viewModels()
    private val flow get()=model.flow
    private val app get()=requireActivity().application as FandaziApplication
    private val activityId get()=requireArguments().getString("activityId").orEmpty()
    private var sheet:BottomSheetDialog?=null
    private var sheetView:View?=null
    private var quantity=1
    private var imageKey:String?=null
    private var summaryKey:String?=null
    private var sheetImageKey:String?=null
    private var lastPoll=0L
    private enum class Screen { LOADING,UPCOMING,LIVE,PENDING,PAID,CLOSED,SOLD_OUT,LIMIT,ENDED,OFFLINE,SYNCING,UNKNOWN,OTHER_ORDER }

    /** 页面可见时才轮询；后台停止读取，恢复只核查原事实，账号切换即时清除旧确认。 */
    override fun onViewCreated(view:View,savedInstanceState:Bundle?) {
        ViewCompat.setOnApplyWindowInsetsListener(view){root,insets->root.updatePadding(top=insets.getInsets(WindowInsetsCompat.Type.statusBars()).top);insets}
        ViewCompat.requestApplyInsets(view)
        view.findViewById<View>(R.id.sk_back).setOnClickListener {findNavController().navigateUp()}
        view.findViewById<View>(R.id.sk_orders).setOnClickListener {openOrders()}
        view.findViewById<View>(R.id.sk_primary).setOnClickListener {primary()}
        view.findViewById<View>(R.id.sk_secondary).setOnClickListener {
            if(screen(flow.state.value) in setOf(Screen.LIMIT,Screen.ENDED))openOrders() else launchAction {flow.refresh()}
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {app.sessionRepository.state.distinctUntilChangedBy(::authenticationIdentity).collect {state->
                    sheet?.dismiss();lastPoll=SystemClock.elapsedRealtime();model.enter(activityId,state,arguments?.getString("orderId"))
                }}
                launch {flow.state.collect {state->render(view,state)}}
                launch {
                    // 250毫秒只更新三格数字；网络最多按5秒节拍，边界归零只额外提前本次读取并重置节拍。
                    while(isActive) {
                        val wasReady=flow.state.value.synchronized
                        flow.tick();renderClock(view)
                        val now=SystemClock.elapsedRealtime()
                        val boundary=wasReady && !flow.state.value.synchronized
                        if(!flow.state.value.busy && (boundary || now-lastPoll>=5000)) {
                            lastPoll=now
                            // 请求作为当前可见生命周期的子协程执行，慢网不能暂停单调计时器。
                            // 请求期间仍需在归零时撤销购买资格并展示同步态；busy防止再发并行轮询，离开页面会一起取消。
                            launch {
                                try {flow.refresh()}
                                finally {
                                    // 慢请求结束后再等待完整轮询间隔，给手动购买留出稳定操作窗口。
                                    // 归零边界仍由boundary立即触发，不受这段常规节拍等待影响。
                                    lastPoll=SystemClock.elapsedRealtime()
                                }
                            }
                        }
                        delay(250)
                    }
                }
            }
        }
    }
    /** 明确按钮动作才可能报价或写入；未知结果按钮永远只读。 */
    private fun primary() {
        when(screen(flow.state.value)) {
            Screen.LIVE->{
                if(app.sessionRepository.state.value !is SessionState.Authenticated) {
                    (requireActivity() as MainActivity).openPurchaseLogin(R.id.seckillFragment,Bundle(requireArguments()))
                } else {quantity=1;launchAction {flow.prepare(quantity)}}
            }
            Screen.PENDING->launchAction {flow.pay()}
            Screen.UNKNOWN->launchAction {flow.refresh()}
            Screen.CLOSED->launchAction {flow.returnToActivity()}
            Screen.PAID->{
                val order=flow.state.value.order?.value ?: return
                val voucher=order.vouchers.firstOrNull()
                if(voucher!=null)findNavController().navigate(R.id.voucherDetailFragment,Bundle().apply {putString("voucherId",voucher.publicId)})
                else openOrder(order.orderId)
            }
            Screen.OTHER_ORDER->flow.state.value.order?.value?.orderId?.let(::openOrder)
            else->Unit
        }
    }
    /** 登录返回只带公开目标，不保存授权或自动提交标记。 */
    private fun openOrders() {
        if(app.sessionRepository.state.value !is SessionState.Authenticated) {
            (requireActivity() as MainActivity).openPurchaseLogin(R.id.seckillFragment,Bundle(requireArguments()))
        } else findNavController().navigate(R.id.ordersFragment)
    }
    /** 使用后端公开订单身份复用既有只读详情。 */
    private fun openOrder(id:String)=findNavController().navigate(R.id.groupOrderDetailFragment,Bundle().apply {putString("orderId",id)})
    /** 动作结束刷新节拍，避免一次点击与定时轮询同时重复读取。 */
    private fun launchAction(block:suspend ()->Unit) {viewLifecycleOwner.lifecycleScope.launch {lastPoll=SystemClock.elapsedRealtime();block()}}
    /** 明确业务终态优先于展示时钟；网络异常不虚构售罄、超时关闭或成功。 */
    private fun screen(s:SeckillState):Screen {
        if(s.unknown)return Screen.UNKNOWN
        s.order?.let {return when(it.value.status){"PENDING_PAYMENT"->Screen.PENDING;"PAID","PARTIALLY_USED","USED"->Screen.PAID;"CLOSED_TIMEOUT"->Screen.CLOSED;else->Screen.OTHER_ORDER}}
        val reason=(s.error as? ApiError.Http)?.reasonCode ?: s.eligibility?.reason
        if(reason=="LIMIT_REACHED")return Screen.LIMIT
        if(reason=="SOLD_OUT")return Screen.SOLD_OUT
        if(reason in setOf("ENDED","STOPPED") || (s.eligibility?.state ?: s.activity?.state) in setOf("ENDED","STOPPED"))return Screen.ENDED
        if(s.error!=null)return Screen.OFFLINE
        if(s.activity==null)return Screen.LOADING
        if(!s.synchronized)return Screen.SYNCING
        return if((s.eligibility?.state ?: s.activity.state)=="UPCOMING")Screen.UPCOMING else if((s.eligibility?.available ?: s.activity.available)==0)Screen.SOLD_OUT else Screen.LIVE
    }
    /** 只在事实变化时更新结构；每秒计时单独绑定，不重排整页或重载图片。 */
    private fun render(root:View,s:SeckillState) {
        val mode=screen(s);val activity=s.activity;val deal=activity?.deal;val order=s.order?.value
        val hero=mode in setOf(Screen.UPCOMING,Screen.LIVE,Screen.SOLD_OUT,Screen.ENDED,Screen.SYNCING)
        val status=mode in setOf(Screen.PENDING,Screen.PAID,Screen.CLOSED,Screen.LIMIT,Screen.OFFLINE,Screen.UNKNOWN,Screen.OTHER_ORDER,Screen.LOADING)
        root.findViewById<View>(R.id.sk_hero).isVisible=hero && deal!=null
        root.findViewById<View>(R.id.sk_price_section).isVisible=hero && deal!=null
        root.findViewById<View>(R.id.sk_status).isVisible=status
        root.findViewById<View>(R.id.sk_summary).isVisible=status && (deal!=null || order!=null)
        root.findViewById<View>(R.id.sk_timer).isVisible=mode in setOf(Screen.UPCOMING,Screen.LIVE,Screen.PENDING,Screen.SYNCING)
        // 支付为5分钟分秒展示，活动为时分秒展示，分别对应确认稿的两种计时结构。
        root.findViewById<View>(R.id.sk_hours).isVisible=mode!=Screen.PENDING
        root.findViewById<View>(R.id.sk_hour_separator).isVisible=mode!=Screen.PENDING
        root.findViewById<View>(R.id.sk_stock_section).isVisible=mode in setOf(Screen.UPCOMING,Screen.LIVE,Screen.SOLD_OUT)
        root.findViewById<View>(R.id.sk_info).isVisible=deal!=null && mode in setOf(Screen.UPCOMING,Screen.LIVE,Screen.PAID)
        // 支付期限是倒计时，券使用日期是独立事实；两者分区展示，不能让用户把5分钟误当券有效期。
        root.findViewById<View>(R.id.sk_payment_facts).isVisible=mode==Screen.PENDING
        if(mode==Screen.PENDING) {
            root.text(R.id.sk_payment_quantity,"购买数量　${order?.quantity} 份")
            root.text(R.id.sk_payment_amount,"应付金额　${order?.totalCents?.let(::seckillMoney)}")
            root.text(R.id.sk_payment_dates,deal?.let {"券使用日期　${it.validFrom} 至 ${it.validTo}"} ?: "券使用日期　请进入券详情核对")
        }
        root.findViewById<View>(R.id.sk_notice).isVisible=mode in setOf(Screen.SOLD_OUT,Screen.ENDED,Screen.SYNCING)
        val centered=mode in setOf(Screen.PENDING,Screen.PAID)
        root.findViewById<android.widget.LinearLayout>(R.id.sk_status).gravity=if(centered)android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.START
        root.findViewById<TextView>(R.id.sk_status_title).gravity=if(centered)android.view.Gravity.CENTER else android.view.Gravity.START
        root.findViewById<TextView>(R.id.sk_status_description).gravity=if(centered)android.view.Gravity.CENTER else android.view.Gravity.START
        root.findViewById<View>(R.id.sk_status_badge).setBackgroundResource(if(mode==Screen.PAID)R.drawable.sk_bg_mint else R.drawable.sk_bg_peach)
        root.findViewById<ImageView>(R.id.sk_status_icon).setImageResource(when(mode){Screen.PAID->R.drawable.sk_cb363;Screen.LIMIT->R.drawable.sk_36794;Screen.OFFLINE->R.drawable.sk_5caed;Screen.UNKNOWN,Screen.LOADING->R.drawable.sk_97586;else->R.drawable.sk_9308a})
        root.text(R.id.sk_status_title,when(mode){Screen.PENDING->"已为你保留${order?.quantity ?: ""}份";Screen.PAID->"这份好味道，抢到了";Screen.CLOSED->"这次预留已结束";Screen.LIMIT->"已达到本次限购数量";Screen.OFFLINE->"网络暂时断开了";Screen.UNKNOWN->"正在确认提交结果";Screen.OTHER_ORDER->"订单状态已更新";else->"正在读取活动"})
        // 成功页按确认稿展示金额与券数量。金额明确标注订单金额，避免把订单总价误称独立的实付快照。
        // 券数量直接取后端订单事实；不能用购买数量或设计示例替代实际发券数量。
        root.text(R.id.sk_status_description,when(mode){Screen.PENDING->"请在倒计时结束前完成模拟支付";Screen.PAID->order?.let {"订单金额 ${seckillMoney(it.totalCents)} · 获得${it.voucherCount}张团购券"} ?: "正在核对订单权益";Screen.CLOSED->"订单未支付，库存与本次预留额度已释放";Screen.LIMIT->"待支付预留和已支付数量均计入活动限购";Screen.OFFLINE->"连接恢复后将重新核对活动与本人资格";Screen.UNKNOWN->"请求比平时慢一些，请先查询本次结果";Screen.OTHER_ORDER->"请进入订单详情核对最新权益";else->"正在连接服务器，请稍候"})
        root.text(R.id.sk_nav_title,when(mode){Screen.PENDING->"待支付";Screen.PAID->"支付成功";Screen.CLOSED->"订单已关闭";Screen.UNKNOWN->"查询提交结果";else->"限时秒杀"})
        if(deal!=null) {
            root.text(R.id.sk_title,deal.title);root.text(R.id.sk_price,seckillMoney(deal.priceCents));root.text(R.id.sk_original,seckillMoney(deal.originalPriceCents))
            root.findViewById<TextView>(R.id.sk_original).paintFlags=root.findViewById<TextView>(R.id.sk_original).paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            root.text(R.id.sk_saving,"省 ${seckillMoney(deal.originalPriceCents-deal.priceCents)}");root.text(R.id.sk_description,deal.description)
            bindInfo(root,activity)
            val key="${activity.id}:${activity.version}:${deal.imageUrl}"
            if(key!=imageKey){imageKey=key;SeckillImages.bind(root.findViewById(R.id.sk_hero),deal.imageUrl,deal.imageAlt,activity.version.toString())}
            val stock=s.eligibility?.available ?: activity.available
            root.text(R.id.sk_stock,"剩余 $stock 份");root.text(R.id.sk_quota,"每人限购 ${activity.limit} 份")
            root.findViewById<ProgressBar>(R.id.sk_stock_bar).apply {max=activity.total;progress=stock}
        }
        val summary=root.findViewById<View>(R.id.sk_summary)
        bindSummary(summary,s)
        val summaryImage="${order?.orderId}:${order?.imageUrl}:${deal?.imageUrl}:${activity?.version}"
        if(summaryImage!=summaryKey){summaryKey=summaryImage;SeckillImages.bind(summary,order?.imageUrl ?: deal?.imageUrl,order?.imageAlt ?: deal?.imageAlt,activity?.version?.toString())}
        // 同步期间沿用最后核实的边界类型，标题、日期与数字必须指向同一个开始或结束时刻。
        val awaitingStart=(s.eligibility?.state ?: activity?.state)=="UPCOMING"
        root.text(R.id.sk_timer_title,if(mode==Screen.PENDING)"支付剩余时间" else if(awaitingStart)"距开抢" else "距结束")
        root.text(R.id.sk_timer_caption,if(mode==Screen.PENDING)"超时自动释放库存" else activity?.let {date(if(awaitingStart)it.startsAt else it.endsAt)+(if(awaitingStart)" 开抢" else " 结束")} ?: "正在核对服务器时间")
        root.findViewById<ImageView>(R.id.sk_notice_icon).setImageResource(if(mode==Screen.ENDED)R.drawable.sk_ffbf3 else R.drawable.sk_20d59)
        root.text(R.id.sk_notice_title,when(mode){Screen.SOLD_OUT->"还有机会，再来看看";Screen.ENDED->"本场秒杀已结束";else->if(awaitingStart)"正在同步开抢状态" else "正在同步活动状态"})
        root.text(R.id.sk_notice_body,when(mode){Screen.SOLD_OUT->"未支付订单超时后，库存可能重新释放。可刷新查看。";Screen.ENDED->"感谢关注，看看店内其他好券吧。";else->"倒计时已结束，正在向服务器核对库存和购买资格。"})
        val facts=when(mode){Screen.PENDING->"购买数量　${order?.quantity} 份\n待支付金额　${order?.totalCents?.let(::seckillMoney)}\n订单编号　${order?.orderId}";Screen.CLOSED->"订单状态　超时关闭\n支付情况　未支付\n订单编号　${order?.orderId}";Screen.LIMIT->"活动限购　${activity?.limit ?: "待核对"} 份\n本人剩余额度　${s.eligibility?.remaining ?: "待核对"}";Screen.UNKNOWN->"当前状态　提交结果待核查\n查询范围　本次原请求";Screen.OFFLINE->"当前操作已暂停，重新连接后核对服务器状态";else->""}
        root.text(R.id.sk_facts,facts);root.findViewById<View>(R.id.sk_facts).isVisible=facts.isNotBlank() && mode!=Screen.PENDING
        val note=when(mode){Screen.PENDING->"每次订单预留5分钟。离开页面不会延长支付期限。";Screen.PAID->"团购券须在规定使用日期内使用。";Screen.CLOSED->"如活动仍在进行且有库存，可返回活动重新抢购。";Screen.LIMIT->"已支付后退款不会恢复本次活动的购买额度。";Screen.ENDED->"已成功预留的订单仍可在自己的支付期限内付款。";Screen.OFFLINE->"恢复联网只更新状态，不会自动下单或支付。";Screen.UNKNOWN->"查询完成前，请勿重复提交。系统不会自动创建新订单。";Screen.SYNCING->"服务器确认后才会开放抢购，请稍候。";else->""}
        root.text(R.id.sk_note,note);root.findViewById<View>(R.id.sk_note).isVisible=note.isNotBlank()
        root.text(R.id.sk_secondary,if(mode in setOf(Screen.LIMIT,Screen.ENDED))"查看我的订单" else if(mode==Screen.OFFLINE)"重新连接" else if(mode==Screen.PENDING)"刷新订单状态" else "刷新库存")
        root.findViewById<View>(R.id.sk_secondary).isVisible=mode in setOf(Screen.SOLD_OUT,Screen.LIMIT,Screen.ENDED,Screen.OFFLINE) || (mode==Screen.PENDING && !s.synchronized)
        root.findViewById<View>(R.id.sk_secondary).isEnabled=!s.busy
        root.text(R.id.sk_hint,when(mode){Screen.LIVE->"点击后确认数量与金额，再提交订单";Screen.PENDING->"本次为模拟支付，不会产生真实扣款";Screen.UPCOMING->"到点后按钮自动开放，请手动抢购";Screen.UNKNOWN->"只查询本次请求，不会重复下单";else->"以服务器实时状态为准"})
        // 活动按钮同时展示权威套餐价格；已建单页面继续使用不可变订单金额，避免混淆两种价格来源。
        root.text(R.id.sk_primary,when(mode){Screen.UPCOMING->"即将开抢";Screen.LIVE->"立即抢购 ${deal?.priceCents?.let(::seckillMoney).orEmpty()}";Screen.PENDING->"模拟支付 ${order?.totalCents?.let(::seckillMoney) ?: ""}";Screen.PAID->"查看团购券";Screen.CLOSED->"返回活动";Screen.SOLD_OUT->"已抢光";Screen.LIMIT->"已达限购";Screen.ENDED->"活动已结束";Screen.OFFLINE->"联网后抢购";Screen.UNKNOWN->"查询本次结果";Screen.OTHER_ORDER->"查看订单详情";else->"正在同步"})
        root.findViewById<View>(R.id.sk_primary).isEnabled=!s.busy && (mode in setOf(Screen.LIVE,Screen.PENDING) && s.synchronized || mode in setOf(Screen.UNKNOWN,Screen.PAID,Screen.CLOSED,Screen.OTHER_ORDER))
        root.text(R.id.sk_error,s.error?.let(::displayError).orEmpty());root.findViewById<View>(R.id.sk_error).isVisible=s.error!=null
        if(s.quote!=null && sheet==null)showSheet()
        if(s.order!=null || s.unknown){sheet?.dismiss()} else sheetView?.let {renderSheet(it,s)}
        renderClock(root)
    }
    /** 时间仅按单调锚点格式化，不将归零改写成库存释放事实。 */
    private fun renderClock(root:View) {
        val s=flow.state.value;val mode=screen(s)
        val deadline=s.order?.paymentExpiresAt ?: s.activity?.let {if((s.eligibility?.state ?: it.state)=="UPCOMING")it.startsAt else it.endsAt}
        val remaining=deadline?.let {flow.clock.remainingMillis(it,SystemClock.elapsedRealtime())}
        val seconds=remaining?.let {(it+999)/1000}
        root.text(R.id.sk_hours,seconds?.let {(it/3600).toString().padStart(2,'0')} ?: "--")
        root.text(R.id.sk_minutes,seconds?.let {((it/60)%60).toString().padStart(2,'0')} ?: "--")
        root.text(R.id.sk_seconds,seconds?.let {(it%60).toString().padStart(2,'0')} ?: "--")
    }
    /** 真正模态确认覆盖详情；长规则可滚动，确认按钮固定，关闭不提交。 */
    private fun showSheet() {
        val dialog=BottomSheetDialog(requireContext());val content=layoutInflater.inflate(R.layout.sheet_seckill,null)
        sheet=dialog;sheetView=content;sheetImageKey=null
        dialog.setContentView(content)
        content.findViewById<View>(R.id.sk_sheet_close).setOnClickListener {if(!flow.state.value.busy)dialog.dismiss()}
        content.findViewById<View>(R.id.sk_minus).setOnClickListener {changeQuantity(-1)}
        content.findViewById<View>(R.id.sk_plus).setOnClickListener {changeQuantity(1)}
        content.findViewById<View>(R.id.sk_sheet_confirm).setOnClickListener {launchAction {if(flow.state.value.quote==null)flow.prepare(quantity) else flow.confirm()}}
        dialog.setOnDismissListener {SeckillImages.clear(content.findViewById(R.id.sk_summary));sheet=null;sheetView=null;flow.dismissQuote()}
        dialog.setOnShowListener {
            // 高度按709/852比例适配可用屏，遵循确认稿且不覆盖真实系统状态栏。
            dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
                // 必须重新提交布局参数触发测量；只改height字段会沿用旧展开位置，底部露出背景购买按钮。
                updateLayoutParams {height=(resources.displayMetrics.heightPixels*.83f).toInt()}
                setBackgroundResource(R.drawable.sk_bg_sheet)
            }
            dialog.behavior.apply {state=BottomSheetBehavior.STATE_EXPANDED;skipCollapsed=true}
        }
        dialog.show();renderSheet(content,flow.state.value)
    }
    /** 数量变化重新报价，旧金额和授权立即撤销；不在端上乘出确认总额。 */
    private fun changeQuantity(delta:Int) {
        val s=flow.state.value;val eligible=s.eligibility ?: return
        val maximum=minOf(eligible.available,eligible.remaining,s.activity?.limit ?: 0)
        if(s.busy || quantity+delta !in 1..maximum)return
        quantity+=delta;launchAction {flow.prepare(quantity)}
    }
    /** 确认区每次显示服务器报价；等待中显示明确状态而不保留旧总价为可提交。 */
    private fun renderSheet(root:View,s:SeckillState) {
        bindSummary(root.findViewById(R.id.sk_summary),s);s.activity?.let {bindInfo(root,it)}
        val deal=s.activity?.deal;val key="${deal?.imageUrl}:${s.activity?.version}"
        if(sheetImageKey!=key){sheetImageKey=key;SeckillImages.bind(root.findViewById(R.id.sk_summary),deal?.imageUrl,deal?.imageAlt,s.activity?.version?.toString())}
        root.text(R.id.sk_quantity,quantity.toString())
        root.text(R.id.sk_quantity_limit,"每人限购 ${s.activity?.limit ?: 0} 份")
        // 仅展示报价数量对应的原价/优惠分解；可提交金额直接使用服务器totalCents。
        val quote=s.quote?.value
        val original=if(quote!=null && deal!=null)deal.originalPriceCents.toLong()*quote.quantity else null
        fun amount(cents:Long)="¥"+java.math.BigDecimal.valueOf(cents,2).toPlainString()
        root.text(R.id.sk_sheet_original,original?.let {"商品原价　${amount(it)}"} ?: "商品原价　待核对")
        root.text(R.id.sk_sheet_discount,if(original!=null && quote!=null)"秒杀优惠　−${amount(original-quote.totalCents)}" else "秒杀优惠　待核对")
        root.text(R.id.sk_sheet_total,quote?.totalCents?.let {"应付金额　${seckillMoney(it)}"} ?: "正在核对报价")
        root.text(R.id.sk_sheet_error,s.error?.let(::displayError).orEmpty())
        // 固定操作始终携带当前权威报价，长须知滚动时也能核对即将提交的金额。
        root.text(R.id.sk_sheet_confirm,quote?.let {"确认抢购 ${seckillMoney(it.totalCents)}"} ?: if(s.busy)"正在核对" else "重新报价")
        root.findViewById<View>(R.id.sk_sheet_confirm).isEnabled=!s.busy && !s.unknown
        root.findViewById<View>(R.id.sk_minus).isEnabled=!s.busy && quantity>1
        root.findViewById<View>(R.id.sk_plus).isEnabled=!s.busy && quantity<minOf(s.eligibility?.available ?: 0,s.eligibility?.remaining ?: 0,s.activity?.limit ?: 0)
        root.findViewById<View>(R.id.sk_sheet_close).isEnabled=!s.busy;sheet?.setCancelable(!s.busy)
    }
    /** 三行事实来自后端；缺失规则明确说明未提供，不填设计示例。 */
    private fun bindInfo(root:View,a:SeckillActivity) {
        root.text(R.id.sk_store,a.deal.storeName ?: "门店信息待核对")
        root.text(R.id.sk_dates,"${a.deal.validFrom} 至 ${a.deal.validTo}")
        root.text(R.id.sk_rules,a.deal.usageRules ?: "使用规则暂未提供，请查看券详情")
    }
    /** 已有订单优先使用订单快照；活动仅为未下单摘要提供事实。 */
    private fun bindSummary(root:View,s:SeckillState) {
        val order=s.order?.value;val deal=s.activity?.deal
        root.text(R.id.sk_summary_title,order?.dealTitle ?: deal?.title.orEmpty())
        root.text(R.id.sk_summary_caption,order?.storeName ?: deal?.storeName.orEmpty())
        root.text(R.id.sk_summary_price,order?.totalCents?.let(::seckillMoney) ?: deal?.priceCents?.let(::seckillMoney).orEmpty())
        // 未下单摘要可以展示当前套餐原价；已下单缺少原价快照时隐藏，不能用后来改价覆盖订单历史。
        root.findViewById<TextView>(R.id.sk_summary_original).apply {
            isVisible=order==null && deal!=null
            text=deal?.originalPriceCents?.let(::seckillMoney).orEmpty()
            paintFlags=paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        }
    }
    /** 只在文字实际变化时赋值，避免每250毫秒重复触发布局测量。 */
    private fun View.text(id:Int,value:String) {findViewById<TextView>(id)?.let {if(it.text.toString()!=value)it.text=value}}
    /** 传输层异常只转换为用户可操作的提示；原请求状态仍由流程保留，不把英文套接字错误暴露在页面。 */
    private fun displayError(error:ApiError)=when(error) {
        is ApiError.Network->"连接未完成，请重新核查服务器状态"
        ApiError.Timeout->"请求超时，请查询本次结果"
        // 服务故障只提供可行动的中文提示，不把网关或后端诊断正文展示给买家。
        // 明确业务拒绝仍沿用后端说明，保留限购、活动变更等需要用户处理的原因。
        is ApiError.Http->if(error.code>=500)"服务暂时不可用，请稍后重新连接核查状态" else purchaseError(error)
        else->purchaseError(error)
    }
    /** 展示日期固定业务时区；业务资格仍由服务端UTC瞬时判定。 */
    private fun date(value:Instant)=DateTimeFormatter.ofPattern("MM月dd日 HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(value)
    /** 清理本页面图像和窗口；原请求记录保留用于再次进入后的只读恢复。 */
    override fun onDestroyView() {
        sheet?.dismiss();view?.findViewById<View>(R.id.sk_hero)?.let(SeckillImages::clear);view?.findViewById<View>(R.id.sk_summary)?.let(SeckillImages::clear)
        imageKey=null;summaryKey=null;super.onDestroyView()
    }
}
