package com.elishaazaria.sayboard.recognition.audio

object WavEncoder {
    /**
     * Encode PCM audio samples into a WAV byte array.
     * @param audioBuffer The audio samples (16-bit PCM)
     * @param numSamples Number of valid samples in the buffer
     * @param sampleRate Sample rate in Hz (e.g. 16000)
     */
    fun createWavBytes(audioBuffer: ShortArray, numSamples: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2  // 16-bit mono
        val dataSize = numSamples * 2
        val fileSize = 36 + dataSize

        val wav = ByteArray(44 + dataSize)
        var offset = 0

        // RIFF header
        "RIFF".encodeToByteArray().copyInto(wav, offset); offset += 4
        writeInt(wav, offset, fileSize); offset += 4
        "WAVE".encodeToByteArray().copyInto(wav, offset); offset += 4

        // fmt chunk
        "fmt ".encodeToByteArray().copyInto(wav, offset); offset += 4
        writeInt(wav, offset, 16); offset += 4  // chunk size
        writeShort(wav, offset, 1); offset += 2  // PCM format
        writeShort(wav, offset, 1); offset += 2  // mono
        writeInt(wav, offset, sampleRate); offset += 4
        writeInt(wav, offset, byteRate); offset += 4
        writeShort(wav, offset, 2); offset += 2  // block align
        writeShort(wav, offset, 16); offset += 2  // bits per sample

        // data chunk
        "data".encodeToByteArray().copyInto(wav, offset); offset += 4
        writeInt(wav, offset, dataSize); offset += 4

        // audio data
        for (i in 0 until numSamples) {
            writeShort(wav, offset, audioBuffer[i].toInt()); offset += 2
        }

        return wav
    }

    private fun writeInt(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xff).toByte()
        arr[offset + 1] = ((value shr 8) and 0xff).toByte()
        arr[offset + 2] = ((value shr 16) and 0xff).toByte()
        arr[offset + 3] = ((value shr 24) and 0xff).toByte()
    }

    private fun writeShort(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xff).toByte()
        arr[offset + 1] = ((value shr 8) and 0xff).toByte()
    }
}
