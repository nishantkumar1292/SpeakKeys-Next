import AppKit
import AVFoundation
import Speech

/// The menu-bar status item and its dropdown menu. Lets the user pick the active
/// speech engine, enter API keys, choose the insertion strategy, and see/grant the
/// required permissions. The microphone glyph reflects the current dictation state.
final class MenuBarController: NSObject, NSMenuDelegate {

    /// Invoked when the user (re)triggers a permission flow so the app can retry
    /// starting the hotkey tap.
    var onPermissionsChanged: (() -> Void)?

    private let engines: EngineManager
    private var statusItem: NSStatusItem!
    private let menu = NSMenu()
    private var statusHeaderItem: NSMenuItem!

    init(engines: EngineManager) {
        self.engines = engines
        super.init()
        menu.autoenablesItems = false
    }

    func install() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        setIcon(systemName: "mic", recording: false)
        menu.delegate = self
        statusItem.menu = menu
        rebuild()
    }

    /// Rebuild every time the menu opens so permission checkmarks are always current
    /// (TCC status can change in System Settings while we're running).
    func menuWillOpen(_ menu: NSMenu) {
        rebuild()
    }

    // MARK: dictation state → icon + header

    func update(state: DictationController.State) {
        switch state {
        case .idle:
            setIcon(systemName: "mic", recording: false)
            statusHeaderItem?.title = "Ready — hold ⌥ to talk"
        case .listening:
            setIcon(systemName: "mic.fill", recording: true)
            statusHeaderItem?.title = "Listening…"
        case .transcribing:
            setIcon(systemName: "waveform", recording: false)
            statusHeaderItem?.title = "Transcribing…"
        case .info(let message):
            setIcon(systemName: "mic", recording: false)
            statusHeaderItem?.title = message
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) { [weak self] in
                self?.statusHeaderItem?.title = "Ready — hold ⌥ to talk"
            }
        case .error(let message):
            setIcon(systemName: "exclamationmark.triangle", recording: false)
            statusHeaderItem?.title = "Error: \(message)"
            // Return to the idle glyph after a moment.
            DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { [weak self] in
                self?.setIcon(systemName: "mic", recording: false)
                self?.statusHeaderItem?.title = "Ready — hold ⌥ to talk"
            }
        }
    }

    private func setIcon(systemName: String, recording: Bool) {
        guard let button = statusItem?.button else { return }
        let image = NSImage(systemSymbolName: systemName, accessibilityDescription: "SpeakKeys")
        image?.isTemplate = !recording
        button.image = image
        button.contentTintColor = recording ? .systemRed : nil
    }

    // MARK: menu construction

    /// Rebuilds the whole menu. Cheap, and keeps engine/permission state in sync.
    func rebuild() {
        menu.removeAllItems()

        statusHeaderItem = NSMenuItem(title: "Ready — hold ⌥ to talk", action: nil, keyEquivalent: "")
        statusHeaderItem.isEnabled = false
        menu.addItem(statusHeaderItem)
        menu.addItem(.separator())

        // --- Engines (radio list) ---
        let engineHeader = NSMenuItem(title: "Speech engine", action: nil, keyEquivalent: "")
        engineHeader.isEnabled = false
        menu.addItem(engineHeader)

        for engine in engines.engines {
            let suffix = engine.isConfigured ? "" : "  — \(engine.setupHint)"
            let item = NSMenuItem(title: "  \(engine.displayName)\(suffix)",
                                  action: #selector(selectEngine(_:)), keyEquivalent: "")
            item.target = self
            item.representedObject = engine.id
            item.state = (engine.id == engines.active.id) ? .on : .off
            menu.addItem(item)
        }
        menu.addItem(.separator())

        // --- API keys ---
        menu.addItem(makeItem("Set OpenAI API Key…", #selector(setOpenAIKey)))
        menu.addItem(makeItem("Set Sarvam API Key…", #selector(setSarvamKey)))
        menu.addItem(.separator())

        // --- Insertion options ---
        let insertHeader = NSMenuItem(title: "Insert text by", action: nil, keyEquivalent: "")
        insertHeader.isEnabled = false
        menu.addItem(insertHeader)

        let pasteItem = makeItem("  Pasting (fast, universal)", #selector(setPasteMode))
        pasteItem.state = Settings.insertMode == .paste ? .on : .off
        menu.addItem(pasteItem)

        let keyItem = makeItem("  Typing keystrokes", #selector(setKeystrokeMode))
        keyItem.state = Settings.insertMode == .keystroke ? .on : .off
        menu.addItem(keyItem)

        let spaceItem = makeItem("Add trailing space", #selector(toggleTrailingSpace))
        spaceItem.state = Settings.addTrailingSpace ? .on : .off
        menu.addItem(spaceItem)
        menu.addItem(.separator())

        // --- Permissions ---
        let permHeader = NSMenuItem(title: "Permissions", action: nil, keyEquivalent: "")
        permHeader.isEnabled = false
        menu.addItem(permHeader)
        menu.addItem(permissionItem("Accessibility (required)", granted: Permissions.hasAccessibility,
                                    action: #selector(fixAccessibility)))
        menu.addItem(permissionItem("Input Monitoring (required)", granted: Permissions.hasInputMonitoring,
                                    action: #selector(fixInputMonitoring)))
        menu.addItem(permissionItem("Microphone", granted: Permissions.microphoneStatus == .authorized,
                                    action: #selector(fixMicrophone)))
        menu.addItem(permissionItem("Speech Recognition (Apple engine)",
                                    granted: Permissions.speechStatus == .authorized,
                                    action: #selector(fixSpeech)))
        menu.addItem(.separator())

        menu.addItem(makeItem("Quit SpeakKeys", #selector(quit)))
    }

    private func makeItem(_ title: String, _ action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    private func permissionItem(_ label: String, granted: Bool, action: Selector) -> NSMenuItem {
        let item = NSMenuItem(title: "\(granted ? "✓" : "✗")  \(label)", action: action, keyEquivalent: "")
        item.target = self
        return item
    }

    // MARK: actions

    @objc private func selectEngine(_ sender: NSMenuItem) {
        guard let id = sender.representedObject as? String else { return }
        engines.setActive(id)
        // Proactively kick off any permission/validation the engine needs.
        engines.active.activate { [weak self] _ in self?.rebuild() }
        rebuild()
    }

    @objc private func setOpenAIKey() {
        if let key = promptForKey(title: "OpenAI API Key", message: "Used for the Whisper engine. Stored locally.",
                                  current: Settings.openAIKey) {
            Settings.openAIKey = key
            rebuild()
        }
    }

    @objc private func setSarvamKey() {
        if let key = promptForKey(title: "Sarvam API Key", message: "Used for the Sarvam engine. Stored locally.",
                                  current: Settings.sarvamKey) {
            Settings.sarvamKey = key
            rebuild()
        }
    }

    @objc private func setPasteMode() { Settings.insertMode = .paste; rebuild() }
    @objc private func setKeystrokeMode() { Settings.insertMode = .keystroke; rebuild() }
    @objc private func toggleTrailingSpace() { Settings.addTrailingSpace.toggle(); rebuild() }

    @objc private func fixAccessibility() {
        Permissions.promptAccessibility()
        Permissions.openAccessibilitySettings()
        onPermissionsChanged?()
        rebuild()
    }

    @objc private func fixInputMonitoring() {
        Permissions.requestInputMonitoring()
        Permissions.openInputMonitoringSettings()
        onPermissionsChanged?()
        rebuild()
    }

    @objc private func fixMicrophone() {
        if Permissions.microphoneStatus == .notDetermined {
            Permissions.requestMicrophone { [weak self] _ in self?.rebuild() }
        } else {
            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone") {
                NSWorkspace.shared.open(url)
            }
        }
    }

    @objc private func fixSpeech() {
        if Permissions.speechStatus == .notDetermined {
            Permissions.requestSpeech { [weak self] _ in self?.rebuild() }
        } else {
            if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_SpeechRecognition") {
                NSWorkspace.shared.open(url)
            }
        }
    }

    @objc private func quit() {
        NSApp.terminate(nil)
    }

    // MARK: key entry

    private func promptForKey(title: String, message: String, current: String) -> String? {
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        alert.addButton(withTitle: "Save")
        alert.addButton(withTitle: "Cancel")

        let field = NSSecureTextField(frame: NSRect(x: 0, y: 0, width: 300, height: 24))
        field.stringValue = current
        field.placeholderString = "Paste key here"
        alert.accessoryView = field

        NSApp.activate(ignoringOtherApps: true)
        let response = alert.runModal()
        guard response == .alertFirstButtonReturn else { return nil }
        return field.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
