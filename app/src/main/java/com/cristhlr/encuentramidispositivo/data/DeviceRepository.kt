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
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

class DeviceRepository(private val context: Context) {
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val functions = FirebaseFunctions.getInstance()
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
        functions.getHttpsCallable("ringDevice")
            .call(mapOf("deviceId" to deviceId))
            .await()
    }

    private fun deviceCollection(userId: String) =
        firestore.collection("users").document(userId).collection("devices")
}

