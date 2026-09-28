// 下单流程模块：协调界面状态与业务请求。
package com.fandazi.app.feature.ordering

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class SearchStatus { Idle, Searching, Results, Empty, NetworkError, AuthRequired }

sealed interface MerchantNavigationEvent {
    data class OpenDetail(val merchantId: Long) : MerchantNavigationEvent
}

data class OrderingUiState(
    val keyword: String = "",
    val merchants: List<Merchant> = emptyList(),
    val selectedMerchant: Merchant? = null,
    val menu: List<Dish> = emptyList(),
    val selectedDish: Dish? = null,
    val selectedOptions: Map<Long, List<DishOption>> = emptyMap(),
    val cartDraft: CartDraft? = null,
    val quote: CartQuote? = null,
    val cartLines: List<CartLine> = emptyList(),
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    val orders: List<Order> = emptyList(),
    val currentOrder: Order? = null,
    val pendingMerchantSwitch: Merchant? = null,
    val checkoutReady: Boolean = false,
    val isSubmittingOrder: Boolean = false,
    val isLoading: Boolean = false,
    val searchStatus: SearchStatus = SearchStatus.Idle,
    val onlyOpen: Boolean = false,
    val error: String? = null
)

@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class OrderingViewModel(
    private val repository: OrderingRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val cartStore: CartStateStore = NoOpCartStateStore,
    private val cartOwner: String = "guest"
) : ViewModel() {
    private var merchantGeneration = 0L
    private data class PendingNavigation(val generation: Long, val event: MerchantNavigationEvent)
    private val _navigationEvents = MutableSharedFlow<PendingNavigation>()
    val navigationEvents: SharedFlow<MerchantNavigationEvent> = object : SharedFlow<MerchantNavigationEvent> {
        // 内部流不重放，包装层也不保存历史导航。
        override val replayCache: List<MerchantNavigationEvent> get() = emptyList()

        override suspend fun collect(collector: FlowCollector<MerchantNavigationEvent>): Nothing {
            _navigationEvents.collect { navigation ->
                // 检查必须紧贴订阅者调用：emit 等待期间，新请求、取消或用户切换都可能使事件过期。
                if (navigation.generation == merchantGeneration) collector.emit(navigation.event)
            }
        }
    }
    private var orderRequestKey: String? = null
    private var paymentRequestKey: String? = null
    private var activeOrderUsesCurrentCart = false
    private var activeCartOwner = cartOwner
    private val restored = cartStore.read(activeCartOwner)
    private val _uiState = MutableStateFlow(
        OrderingUiState(
            selectedMerchant = restored?.merchant,
            cartDraft = restored?.draft,
            quote = restored?.quote,
            cartLines = restored?.lines ?: restored?.quote?.lines.orEmpty(),
            currentOrder = restored?.currentOrder
        )
    )
    init {
        orderRequestKey = restored?.orderRequestKey
        paymentRequestKey = restored?.paymentRequestKey
        activeOrderUsesCurrentCart = restored?.activeOrderUsesCurrentCart ?: false
    }
    val uiState = _uiState.asStateFlow()

// 更新search、keyword。
    fun updateSearchKeyword(keyword: String) {
        if (_uiState.value.keyword != keyword) _uiState.update { it.copy(keyword = keyword) }
    }

// 处理 `switchCartOwner` 相关逻辑。
    fun switchCartOwner(owner: String) {
        if (owner == activeCartOwner) return
        ++merchantGeneration
        activeCartOwner = owner
        val snapshot = cartStore.read(owner)
        orderRequestKey = snapshot?.orderRequestKey
        paymentRequestKey = snapshot?.paymentRequestKey
        activeOrderUsesCurrentCart = snapshot?.activeOrderUsesCurrentCart ?: false
        _uiState.update {
            it.copy(
                selectedMerchant = snapshot?.merchant,
                menu = emptyList(),
                selectedDish = null,
                selectedOptions = emptyMap(),
                cartDraft = snapshot?.draft,
                quote = snapshot?.quote,
                cartLines = snapshot?.lines ?: snapshot?.quote?.lines.orEmpty(),
                selectedAddress = null,
                currentOrder = snapshot?.currentOrder,
                pendingMerchantSwitch = null,
                checkoutReady = false,
                isSubmittingOrder = false,
                isLoading = false,
                error = null
            )
        }
    }

// 搜索当前数据。
    fun search(keyword: String) {
        _uiState.update { it.copy(keyword = keyword, isLoading = true, searchStatus = SearchStatus.Searching, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.searchMerchants(keyword)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(merchants = result.value, isLoading = false, searchStatus = if (result.value.isEmpty()) SearchStatus.Empty else SearchStatus.Results)
                }
                is ApiResult.Failure -> showSearchError(result.error)
            }
        }
    }

// 处理 `toggleOnlyOpen` 相关逻辑。
    fun toggleOnlyOpen() {
        _uiState.update { it.copy(onlyOpen = !it.onlyOpen) }
    }

// 打开商家。
    fun openMerchant(merchantId: Long) {
        val request = ++merchantGeneration
        _uiState.update {
            it.copy(menu = emptyList(), selectedDish = null, pendingMerchantSwitch = null, isLoading = true, error = null)
        }
        viewModelScope.launch(dispatcher) {
            if (request != merchantGeneration) return@launch
            when (val result = repository.loadMerchant(merchantId)) {
                is ApiResult.Success -> prepareMerchant(result.value, request)
                is ApiResult.Failure -> if (request == merchantGeneration) {
                    _uiState.update { it.copy(selectedMerchant = null) }
                    showError(result.error)
                }
            }
        }
    }

    // 旧弹窗回调没有待确认目标时直接忽略，不能使新打开请求失效或结束其加载状态。
    fun confirmMerchantSwitch() {
        val target = _uiState.value.pendingMerchantSwitch ?: return
        val request = ++merchantGeneration
        clearCart()
        _uiState.update { it.copy(pendingMerchantSwitch = null, isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) { prepareMerchant(target, request) }
    }

// 取消商家、switch。
    fun cancelMerchantSwitch() {
        ++merchantGeneration
        _uiState.update { it.copy(pendingMerchantSwitch = null, isLoading = false) }
    }

// 加载商家。
    private suspend fun prepareMerchant(merchant: Merchant, request: Long) {
        if (request != merchantGeneration) return
        if (_uiState.value.cartDraft?.merchantId?.let { it != merchant.id } == true) {
            _uiState.update { it.copy(pendingMerchantSwitch = merchant, isLoading = false) }
            return
        }
        _uiState.update { it.copy(selectedMerchant = merchant, pendingMerchantSwitch = null) }
        if (request != merchantGeneration) return
        _navigationEvents.emit(PendingNavigation(request, MerchantNavigationEvent.OpenDetail(merchant.id)))
        loadMenuFor(merchant.id, request)
    }

    private suspend fun loadMenuFor(merchantId: Long, request: Long) {
        if (request != merchantGeneration) return
        when (val result = repository.loadMenu(merchantId)) {
            is ApiResult.Success -> if (request == merchantGeneration) {
                _uiState.update { it.copy(menu = result.value, selectedDish = result.value.firstOrNull(), isLoading = false) }
            }
            is ApiResult.Failure -> if (request == merchantGeneration) showError(result.error)
        }
    }

// 处理 `selectDish` 相关逻辑。
    fun selectDish(dishId: Long) = _uiState.update { state -> state.copy(selectedDish = state.menu.firstOrNull { it.id == dishId }) }

// 处理 `selectOption` 相关逻辑。
    fun selectOption(dishId: Long, optionId: Long) = _uiState.update { state ->
        val option = state.menu.firstOrNull { it.id == dishId }?.options?.firstOrNull { it.id == optionId } ?: return@update state
        if (!option.available) return@update state
        val existing = state.selectedOptions[dishId].orEmpty().filterNot { it.groupName == option.groupName }
        state.copy(selectedOptions = state.selectedOptions + (dishId to (existing + option)))
    }

// 处理 `addSelectedDish` 相关逻辑。
    fun addSelectedDish(quantity: Int = 1) {
        val state = _uiState.value
        if (state.isLoading) return
        val merchantId = state.selectedMerchant?.id ?: state.selectedDish?.merchantId ?: return
        val dish = state.selectedDish ?: return
        val selectedOptions = state.selectedOptions[dish.id].orEmpty()
        orderRequestKey = null
        paymentRequestKey = null
        activeOrderUsesCurrentCart = false
        _uiState.update { it.copy(isLoading = true, currentOrder = null, error = null) }
        viewModelScope.launch(dispatcher) {
            val draft = state.cartDraft ?: when (val created = repository.createCartDraft(merchantId)) {
                is ApiResult.Success -> created.value
                is ApiResult.Failure -> {
                    showError(created.error)
                    return@launch
                }
            }
            val optionIds = selectedOptions.map { it.id }.sorted()
            val inputs = draft.items.map { CartItemInput(it.dishId, it.quantity, it.optionIds) }.toMutableList()
            val existingIndex = inputs.indexOfFirst { it.dishId == dish.id && it.optionIds.sorted() == optionIds }
            if (existingIndex >= 0) inputs[existingIndex] = inputs[existingIndex].copy(quantity = inputs[existingIndex].quantity + quantity)
            else inputs += CartItemInput(dish.id, quantity, optionIds)
            when (val updated = repository.updateCart(draft.id, inputs)) {
                is ApiResult.Success -> {
                    when (val quote = repository.validateCart(updated.value.id)) {
                        is ApiResult.Success -> _uiState.update {
                            it.copy(cartDraft = updated.value, quote = quote.value, cartLines = quote.value.lines, isLoading = false)
                        }
                        is ApiResult.Failure -> {
                            _uiState.update { current -> current.copy(cartDraft = updated.value, quote = null, cartLines = displayLines(updated.value, current)) }
                            showError(quote.error)
                        }
                    }
                    persistCart()
                }
                is ApiResult.Failure -> showError(updated.error)
            }
        }
    }

// 更新购物车、line。
    fun updateCartLine(dishId: Long, optionIds: List<Long>, quantity: Int) {
        if (_uiState.value.isLoading) return
        val draft = _uiState.value.cartDraft ?: return
        val inputs = draft.items.map { CartItemInput(it.dishId, it.quantity, it.optionIds) }.toMutableList()
        val index = inputs.indexOfFirst { it.dishId == dishId && it.optionIds.sorted() == optionIds.sorted() }
        if (index < 0) return
        if (quantity <= 0) inputs.removeAt(index) else inputs[index] = inputs[index].copy(quantity = quantity.coerceAtMost(100))
        if (inputs.isEmpty()) {
            clearCart()
            return
        }
        orderRequestKey = null
        paymentRequestKey = null
        activeOrderUsesCurrentCart = false
        _uiState.update { it.copy(currentOrder = null) }
        syncCart(draft.id, inputs)
    }

// 清除购物车。
    fun clearCart() {
        cartStore.clear(activeCartOwner)
        _uiState.update { it.copy(cartDraft = null, quote = null, cartLines = emptyList(), checkoutReady = false, error = null) }
        if (_uiState.value.currentOrder != null) persistCart()
    }

// 准备checkout。
    fun prepareCheckout() {
        if (_uiState.value.isLoading) return
        val draftId = _uiState.value.cartDraft?.id ?: return
        _uiState.update { it.copy(isLoading = true, checkoutReady = false, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val quote = repository.validateCart(draftId)) {
                is ApiResult.Failure -> showError(quote.error)
                is ApiResult.Success -> when (val addresses = repository.loadAddresses()) {
                    is ApiResult.Failure -> showError(addresses.error)
                    is ApiResult.Success -> {
                        val selected = addresses.value.firstOrNull { it.defaultAddress && it.valid }
                            ?: addresses.value.firstOrNull { it.valid }
                        _uiState.update {
                            it.copy(
                                quote = quote.value,
                                cartLines = quote.value.lines,
                                addresses = addresses.value,
                                selectedAddress = selected,
                                isLoading = false,
                                checkoutReady = selected != null,
                                error = if (selected == null) "请先添加可用的收货地址" else null
                            )
                        }
                        persistCart()
                    }
                }
            }
        }
    }

// 处理 `consumeCheckoutReady` 相关逻辑。
    fun consumeCheckoutReady() = _uiState.update { it.copy(checkoutReady = false) }

// 处理 `syncCart` 相关逻辑。
    private fun syncCart(draftId: Long, inputs: List<CartItemInput>) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val updated = repository.updateCart(draftId, inputs)) {
                is ApiResult.Failure -> showError(updated.error)
                is ApiResult.Success -> when (val quote = repository.validateCart(draftId)) {
                    is ApiResult.Failure -> {
                        _uiState.update { current -> current.copy(cartDraft = updated.value, quote = null, cartLines = displayLines(updated.value, current)) }
                        showError(quote.error)
                        persistCart()
                    }
                    is ApiResult.Success -> {
                        _uiState.update { it.copy(cartDraft = updated.value, quote = quote.value, cartLines = quote.value.lines, isLoading = false) }
                        persistCart()
                    }
                }
            }
        }
    }

// 处理 `persistCart` 相关逻辑。
    private fun persistCart() {
        val state = _uiState.value
        cartStore.write(
            CartSnapshot(
                activeCartOwner,
                state.selectedMerchant,
                state.cartDraft,
                state.quote,
                state.cartLines,
                state.currentOrder,
                orderRequestKey,
                paymentRequestKey,
                activeOrderUsesCurrentCart
            )
        )
    }

// 处理 `displayLines` 相关逻辑。
    private fun displayLines(draft: CartDraft, state: OrderingUiState): List<CartLine> = draft.items.map { raw ->
        val previous = state.cartLines.firstOrNull { it.dishId == raw.dishId && it.optionIds.sorted() == raw.optionIds.sorted() }
        val dish = state.menu.firstOrNull { it.id == raw.dishId }
        val options = dish?.options.orEmpty().filter { it.id in raw.optionIds }
        CartLine(
            dishId = raw.dishId,
            dishName = raw.dishName.ifBlank { previous?.dishName ?: dish?.name.orEmpty() },
            quantity = raw.quantity,
            optionIds = raw.optionIds,
            optionSummary = raw.optionSummary.ifBlank { previous?.optionSummary ?: options.joinToString(",") { "${it.groupName}:${it.optionName}" } },
            unitPrice = if (raw.unitPrice.signum() > 0) raw.unitPrice else previous?.unitPrice
                ?: dish?.price?.add(options.fold(java.math.BigDecimal.ZERO) { total, option -> total + option.priceDelta })
                ?: java.math.BigDecimal.ZERO
        )
    }

// 处理 `selectDefaultAddress` 相关逻辑。
    fun selectDefaultAddress() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.loadAddresses()) {
                is ApiResult.Success -> _uiState.update {
                    val selected = result.value.firstOrNull { address -> address.defaultAddress && address.valid } ?: result.value.firstOrNull { address -> address.valid }
                    it.copy(addresses = result.value, selectedAddress = selected, isLoading = false)
                }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 创建订单。
    fun createOrder() {
        if (_uiState.value.isSubmittingOrder) return
        val cartId = _uiState.value.cartDraft?.id ?: return
        val addressId = _uiState.value.selectedAddress?.id ?: return
        _uiState.update { it.copy(isSubmittingOrder = true, error = null) }
        val requestKey = orderRequestKey ?: "android-order-${UUID.randomUUID()}".also { orderRequestKey = it }
        persistCart()
        viewModelScope.launch(dispatcher) {
            when (val result = repository.createOrder(cartId, addressId, requestKey)) {
                is ApiResult.Success -> {
                    activeOrderUsesCurrentCart = true
                    _uiState.update { it.copy(currentOrder = result.value, isSubmittingOrder = false, error = null) }
                    persistCart()
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(isSubmittingOrder = false) }
                    showError(result.error)
                }
            }
        }
    }

// 支付订单。
    fun payOrder() {
        if (_uiState.value.isSubmittingOrder) return
        val orderId = _uiState.value.currentOrder?.id ?: return
        _uiState.update { it.copy(isSubmittingOrder = true, error = null) }
        val requestKey = paymentRequestKey ?: "android-pay-${UUID.randomUUID()}".also { paymentRequestKey = it }
        persistCart()
        viewModelScope.launch(dispatcher) {
            when (val result = repository.payOrder(orderId, requestKey)) {
                is ApiResult.Success -> {
                    if (activeOrderUsesCurrentCart) {
                        _uiState.update { it.copy(currentOrder = result.value, cartDraft = null, quote = null, cartLines = emptyList(), isSubmittingOrder = false, error = null) }
                    } else {
                        _uiState.update { it.copy(currentOrder = result.value, isSubmittingOrder = false, error = null) }
                    }
                    activeOrderUsesCurrentCart = false
                    orderRequestKey = null
                    paymentRequestKey = null
                    persistCart()
                }
                is ApiResult.Failure -> {
                    _uiState.update { it.copy(isSubmittingOrder = false) }
                    showError(result.error)
                }
            }
        }
    }

// 处理 `refreshOrders` 相关逻辑。
    fun refreshOrders() {
        viewModelScope.launch(dispatcher) {
            when (val result = repository.loadOrders()) {
                is ApiResult.Success -> _uiState.update {
                    val selected = it.currentOrder?.let { current -> result.value.firstOrNull { order -> order.id == current.id } }
                        ?: result.value.firstOrNull()
                    it.copy(orders = result.value, currentOrder = selected ?: it.currentOrder, error = null)
                }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 处理 `selectOrder` 相关逻辑。
    fun selectOrder(orderId: Long) {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.loadOrder(orderId)) {
                is ApiResult.Success -> _uiState.update { it.copy(currentOrder = result.value, isLoading = false, error = null) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

// 处理 `selectOrderSnapshot` 相关逻辑。
    fun selectOrderSnapshot(order: Order) {
        paymentRequestKey = null
        activeOrderUsesCurrentCart = false
        _uiState.update { it.copy(currentOrder = order, selectedAddress = Address(0, order.recipient, order.phone, order.detail, true, true), error = null) }
        persistCart()
    }

// 处理 `showError` 相关逻辑。
    private fun showError(error: ApiError) = _uiState.update {
        it.copy(isLoading = false, error = when (error) {
            is ApiError.Http -> error.message
            is ApiError.Network -> error.message
            ApiError.Timeout -> "请求超时，请稍后重试"
            ApiError.Storage -> "本地会话不可用"
        })
    }

// 处理 `showSearchError` 相关逻辑。
    private fun showSearchError(error: ApiError) = _uiState.update {
        it.copy(
            isLoading = false,
            searchStatus = when (error) {
                is ApiError.Http -> if (error.code == 401 || error.code == 403) SearchStatus.AuthRequired else SearchStatus.NetworkError
                else -> SearchStatus.NetworkError
            },
            error = when (error) {
                is ApiError.Http -> error.message
                is ApiError.Network -> error.message
                ApiError.Timeout -> "请求超时，请稍后重试"
                ApiError.Storage -> "本地会话不可用"
            }
        )
    }
}

class OrderingViewModelFactory(
    private val repository: OrderingRepository,
    private val cartStore: CartStateStore = NoOpCartStateStore,
    private val cartOwner: String = "guest"
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = OrderingViewModel(repository, cartStore = cartStore, cartOwner = cartOwner) as T
}
