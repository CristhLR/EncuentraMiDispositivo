package com.cristhlr.encuentramidispositivo.service

import android.content.Intent
import androidx.core.content.ContextCompat
import com.cristhlr.encuentramidispositivo.data.DeviceRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class FcmService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["action"] != "RING") return

        val intent = Intent(this, RingService::class.java).apply {
            action = RingService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onNewToken(token: String) {
        if (FirebaseApp.getApps(this).isEmpty()) return
        serviceScope.launch {
            runCatching { DeviceRepository(applicationContext).updateCurrentToken(token) }
        }
    }
}

