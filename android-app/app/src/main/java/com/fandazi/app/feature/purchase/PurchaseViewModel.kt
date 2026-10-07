// 购买状态模块：依赖真实仓库管理报价、提交、支付与失败恢复，不保存导航金额。
package com.fandazi.app.feature.purchase

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** 页面保留类型化错误，401 进入登录，其他错误允许原地重试。 */
data class PurchaseUiState(val quote: PurchaseQuote? = null, val order: GroupBuyOrder? = null,
    val loading: Boolean = false, val error: ApiError? = null)

/** 点击入口同步置忙，阻止协程开始前的重复提交窗口。 */
class PurchaseViewModel(private val repository: PurchaseRepository) : ViewModel() {
    private val mutable = MutableStateFlow(PurchaseUiState())
    val state = mutable.asStateFlow()
    private val sessionId = "purchase-${UUID.randomUUID()}"
    private var orderKey = "purchase-${UUID.randomUUID()}"
    private val paymentKey = "payment-${UUID.randomUUID()}"

    /** 切换数量废弃旧报价，新确认使用该数量的后端金额和新幂等键。 */
    fun quote(dealId: String, quantity: Int) {
        if (mutable.value.loading) return
        mutable.value = PurchaseUiState(loading = true)
        orderKey = "purchase-${UUID.randomUUID()}"
        viewModelScope.launch {
            mutable.value = when (val result = repository.createQuote(dealId, quantity, sessionId)) {
                is ApiResult.Success -> PurchaseUiState(quote = result.value)
                is ApiResult.Failure -> PurchaseUiState(error = result.error)
            }
        }
    }

    /** 未取得有效报价不得提交；明确过期或价格变化清除旧授权并要求重新报价。 */
    fun createOrder() {
        val current = mutable.value
        val quote = current.quote ?: return
        if (current.loading || current.order != null) return
        if (!java.time.Instant.parse(quote.expiresAt).isAfter(java.time.Instant.now())) {
            mutable.value = PurchaseUiState(error = ApiError.Http(410, "报价已过期，请重新报价"))
            return
        }
        mutable.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            when (val result = repository.createOrder(quote, sessionId, orderKey)) {
                is ApiResult.Success -> mutable.value = current.copy(order = result.value)
                is ApiResult.Failure -> {
                    // 明确业务拒绝要求重新授权；网络未知结果保留原键，由后端重放同一订单。
                    val http = result.error as? ApiError.Http
                    mutable.value = current.copy(quote = if (http != null && http.code != 503) null else quote,
                        loading = false, error = result.error)
                }
            }
        }
    }

    /** 按身份回读订单，支付页与成功页不由导航参数伪造金额或状态。 */
    fun loadOrder(orderId: String) {
        if (mutable.value.loading) return
        mutable.value = PurchaseUiState(loading = true)
        viewModelScope.launch {
            mutable.value = when (val result = repository.getOrder(orderId)) {
                is ApiResult.Success -> PurchaseUiState(order = result.value)
                is ApiResult.Failure -> PurchaseUiState(error = result.error)
            }
        }
    }

    /** 只支付本人待支付订单，重复点击及网络重试保持同一键。 */
    fun mockPay() {
        val current = mutable.value
        val order = current.order ?: return
        if (current.loading || order.status != "PENDING_PAYMENT") return
        mutable.value = current.copy(loading = true, error = null)
        viewModelScope.launch {
            mutable.value = when (val result = repository.mockPay(order, paymentKey)) {
                is ApiResult.Success -> current.copy(order = result.value)
                is ApiResult.Failure -> current.copy(error = result.error)
            }
        }
    }
}

/** 将共享后端仓库注入页面状态，不创建第二套客户端。 */
class PurchaseViewModelFactory(private val repository: PurchaseRepository) : ViewModelProvider.Factory {
    /** 调用方按购买 ViewModel 类型获取状态机。 */
    override fun <T : ViewModel> create(modelClass: Class<T>): T = PurchaseViewModel(repository) as T
}

/** 展示可理解的错误消息，不打印内部错误对象或英文状态。 */
fun purchaseError(error: ApiError): String = when (error) {
    is ApiError.Http -> error.message
    is ApiError.Network -> error.message
    ApiError.Timeout -> "请求超时，请重试"
    ApiError.Storage -> "账号状态读取失败，请重新登录"
}
