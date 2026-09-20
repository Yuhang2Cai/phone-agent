package com.fandazi.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.fandazi.app.core.network.ApiResult
import com.fandazi.app.feature.ordering.Address
import com.fandazi.app.feature.ordering.OrderingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface AddressSubmitState {
    data object Idle : AddressSubmitState
    data object Submitting : AddressSubmitState
    data object Success : AddressSubmitState
    data object Failure : AddressSubmitState
}

class AddressEditViewModel(private val repository: OrderingRepository) : ViewModel() {
    private val mutableState = MutableStateFlow<AddressSubmitState>(AddressSubmitState.Idle)
    val state = mutableState.asStateFlow()

    fun save(address: Address) = submit {
        if (address.id > 0) repository.updateAddress(address) else repository.createAddress(address)
    }

    fun delete(addressId: Long) = submit { repository.deleteAddress(addressId) }

    fun consumeResult() {
        if (mutableState.value !is AddressSubmitState.Submitting) mutableState.value = AddressSubmitState.Idle
    }

    private fun submit(request: suspend () -> ApiResult<*>) {
        if (mutableState.value is AddressSubmitState.Submitting) return
        mutableState.value = AddressSubmitState.Submitting
        viewModelScope.launch {
            mutableState.value = when (request()) {
                is ApiResult.Success -> AddressSubmitState.Success
                is ApiResult.Failure -> AddressSubmitState.Failure
            }
        }
    }
}

class AddressEditViewModelFactory(private val repository: OrderingRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AddressEditViewModel(repository) as T
}
