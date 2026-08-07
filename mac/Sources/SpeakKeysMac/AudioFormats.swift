import AVFoundation

/// The single canonical capture format the whole app works in: 16 kHz, mono,
/// signed 16-bit PCM. Every speech engine receives audio in this format, so the
/// recorder only has to convert once and engines stay simple.
enum AudioFormats {
    static let sampleRate: Double = 16_000

    static let mono16k: AVAudioFormat = AVAudioFormat(
        commonFormat: .pcmFormatInt16,
        sampleRate: sampleRate,
        channels: 1,
        interleaved: true
    )!
}
