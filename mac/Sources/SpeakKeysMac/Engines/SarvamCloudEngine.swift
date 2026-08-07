import AVFoundation

/// Sarvam AI speech-to-text (`saaras:v3`) cloud transcription — good for Indic
/// languages. Requires the user's Sarvam API subscription key (set from the menu).
/// Mirrors the shared SpeakKeys `SarvamCloudRecognizer`.
final class SarvamCloudEngine: SpeechEngine {

    let id = "sarvam"
    let displayName = "Sarvam AI (cloud)"
    let requiresNetwork = true
    let setupHint = "Set your Sarvam API key"

    private let accumulator = AudioAccumulator()

    var isConfigured: Bool { !Settings.sarvamKey.isEmpty }

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
            let text = try await CloudHTTP.transcribeSarvam(apiKey: Settings.sarvamKey, wav: accumulator.makeWav())
            return .success(text)
        } catch {
            return .failure(error)
        }
    }

    func cancel() {
        accumulator.reset()
    }
}
