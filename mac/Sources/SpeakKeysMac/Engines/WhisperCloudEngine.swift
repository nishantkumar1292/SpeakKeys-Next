import AVFoundation

/// OpenAI Whisper (`whisper-1`) cloud transcription. Accumulates the held audio and
/// uploads it as a WAV on release. Requires the user's OpenAI API key (set from the
/// menu). Mirrors the shared SpeakKeys `WhisperCloudRecognizer`.
final class WhisperCloudEngine: SpeechEngine {

    let id = "whisper"
    let displayName = "OpenAI Whisper (cloud)"
    let requiresNetwork = true
    let setupHint = "Set your OpenAI API key"

    private let accumulator = AudioAccumulator()

    var isConfigured: Bool { !Settings.openAIKey.isEmpty }

    func activate(_ completion: @escaping (Result<Void, Error>) -> Void) {
        if isConfigured {
            completion(.success(()))
        } else {
            completion(.failure(SpeechEngineError.notConfigured(setupHint)))
        }
    }

    func beginUtterance(inputFormat: AVAudioFormat) throws {
        guard isConfigured else { throw SpeechEngineError.notConfigured(setupHint) }
        accumulator.begin(inputFormat: inputFormat)
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        accumulator.append(buffer)
    }

    func finishUtterance() async -> Result<String, Error> {
        guard !accumulator.isEmpty else { return .success("") }
        do {
            let text = try await CloudHTTP.transcribeWhisper(apiKey: Settings.openAIKey, wav: accumulator.makeWav())
            return .success(text)
        } catch {
            return .failure(error)
        }
    }

    func cancel() {
        accumulator.reset()
    }
}
