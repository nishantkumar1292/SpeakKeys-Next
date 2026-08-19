import Foundation

/// Owns the set of available speech engines and tracks which one is active
/// (persisted in `Settings`). To add a backend, append it to `engines` — the menu
/// and dictation flow pick it up automatically.
final class EngineManager {

    let engines: [SpeechEngine]

    init() {
        engines = [
            AppleSpeechEngine(),
            WhisperCloudEngine(),
            SarvamCloudEngine(),
        ]
    }

    var active: SpeechEngine {
        engines.first { $0.id == Settings.activeEngineId } ?? engines[0]
    }

    func engine(withId id: String) -> SpeechEngine? {
        engines.first { $0.id == id }
    }

    func setActive(_ id: String) {
        guard engine(withId: id) != nil else { return }
        Settings.activeEngineId = id
    }
}
