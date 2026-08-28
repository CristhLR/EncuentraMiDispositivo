package com.cristhlr.encuentramidispositivo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cristhlr.encuentramidispositivo.MainUiState
import com.cristhlr.encuentramidispositivo.MainViewModel
import com.cristhlr.encuentramidispositivo.model.Device
import java.text.DateFormat
import java.util.Date

@Composable
fun AppScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.message, state.error) {
        (state.error ?: state.message)?.let {
            snackbar.showSnackbar(it)
            viewModel.clearFeedback()
        }
    }

    if (state.email == null) {
        AuthScreen(
            loading = state.loading,
            snackbar = snackbar,
            onSignIn = viewModel::signIn,
            onRegister = viewModel::createAccount,
        )
    } else if (state.group == null) {
        FamilySetupScreen(
            state = state,
            snackbar = snackbar,
            onCreateGroup = viewModel::createFamilyGroup,
            onJoinGroup = viewModel::joinFamilyGroup,
            onRefresh = viewModel::refresh,
            onSignOut = viewModel::signOut,
        )
    } else {
        DevicesScreen(
            state = state,
            snackbar = snackbar,
            onRing = viewModel::ring,
            onRefresh = viewModel::refresh,
            onSignOut = viewModel::signOut,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthScreen(
    loading: Boolean,
    snackbar: SnackbarHostState,
    onSignIn: (String, String) -> Unit,
    onRegister: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Encuentra mi dispositivo", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Cada persona usa su propia cuenta y se une al mismo grupo familiar.",
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Correo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Contraseña") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    if (registering) onRegister(email, password) else onSignIn(email, password)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && email.isNotBlank() && password.length >= 6,
            ) {
                if (loading) CircularProgressIndicator() else Text(if (registering) "Crear cuenta" else "Entrar")
            }
            TextButton(
                onClick = { registering = !registering },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (registering) "Ya tengo una cuenta" else "Crear una cuenta")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FamilySetupScreen(
    state: MainUiState,
    snackbar: SnackbarHostState,
    onCreateGroup: (String) -> Unit,
    onJoinGroup: (String) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    var groupName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grupo familiar") },
                actions = { TextButton(onClick = onSignOut) { Text("Salir") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Conecta a tu familia", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Una persona crea el grupo y comparte el código. Los demás pueden entrar usando cuentas diferentes.",
                modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
            )
            OutlinedTextField(
                value = groupName,
                onValueChange = { groupName = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Nombre del grupo") },
                placeholder = { Text("Familia León") },
                singleLine = true,
            )
            Button(
                onClick = { onCreateGroup(groupName) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = !state.loading && groupName.trim().length >= 2,
            ) {
                Text("Crear grupo familiar")
            }
            Text("o", modifier = Modifier.align(Alignment.CenterHorizontally).padding(18.dp))
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.uppercase().take(8) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Código de invitación") },
                singleLine = true,
            )
            OutlinedButton(
                onClick = { onJoinGroup(inviteCode) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                enabled = !state.loading && inviteCode.length == 8,
            ) {
                Text("Unirme al grupo")
            }
            TextButton(
                onClick = onRefresh,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp),
                enabled = !state.loading,
            ) {
                Text("Ya tengo un grupo: cargarlo")
            }
            if (state.loading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DevicesScreen(
    state: MainUiState,
    snackbar: SnackbarHostState,
    onRing: (Device) -> Unit,
    onRefresh: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.group?.name ?: "Grupo familiar") },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Actualizar") }
                    TextButton(onClick = onSignOut) { Text("Salir") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Cuenta: ${state.email}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Código para invitar: ${state.group?.inviteCode}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "El teléfono debe estar encendido, conectado a Internet y haber abierto la app al menos una vez.",
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (state.loading && state.devices.isEmpty()) {
                item { CircularProgressIndicator() }
            } else if (state.devices.isEmpty()) {
                item { Text("Aún no hay dispositivos registrados.") }
            }

            items(state.devices, key = { it.id }) { device ->
                DeviceCard(
                    device = device,
                    isCurrent = device.ownerUid == state.currentUserUid &&
                        device.deviceId == state.currentDeviceId,
                    enabled = !state.loading,
                    onRing = { onRing(device) },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: Device,
    isCurrent: Boolean,
    enabled: Boolean,
    onRing: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (isCurrent) "Este dispositivo" else device.model)
                    Text(device.ownerEmail, style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Última conexión: ${formatLastSeen(device)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = onRing, enabled = enabled) {
                    Text("Hacer sonar")
                }
            }
        }
    }
}

private fun formatLastSeen(device: Device): String {
    if (device.lastSeenMillis <= 0) return "pendiente"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        .format(Date(device.lastSeenMillis))
}
