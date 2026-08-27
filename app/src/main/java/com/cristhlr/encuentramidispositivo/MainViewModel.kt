package com.cristhlr.encuentramidispositivo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cristhlr.encuentramidispositivo.data.DeviceRepository
import com.cristhlr.encuentramidispositivo.model.Device
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val email: String? = null,
    val devices: List<Device> = emptyList(),
    val currentDeviceId: String = "",
    val loading: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class MainViewModel(private val repository: DeviceRepository) : ViewModel() {
    private val _state = MutableStateFlow(
        MainUiState(currentDeviceId = repository.currentDeviceId()),
    )
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var deviceListener: ListenerRegistration? = null
    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        deviceListener?.remove()
        val user = auth.currentUser
        if (user == null) {
            _state.value = MainUiState(currentDeviceId = repository.currentDeviceId())
        } else {
            _state.update { it.copy(email = user.email, loading = true, error = null) }
            deviceListener = repository.listenToDevices(
                userId = user.uid,
                onChange = { devices -> _state.update { it.copy(devices = devices, loading = false) } },
                onError = { error -> _state.update { it.copy(error = error.message, loading = false) } },
            )
            viewModelScope.launch {
                runCatching { repository.registerCurrentDevice() }
                    .onFailure { error -> _state.update { it.copy(error = error.readableMessage()) } }
            }
        }
    }

    init {
        repository.addAuthListener(authListener)
    }

    fun signIn(email: String, password: String) = runAction {
        repository.signIn(email, password)
    }

    fun createAccount(email: String, password: String) = runAction {
        repository.createAccount(email, password)
    }

    fun ring(device: Device) = runAction(successMessage = "Orden enviada a ${device.name}") {
        repository.ringDevice(device.id)
    }

    fun signOut() = repository.signOut()

    fun clearFeedback() = _state.update { it.copy(message = null, error = null) }

    private fun runAction(successMessage: String? = null, action: suspend () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, message = null) }
            runCatching { action() }
                .onSuccess { _state.update { it.copy(loading = false, message = successMessage) } }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.readableMessage()) }
                }
        }
    }

    override fun onCleared() {
        deviceListener?.remove()
        repository.removeAuthListener(authListener)
        super.onCleared()
    }

    class Factory(private val repository: DeviceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(repository) as T
    }
}

private fun Throwable.readableMessage(): String =
    localizedMessage ?: "Ocurrió un error. Revisa tu conexión e inténtalo de nuevo."

