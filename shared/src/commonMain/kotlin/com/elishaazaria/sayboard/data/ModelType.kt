package com.elishaazaria.sayboard.data

import kotlinx.serialization.Serializable

@Serializable
enum class ModelType {
    WhisperCloud,
    SarvamCloud,
    ElevenLabsCloud,
    ProxiedWhisperCloud,
    ProxiedSarvamCloud,
    AndroidOnDevice,
    /** Catalog-driven Vosk model. Path, not this enum value, identifies language and version. */
    VoskLocal,
    /** Legacy persisted values retained so existing preferences continue to deserialize. */
    VoskHindiLocal,
    VoskEnglishIndiaLocal,
}
