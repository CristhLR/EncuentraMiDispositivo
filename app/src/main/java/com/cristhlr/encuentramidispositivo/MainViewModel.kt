package com.cristhlr.encuentramidispositivo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cristhlr.encuentramidispositivo.data.DeviceRepository
import com.cristhlr.encuentramidispositivo.model.Device
import com.cristhlr.encuentramidispositivo.model.FamilyGroup
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val email: String? = null,
    val currentUserUid: String = "",
    val group: FamilyGroup? = null,
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

    private val authListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        if (user == null) {
            _state.value = MainUiState(currentDeviceId = repository.currentDeviceId())
        } else {
            _state.update {
                it.copy(
                    email = user.email,
                    currentUserUid = user.uid,
                    loading = true,
                    error = null,
                )
            }
            viewModelScope.launch {
                runCatching { repository.registerCurrentDevice() }
                runCatching { refreshFromServer() }.onFailure { error ->
                    _state.update { it.copy(error = error.readableMessage(), loading = false) }
                }
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

    fun createFamilyGroup(name: String) = runAction(successMessage = "Grupo familiar creado") {
        repository.createFamilyGroup(name)
        runCatching { repository.registerCurrentDevice() }
        refreshFromServer()
    }

    fun joinFamilyGroup(code: String) = runAction(successMessage = "Te uniste al grupo familiar") {
        repository.joinFamilyGroup(code)
        runCatching { repository.registerCurrentDevice() }
        refreshFromServer()
    }

    fun refresh() = runAction {
        runCatching { repository.registerCurrentDevice() }
        refreshFromServer()
    }

    fun ring(device: Device) = runAction(successMessage = "Orden enviada a ${device.name}") {
        repository.ringDevice(device.id)
    }

    fun signOut() = repository.signOut()

    fun clearFeedback() = _state.update { it.copy(message = null, error = null) }

    private suspend fun refreshFromServer() {
        val family = repository.loadFamilyState()
        _state.update {
            it.copy(group = family.group, devices = family.devices, loading = false)
        }
    }

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
