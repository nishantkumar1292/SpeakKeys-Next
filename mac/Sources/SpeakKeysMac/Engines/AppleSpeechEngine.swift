import AVFoundation
import Speech

/// Runs a closure at most once, thread-safely (for resolving a continuation that
/// several callbacks might race to fulfill).
private final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func run(_ block: () -> Void) {
        lock.lock()
        if done { lock.unlock(); return }
        done = true
        lock.unlock()
        block()
    }
}

/// Apple's speech recognition (`SFSpeechRecognizer`). Default engine: free, no API key.
///
/// Uses **batch** recognition: audio is accumulated while the key is held, then the
/// recognition task is created on release with all the audio already present. This
/// avoids the streaming failure mode where the task, created before any audio flows,
/// immediately errors with "No speech detected" (error 1110). On-device recognition
/// is forced when available — it's offline, private, and has no server-side timeout.
final class AppleSpeechEngine: NSObject, SpeechEngine, @unchecked Sendable {

    let id = "apple"
    let displayName = "Apple"
    let requiresNetwork = false
    let setupHint = "Grant Speech Recognition permission"

    private let recognizer = SFSpeechRecognizer()   // user's current locale
    private let accumulator = AudioAccumulator()
    private var task: SFSpeechRecognitionTask?

    var isConfigured: Bool {
        SFSpeechRecognizer.authorizationStatus() == .authorized && (recognizer?.isAvailable ?? false)
    }

    func activate(_ completion: @escaping (Result<Void, Error>) -> Void) {
        Permissions.requestSpeech { granted in
            if !granted {
                completion(.failure(SpeechEngineError.notConfigured("Speech Recognition permission is required")))
            } else if self.recognizer == nil {
                completion(.failure(SpeechEngineError.failed("Speech recognition isn't supported for your locale")))
            } else {
                completion(.success(()))
            }
        }
    }

    func beginUtterance(inputFormat: AVAudioFormat) throws {
        let status = SFSpeechRecognizer.authorizationStatus()
        guard status == .authorized else {
            throw SpeechEngineError.notConfigured(
                "Speech Recognition not authorized — enable SpeakKeys in System Settings ▸ Privacy & Security ▸ Speech Recognition")
        }
        guard let recognizer, recognizer.isAvailable else {
            throw SpeechEngineError.failed("Speech recognizer unavailable (locale \(Locale.current.identifier))")
        }
        Log.info("apple: beginUtterance locale=\(recognizer.locale.identifier) supportsOnDevice=\(recognizer.supportsOnDeviceRecognition)")
        accumulator.begin(inputFormat: inputFormat)
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        accumulator.append(buffer)
    }

    func finishUtterance() async -> Result<String, Error> {
        guard let recognizer, recognizer.isAvailable else {
            return .failure(SpeechEngineError.failed("Speech recognizer unavailable"))
        }
        guard let audio = accumulator.makeBuffer() else {
            Log.info("apple: no audio captured")
            return .success("")
        }
        Log.info("apple: recognizing \(audio.frameLength) frames @16kHz")

        // Prefer on-device (offline) when supported; fall back to server-based
        // recognition if the on-device path fails (e.g. model not downloaded).
        if recognizer.supportsOnDeviceRecognition {
            let result = await recognize(recognizer, audio: audio, onDevice: true)
            if case .success = result { return result }
            Log.error("apple: on-device path failed, retrying server-based")
        }
        return await recognize(recognizer, audio: audio, onDevice: false)
    }

    /// Run one batch recognition pass over the whole utterance.
    private func recognize(_ recognizer: SFSpeechRecognizer,
                           audio: AVAudioPCMBuffer,
                           onDevice: Bool) async -> Result<String, Error> {
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = false
        if #available(macOS 13, *) { request.addsPunctuation = true }
        request.requiresOnDeviceRecognition = onDevice

        return await withCheckedContinuation { (cont: CheckedContinuation<Result<String, Error>, Never>) in
            let once = ResumeOnce()

            let t = recognizer.recognitionTask(with: request) { result, error in
                if let result, result.isFinal {
                    let text = result.bestTranscription.formattedString
                    Log.info("apple: FINAL (onDevice=\(onDevice)) = \"\(text)\"")
                    once.run { cont.resume(returning: .success(text)) }
                    return
                }
                if let error {
                    let ns = error as NSError
                    let partial = result?.bestTranscription.formattedString ?? ""
                    Log.error("apple: error (onDevice=\(onDevice)) \(ns.domain) code=\(ns.code) — \(ns.localizedDescription); partial=\"\(partial)\"")
                    if !partial.isEmpty {
                        once.run { cont.resume(returning: .success(partial)) }
                    } else if Self.isNoSpeechError(ns) {
                        once.run { cont.resume(returning: .success("")) }
                    } else {
                        once.run { cont.resume(returning: .failure(SpeechEngineError.failed("Apple Speech error \(ns.code): \(ns.localizedDescription)"))) }
                    }
                }
            }
            self.task = t

            // All audio is present up front: hand it over, then close the stream.
            request.append(audio)
            request.endAudio()

            // Safety net in case the task never reports final/error.
            DispatchQueue.main.asyncAfter(deadline: .now() + 12) {
                once.run { cont.resume(returning: .success("")) }
            }
        }
    }

    func cancel() {
        task?.cancel()
        task = nil
        accumulator.reset()
    }

    // MARK: helpers

    /// `kAFAssistantErrorDomain` codes that just mean "no speech / nothing to do".
    private static func isNoSpeechError(_ error: NSError) -> Bool {
        guard error.domain == "kAFAssistantErrorDomain" else { return false }
        return [203, 216, 1110, 1700].contains(error.code)
    }
}
