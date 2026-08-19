import AppKit

/// Top-level coordinator. Builds the menu bar, requests permissions, and wires the
/// global hotkey to the dictation controller.
final class AppDelegate: NSObject, NSApplicationDelegate {

    private let engines = EngineManager()
    private lazy var controller = DictationController(engines: engines)
    private lazy var menuBar = MenuBarController(engines: engines)
    private let hotkey = GlobalHotkeyMonitor()
    private var permissionPollTimer: Timer?

    func applicationDidFinishLaunching(_ notification: Notification) {
        Log.start()
        Log.info("app launched")
        menuBar.install()

        controller.onStateChange = { [weak self] state in
            self?.menuBar.update(state: state)
        }
        menuBar.onPermissionsChanged = { [weak self] in
            self?.startHotkeyOrPrompt()
        }

        hotkey.onPress = { [weak self] in self?.controller.startDictation() }
        hotkey.onRelease = { [weak self] in self?.controller.stopDictation() }

        // Ask for microphone up front (the recorder needs it before first use).
        Permissions.requestMicrophone { _ in }
        // Apple engine needs speech-recognition auth; request it when it's the default.
        if engines.active.id == "apple" {
            Permissions.requestSpeech { [weak self] _ in self?.menuBar.rebuild() }
        }
        // Input Monitoring is required to RECEIVE keyboard events in the tap.
        Permissions.requestInputMonitoring()

        startHotkeyOrPrompt()
    }

    func applicationWillTerminate(_ notification: Notification) {
        hotkey.stop()
    }

    /// A keyboard event tap needs TWO permissions: Accessibility (to create the tap)
    /// and Input Monitoring (to actually receive key events). `tapCreate` succeeds
    /// with only Accessibility but then receives nothing — so we wait for BOTH before
    /// (re)creating the tap, and poll until the user grants them.
    private func startHotkeyOrPrompt() {
        if Permissions.hasAccessibility && Permissions.hasInputMonitoring {
            hotkey.stop()                       // recreate fresh after a grant
            if hotkey.start() {
                permissionPollTimer?.invalidate()
                permissionPollTimer = nil
                Log.info("hotkey ready — accessibility + input monitoring granted")
                menuBar.rebuild()
                return
            }
        }

        if !Permissions.hasAccessibility { Permissions.promptAccessibility() }
        if !Permissions.hasInputMonitoring { Permissions.requestInputMonitoring() }
        Log.info("waiting for permissions — accessibility=\(Permissions.hasAccessibility) inputMonitoring=\(Permissions.hasInputMonitoring)")
        menuBar.rebuild()

        guard permissionPollTimer == nil else { return }
        permissionPollTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] timer in
            guard let self else { timer.invalidate(); return }
            guard Permissions.hasAccessibility && Permissions.hasInputMonitoring else { return }
            self.hotkey.stop()
            if self.hotkey.start() {
                timer.invalidate()
                self.permissionPollTimer = nil
                Log.info("hotkey ready after granting permissions")
                self.menuBar.rebuild()
            }
        }
    }
}
