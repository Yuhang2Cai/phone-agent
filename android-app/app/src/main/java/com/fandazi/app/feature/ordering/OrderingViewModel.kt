package com.fandazi.app.feature.ordering

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class OrderingUiState(
    val keyword: String = "",
    val merchants: List<Merchant> = emptyList(),
    val selectedMerchant: Merchant? = null,
    val menu: List<Dish> = emptyList(),
    val selectedDish: Dish? = null,
    val selectedOptions: Map<Long, List<DishOption>> = emptyMap(),
    val cartDraft: CartDraft? = null,
    val quote: CartQuote? = null,
    val addresses: List<Address> = emptyList(),
    val selectedAddress: Address? = null,
    val orders: List<Order> = emptyList(),
    val currentOrder: Order? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class OrderingViewModel(
    private val repository: OrderingRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val _uiState = MutableStateFlow(OrderingUiState())
    val uiState = _uiState.asStateFlow()

    fun search(keyword: String) {
        _uiState.update { it.copy(keyword = keyword, isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.searchMerchants(keyword)) {
                is ApiResult.Success -> _uiState.update { it.copy(merchants = result.value, isLoading = false) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    fun openMerchant(merchantId: Long) {
        val merchant = _uiState.value.merchants.firstOrNull { it.id == merchantId }
        _uiState.update { it.copy(selectedMerchant = merchant, isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) {
            when (val result = repository.loadMenu(merchantId)) {
                is ApiResult.Success -> _uiState.update { it.copy(menu = result.value, selectedDish = result.value.firstOrNull(), isLoading = false) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    fun selectDish(dishId: Long) = _uiState.update { state -> state.copy(selectedDish = state.menu.firstOrNull { it.id == dishId }) }

    fun selectOption(dishId: Long, optionId: Long) = _uiState.update { state ->
        val option = state.menu.firstOrNull { it.id == dishId }?.options?.firstOrNull { it.id == optionId } ?: return@update state
        state.copy(selectedOptions = state.selectedOptions + (dishId to listOf(option)))
    }

    fun addSelectedDish(quantity: Int = 1) {
        val state = _uiState.value
        val merchantId = state.selectedMerchant?.id ?: state.selectedDish?.merchantId ?: return
        val dish = state.selectedDish ?: return
        val selectedOptions = state.selectedOptions[dish.id].orEmpty()
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(dispatcher) {
            val draft = state.cartDraft ?: when (val created = repository.createCartDraft(merchantId)) {
                is ApiResult.Success -> created.value
                is ApiResult.Failure -> {
                    showError(created.error)
                    return@launch
                }
            }
            val inputs = draft.items.map { CartItemInput(it.dishId, it.quantity, it.optionIds) } +
                CartItemInput(dish.id, quantity, selectedOptions.map { it.id })
            when (val updated = repository.updateCart(draft.id, inputs)) {
                is ApiResult.Success -> {
                    val quote = repository.validateCart(updated.value.id)
                    _uiState.update {
                        it.copy(
                            cartDraft = updated.value,
                            quote = (quote as? ApiResult.Success)?.value,
                            isLoading = false
                        )
                    }
                }
                is ApiResult.Failure -> showError(updated.error)
            }
        }
    }

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

    fun createOrder() {
        val cartId = _uiState.value.cartDraft?.id ?: return
        val addressId = _uiState.value.selectedAddress?.id ?: return
        viewModelScope.launch(dispatcher) {
            when (val result = repository.createOrder(cartId, addressId, "android-order-${UUID.randomUUID()}")) {
                is ApiResult.Success -> _uiState.update { it.copy(currentOrder = result.value, error = null) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    fun payOrder() {
        val orderId = _uiState.value.currentOrder?.id ?: return
        viewModelScope.launch(dispatcher) {
            when (val result = repository.payOrder(orderId, "android-pay-${UUID.randomUUID()}")) {
                is ApiResult.Success -> _uiState.update { it.copy(currentOrder = result.value, error = null) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    fun refreshOrders() {
        viewModelScope.launch(dispatcher) {
            when (val result = repository.loadOrders()) {
                is ApiResult.Success -> _uiState.update { it.copy(orders = result.value, currentOrder = result.value.firstOrNull() ?: it.currentOrder, error = null) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    fun selectOrder(orderId: Long) {
        viewModelScope.launch(dispatcher) {
            when (val result = repository.loadOrder(orderId)) {
                is ApiResult.Success -> _uiState.update { it.copy(currentOrder = result.value, error = null) }
                is ApiResult.Failure -> showError(result.error)
            }
        }
    }

    private fun showError(error: ApiError) = _uiState.update {
        it.copy(isLoading = false, error = when (error) {
            is ApiError.Http -> error.message
            is ApiError.Network -> error.message
            ApiError.Timeout -> "请求超时，请稍后重试"
            ApiError.Storage -> "本地会话不可用"
        })
    }
}

class OrderingViewModelFactory(private val repository: OrderingRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = OrderingViewModel(repository) as T
}
