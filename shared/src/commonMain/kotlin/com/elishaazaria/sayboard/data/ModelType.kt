package com.elishaazaria.sayboard.data

import kotlinx.serialization.Serializable

@Serializable
enum class ModelType {
    WhisperCloud,
    SarvamCloud,
    ProxiedWhisperCloud,
    ProxiedSarvamCloud
}
