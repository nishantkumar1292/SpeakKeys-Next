import AVFoundation

enum SpeechEngineError: LocalizedError {
    case notConfigured(String)
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured(let m): return m
        case .failed(let m): return m
        }
    }
}

/// The one interface every speech backend implements. Add a new backend (a local
/// Whisper.cpp server, Deepgram, Google, etc.) by conforming to this and registering
/// it in `EngineManager` — nothing else in the app needs to change.
///
/// Lifecycle for a single push-to-talk utterance:
///   1. `beginUtterance()`           — reset state, open a streaming session if any.
///   2. `append(_:)` (many times)    — 16 kHz mono Int16 buffers, on the audio thread.
///   3. `finishUtterance()`          — produce the final transcript (await network/model).
/// `cancel()` may be called instead of `finishUtterance()` to abort.
protocol SpeechEngine: AnyObject {
    /// Stable identifier persisted in settings (e.g. "apple", "whisper").
    var id: String { get }
    var displayName: String { get }
    /// Whether this engine sends audio off-device.
    var requiresNetwork: Bool { get }
    /// Ready to use right now (permission granted / API key present).
    var isConfigured: Bool { get }
    /// Human-readable hint shown when `isConfigured` is false.
    var setupHint: String { get }

    /// Acquire any permission / validate config. Calls back on the main thread.
    func activate(_ completion: @escaping (Result<Void, Error>) -> Void)

    /// `inputFormat` is the microphone's native capture format.
    func beginUtterance(inputFormat: AVAudioFormat) throws
    func append(_ buffer: AVAudioPCMBuffer)
    func finishUtterance() async -> Result<String, Error>
    func cancel()
}
