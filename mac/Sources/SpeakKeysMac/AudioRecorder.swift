import AVFoundation

/// Captures microphone audio via `AVAudioEngine` and forwards each **native** buffer
/// (the input device's own format) to `onBuffer`. Engines convert as needed:
/// `SFSpeechRecognizer` wants the native buffer directly (its documented usage),
/// while cloud engines downsample to 16 kHz inside `AudioAccumulator`.
///
/// Also tracks buffer count and peak amplitude for diagnostics, logged on stop.
final class AudioRecorder {

    /// Called on the audio render thread with native-format buffers.
    var onBuffer: ((AVAudioPCMBuffer) -> Void)?

    private let engine = AVAudioEngine()
    private var isRunning = false
    private var bufferCount = 0
    private var peak: Float = 0

    /// The current input device format. Valid to read before `start()`.
    var inputFormat: AVAudioFormat {
        engine.inputNode.outputFormat(forBus: 0)
    }

    func start() throws {
        guard !isRunning else { return }

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            throw NSError(domain: "SpeakKeys.Audio", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "No microphone input available (check Microphone permission)."
            ])
        }

        bufferCount = 0
        peak = 0

        input.installTap(onBus: 0, bufferSize: 4096, format: format) { [weak self] buffer, _ in
            self?.handle(buffer)
        }

        engine.prepare()
        try engine.start()
        isRunning = true
        Log.info("recorder started — format: \(format.sampleRate)Hz, \(format.channelCount)ch, common=\(format.commonFormat.rawValue)")
    }

    func stop() {
        guard isRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        isRunning = false
        Log.info(String(format: "recorder stopped — %d buffers, peak amplitude %.4f", bufferCount, peak))
    }

    private func handle(_ buffer: AVAudioPCMBuffer) {
        bufferCount += 1
        updatePeak(buffer)
        onBuffer?(buffer)
    }

    private func updatePeak(_ buffer: AVAudioPCMBuffer) {
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return }
        if let f = buffer.floatChannelData {
            var localPeak: Float = 0
            for i in 0..<frames { localPeak = max(localPeak, abs(f[0][i])) }
            peak = max(peak, localPeak)
        } else if let s = buffer.int16ChannelData {
            var localPeak: Int16 = 0
            for i in 0..<frames { localPeak = max(localPeak, abs(s[0][i])) }
            peak = max(peak, Float(localPeak) / 32768.0)
        }
    }
}
