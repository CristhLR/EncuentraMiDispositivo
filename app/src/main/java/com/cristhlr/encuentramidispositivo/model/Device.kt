package com.cristhlr.encuentramidispositivo.model

import com.google.firebase.Timestamp

data class Device(
    val id: String = "",
    val name: String = "Dispositivo Android",
    val model: String = "",
    val platform: String = "Android",
    val lastSeen: Timestamp? = null,
)

