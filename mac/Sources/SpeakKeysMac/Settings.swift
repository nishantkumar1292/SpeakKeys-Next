import Foundation

enum InsertMode: String {
    case paste      // put text on the clipboard and synthesize ⌘V (fast, universal)
    case keystroke  // synthesize Unicode keystrokes character by character
}

/// Thin, type-safe wrapper over `UserDefaults` for all persisted preferences.
///
/// Note: API keys are stored in `UserDefaults` for prototype simplicity. They are
/// not encrypted; move them to the macOS Keychain before shipping this widely.
enum Settings {
    private static let d = UserDefaults.standard

    private enum Key {
        static let activeEngine = "activeEngineId"
        static let insertMode = "insertMode"
        static let trailingSpace = "addTrailingSpace"
        static let openAIKey = "openAIKey"
        static let sarvamKey = "sarvamKey"
    }

    static var activeEngineId: String {
        get { d.string(forKey: Key.activeEngine) ?? "apple" }
        set { d.set(newValue, forKey: Key.activeEngine) }
    }

    static var insertMode: InsertMode {
        get { InsertMode(rawValue: d.string(forKey: Key.insertMode) ?? "") ?? .paste }
        set { d.set(newValue.rawValue, forKey: Key.insertMode) }
    }

    /// Append a single space after each dictation so consecutive bursts don't run
    /// together. Defaults to `true`.
    static var addTrailingSpace: Bool {
        get { d.object(forKey: Key.trailingSpace) == nil ? true : d.bool(forKey: Key.trailingSpace) }
        set { d.set(newValue, forKey: Key.trailingSpace) }
    }

    static var openAIKey: String {
        get { d.string(forKey: Key.openAIKey) ?? "" }
        set { d.set(newValue, forKey: Key.openAIKey) }
    }

    static var sarvamKey: String {
        get { d.string(forKey: Key.sarvamKey) ?? "" }
        set { d.set(newValue, forKey: Key.sarvamKey) }
    }
}
