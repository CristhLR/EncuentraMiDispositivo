package com.cristhlr.encuentramidispositivo.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.cristhlr.encuentramidispositivo.model.Device
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DeviceRepository(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val messaging = FirebaseMessaging.getInstance()

    val currentUser get() = auth.currentUser

    fun addAuthListener(listener: FirebaseAuth.AuthStateListener) = auth.addAuthStateListener(listener)

    fun removeAuthListener(listener: FirebaseAuth.AuthStateListener) = auth.removeAuthStateListener(listener)

    suspend fun signIn(email: String, password: String) {
        auth.signInWithEmailAndPassword(email.trim(), password).await()
    }

    suspend fun createAccount(email: String, password: String) {
        auth.createUserWithEmailAndPassword(email.trim(), password).await()
    }

    fun signOut() = auth.signOut()

    fun currentDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    suspend fun registerCurrentDevice() {
        val user = auth.currentUser ?: return
        val token = messaging.token.await()
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val deviceId = currentDeviceId()
        val data = mapOf(
            "name" to "$manufacturer $model",
            "model" to model,
            "manufacturer" to manufacturer,
            "platform" to "Android",
            "fcmToken" to token,
            "lastSeen" to FieldValue.serverTimestamp(),
            "appVersion" to "0.1.0",
        )
        deviceCollection(user.uid).document(deviceId).set(data, SetOptions.merge()).await()
    }

    suspend fun updateCurrentToken(token: String) {
        val user = auth.currentUser ?: return
        deviceCollection(user.uid).document(currentDeviceId()).set(
            mapOf(
                "fcmToken" to token,
                "lastSeen" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        ).await()
    }

    fun listenToDevices(
        userId: String,
        onChange: (List<Device>) -> Unit,
        onError: (Throwable) -> Unit,
    ): ListenerRegistration = deviceCollection(userId).addSnapshotListener { snapshot, error ->
        if (error != null) {
            onError(error)
            return@addSnapshotListener
        }
        val devices = snapshot?.documents.orEmpty().mapNotNull { document ->
            document.toObject(Device::class.java)?.copy(id = document.id)
        }.sortedByDescending { it.lastSeen?.seconds ?: 0L }
        onChange(devices)
    }

    suspend fun ringDevice(deviceId: String) {
        val user = auth.currentUser ?: error("Debes iniciar sesión.")
        val idToken = user.getIdToken(false).await().token
            ?: error("No se pudo validar tu sesión.")

        withContext(Dispatchers.IO) {
            val connection = (URL(RING_ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val requestBody = JSONObject().put("deviceId", deviceId).toString()
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(requestBody)
                }

                val status = connection.responseCode
                val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

                if (status !in 200..299) {
                    val message = runCatching { JSONObject(responseBody).optString("error") }
                        .getOrNull()
                        .takeUnless { it.isNullOrBlank() }
                        ?: "No se pudo enviar la alarma."
                    throw IOException(message)
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun deviceCollection(userId: String) =
        firestore.collection("users").document(userId).collection("devices")

    private companion object {
        const val RING_ENDPOINT =
            "https://encuentra-mi-dispositivo-api.cdavidleonr.workers.dev/ring"
    }
}
