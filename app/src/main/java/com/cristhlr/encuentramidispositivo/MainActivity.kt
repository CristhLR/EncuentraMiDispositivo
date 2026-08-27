package com.cristhlr.encuentramidispositivo

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cristhlr.encuentramidispositivo.data.DeviceRepository
import com.cristhlr.encuentramidispositivo.ui.AppScreen
import com.cristhlr.encuentramidispositivo.ui.theme.EncuentraMiDispositivoTheme
import com.google.firebase.FirebaseApp

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            EncuentraMiDispositivoTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (FirebaseApp.getApps(this).isEmpty()) {
                        FirebaseSetupRequired()
                    } else {
                        val repository = DeviceRepository(applicationContext)
                        val mainViewModel: MainViewModel = viewModel(
                            factory = MainViewModel.Factory(repository),
                        )
                        AppScreen(mainViewModel)
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun FirebaseSetupRequired() {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Falta conectar Firebase", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Agrega app/google-services.json siguiendo el README y vuelve a ejecutar la aplicación.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Button(onClick = { }) { Text("Consulta el README") }
        }
    }
}

