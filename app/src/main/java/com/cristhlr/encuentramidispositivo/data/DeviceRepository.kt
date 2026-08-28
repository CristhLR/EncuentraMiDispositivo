package com.cristhlr.encuentramidispositivo.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.cristhlr.encuentramidispositivo.model.Device
import com.cristhlr.encuentramidispositivo.model.FamilyGroup
import com.cristhlr.encuentramidispositivo.model.FamilyState
import com.cristhlr.encuentramidispositivo.service.RingService
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
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

    fun stopCurrentRing() {
        val intent = Intent(context, RingService::class.java).apply {
            action = RingService.ACTION_STOP
        }
        context.startService(intent)
    }

    fun currentDeviceId(): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    suspend fun registerCurrentDevice() {
        val user = auth.currentUser ?: return
        val token = messaging.token.await()
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val data = mapOf(
            "name" to "$manufacturer $model",
            "model" to model,
            "manufacturer" to manufacturer,
            "platform" to "Android",
            "fcmToken" to token,
            "lastSeen" to FieldValue.serverTimestamp(),
            "appVersion" to "0.2.3",
        )
        deviceCollection(user.uid).document(currentDeviceId()).set(data, SetOptions.merge()).await()
        runCatching { syncCurrentDeviceToGroup() }
    }

    suspend fun updateCurrentToken(token: String) {
        val user = auth.currentUser ?: return
        deviceCollection(user.uid).document(currentDeviceId()).set(
            mapOf("fcmToken" to token, "lastSeen" to FieldValue.serverTimestamp()),
            SetOptions.merge(),
        ).await()
        runCatching { syncCurrentDeviceToGroup() }
    }

    suspend fun createFamilyGroup(name: String) {
        api("/groups/create", JSONObject().put("name", name.trim()))
    }

    suspend fun joinFamilyGroup(code: String) {
        api("/groups/join", JSONObject().put("code", code.trim().uppercase()))
    }

    suspend fun loadFamilyState(): FamilyState {
        val response = api("/state", JSONObject())
        val groupJson = response.optJSONObject("group")
        val group = groupJson?.let {
            FamilyGroup(
                id = it.getString("id"),
                name = it.getString("name"),
                inviteCode = it.getString("inviteCode"),
            )
        }
        val devicesJson = response.optJSONArray("devices")
        val devices = buildList {
            if (devicesJson != null) {
                for (index in 0 until devicesJson.length()) {
                    val item = devicesJson.getJSONObject(index)
                    add(
                        Device(
                            id = item.getString("id"),
                            deviceId = item.getString("deviceId"),
                            ownerUid = item.getString("ownerUid"),
                            ownerEmail = item.optString("ownerEmail"),
                            name = item.optString("name", "Dispositivo Android"),
                            model = item.optString("model"),
                            platform = item.optString("platform", "Android"),
                            lastSeenMillis = item.optLong("lastSeenMillis"),
                        ),
                    )
                }
            }
        }
        return FamilyState(group, devices)
    }

    suspend fun ringDevice(groupDeviceId: String) {
        api("/ring", JSONObject().put("deviceId", groupDeviceId))
    }

    suspend fun stopDevice(groupDeviceId: String) {
        api("/stop", JSONObject().put("deviceId", groupDeviceId))
    }

    private suspend fun syncCurrentDeviceToGroup() {
        api("/devices/register", JSONObject().put("deviceId", currentDeviceId()))
    }

    private suspend fun api(path: String, body: JSONObject): JSONObject {
        val user = auth.currentUser ?: error("Debes iniciar sesión.")
        val idToken = user.getIdToken(false).await().token
            ?: error("No se pudo validar tu sesión.")

        return withContext(Dispatchers.IO) {
            val connection = (URL(API_BASE_URL + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 25_000
                doOutput = true
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
            }
            try {
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
                val status = connection.responseCode
                val responseBody = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching { JSONObject(responseBody).optString("error") }
                        .getOrNull().takeUnless { it.isNullOrBlank() }
                        ?: "No se pudo completar la solicitud."
                    throw IOException(message)
                }
                if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun deviceCollection(userId: String) =
        firestore.collection("users").document(userId).collection("devices")

    private companion object {
        const val API_BASE_URL = "https://encuentra-mi-dispositivo-api.cdavidleonr.workers.dev"
    }
}
