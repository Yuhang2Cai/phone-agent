package com.fandazi.app.feature.merchant

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiError
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.ordering.Merchant
import com.fandazi.app.feature.ordering.OrderingRepository
import com.fandazi.app.feature.ordering.PagedResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MerchantFeedState(
    val items: List<Merchant> = emptyList(),
    val nextPage: Int = 0,
    val isInitialLoading: Boolean = false,
    val isAppending: Boolean = false,
    val endReached: Boolean = false,
    val initialError: String? = null,
    val appendError: String? = null
)

class MerchantFeedViewModel(
    private val repository: OrderingRepository,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate
) : ViewModel() {
    private val _state = MutableStateFlow(MerchantFeedState())
    val state = _state.asStateFlow()

    private var requestJob: Job? = null
    private var generation = 0L

// 加载initial。
    fun loadInitial() {
        generation++
        requestJob?.cancel()
        _state.value = MerchantFeedState()
        requestPage(page = 0, append = false, requestGeneration = generation)
    }

// 加载next。
    fun loadNext() {
        val current = state.value
        if (requestJob?.isActive == true || current.endReached || current.items.isEmpty() || current.appendError != null) return
        requestPage(page = current.nextPage, append = true, requestGeneration = generation)
    }

// 重试initial。
    fun retryInitial() {
        if (requestJob?.isActive == true || state.value.initialError == null) return
        requestPage(page = 0, append = false, requestGeneration = generation)
    }

// 重试append。
    fun retryAppend() {
        val current = state.value
        if (requestJob?.isActive == true || current.appendError == null || current.endReached) return
        requestPage(page = current.nextPage, append = true, requestGeneration = generation)
    }

// 处理 `requestPage` 相关逻辑。
    private fun requestPage(page: Int, append: Boolean, requestGeneration: Long) {
        _state.update { old ->
            if (append) old.copy(isAppending = true, appendError = null)
            else old.copy(isInitialLoading = true, initialError = null)
        }
        requestJob = viewModelScope.launch(dispatcher) {
            when (val result = repository.loadMerchants(page, 4, true)) {
                is ApiResult.Success -> if (requestGeneration == generation) accept(result.value, append)
                is ApiResult.Failure -> if (requestGeneration == generation) fail(result.error, append)
            }
        }
    }

// 处理 `accept` 相关逻辑。
    private fun accept(page: PagedResult<Merchant>, append: Boolean) {
        _state.update { old ->
            old.copy(
                items = (if (append) old.items + page.content else page.content).distinctBy(Merchant::id),
                nextPage = page.number + 1,
                isInitialLoading = false,
                isAppending = false,
                endReached = page.last,
                initialError = null,
                appendError = null
            )
        }
    }

// 处理 `fail` 相关逻辑。
    private fun fail(error: ApiError, append: Boolean) {
        val message = when (error) {
            is ApiError.Http -> error.message
            is ApiError.Network -> error.message
            ApiError.Timeout -> "请求超时，请稍后重试"
            ApiError.Storage -> "本地会话不可用"
        }
        _state.update { old ->
            if (append) old.copy(isAppending = false, appendError = message)
            else old.copy(isInitialLoading = false, initialError = message)
        }
    }
}

class MerchantFeedViewModelFactory(private val repository: OrderingRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = MerchantFeedViewModel(repository) as T
}
