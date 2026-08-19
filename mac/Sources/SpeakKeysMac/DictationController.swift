import AppKit
import AVFoundation

/// Orchestrates one push-to-talk cycle:
///   key down → start recorder + active engine → buffers stream in →
///   key up   → stop recorder → await transcript → insert text.
///
/// All public methods are called on the main thread (from the hotkey callbacks).
final class DictationController {

    enum State {
        case idle
        case listening
        case transcribing
        case info(String)     // transient, non-error status (e.g. "No speech detected")
        case error(String)
    }

    var onStateChange: ((State) -> Void)?

    private let engines: EngineManager
    private let recorder = AudioRecorder()
    private let inserter = TextInserter()
    private var isRecording = false

    init(engines: EngineManager) {
        self.engines = engines
    }

    func startDictation() {
        guard !isRecording else { return }

        let engine = engines.active
        Log.info("startDictation — engine=\(engine.id)")
        guard engine.isConfigured else {
            fail("\(engine.displayName): \(engine.setupHint)")
            return
        }

        do {
            let inputFormat = recorder.inputFormat
            try engine.beginUtterance(inputFormat: inputFormat)
            recorder.onBuffer = { engine.append($0) }
            try recorder.start()
            isRecording = true
            onStateChange?(.listening)
        } catch {
            recorder.onBuffer = nil
            engine.cancel()
            Log.error("startDictation failed: \(message(for: error))")
            fail(message(for: error))
        }
    }

    func stopDictation() {
        guard isRecording else { return }
        isRecording = false

        recorder.stop()
        recorder.onBuffer = nil
        onStateChange?(.transcribing)

        let engine = engines.active
        Log.info("stopDictation — awaiting transcript from \(engine.id)")
        Task {
            let result = await engine.finishUtterance()
            await MainActor.run {
                switch result {
                case .success(let raw):
                    Log.info("transcript success: \(raw.count) chars — \"\(raw)\"")
                    let text = self.postProcess(raw)
                    if text.isEmpty {
                        self.onStateChange?(.info("No speech detected"))
                    } else {
                        self.inserter.insert(text)
                        self.onStateChange?(.idle)
                    }
                case .failure(let error):
                    Log.error("transcript failed: \(self.message(for: error))")
                    self.fail(self.message(for: error))
                }
            }
        }
    }

    /// Abort without transcribing/inserting.
    func cancelDictation() {
        guard isRecording else { return }
        isRecording = false
        recorder.stop()
        recorder.onBuffer = nil
        engines.active.cancel()
        onStateChange?(.idle)
    }

    // MARK: helpers

    private func postProcess(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return "" }
        if Settings.addTrailingSpace { text += " " }
        return text
    }

    private func fail(_ message: String) {
        NSSound.beep()
        onStateChange?(.error(message))
    }

    private func message(for error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }
}
