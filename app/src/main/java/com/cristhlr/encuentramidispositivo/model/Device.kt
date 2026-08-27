package com.cristhlr.encuentramidispositivo.model

data class FamilyGroup(
    val id: String,
    val name: String,
    val inviteCode: String,
)

data class FamilyState(
    val group: FamilyGroup?,
    val devices: List<Device>,
)

data class Device(
    val id: String,
    val deviceId: String,
    val ownerUid: String,
    val ownerEmail: String,
    val name: String,
    val model: String,
    val platform: String,
    val lastSeenMillis: Long,
)
