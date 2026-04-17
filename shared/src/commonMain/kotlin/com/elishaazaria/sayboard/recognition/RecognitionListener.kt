package com.elishaazaria.sayboard.recognition

/**
 * Listener for speech recognition events.
 */
interface RecognitionListener {
    fun onResult(text: String?)
    fun onFinalResult(text: String?)
    fun onPartialResult(partialText: String?)
    fun onError(e: Exception?)
    fun onTimeout()
}
