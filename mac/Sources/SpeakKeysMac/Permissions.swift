import AppKit
import AVFoundation
import Speech
import ApplicationServices
import IOKit.hid

/// Centralizes the three TCC permissions SpeakKeys needs:
///  - Microphone:        to record audio.
///  - Speech Recognition: only when the Apple engine is selected.
///  - Accessibility:      to listen for the global hotkey and to post the
///                        synthesized keystrokes / paste that insert text.
enum Permissions {

    // MARK: Accessibility (event tap + posting events)

    static var hasAccessibility: Bool { AXIsProcessTrusted() }

    /// Triggers the system "… would like to control this computer" prompt and
    /// adds the app to the Accessibility list (unchecked) so the user can enable it.
    @discardableResult
    static func promptAccessibility() -> Bool {
        let key = kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String
        let options = [key: true] as CFDictionary
        return AXIsProcessTrustedWithOptions(options)
    }

    static func openAccessibilitySettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: Input Monitoring (listening to keyboard events via the event tap)

    static var hasInputMonitoring: Bool {
        IOHIDCheckAccess(kIOHIDRequestTypeListenEvent) == kIOHIDAccessTypeGranted
    }

    /// Prompts for Input Monitoring if undetermined; no-op if already decided.
    @discardableResult
    static func requestInputMonitoring() -> Bool {
        IOHIDRequestAccess(kIOHIDRequestTypeListenEvent)
    }

    static func openInputMonitoringSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: Microphone

    static var microphoneStatus: AVAuthorizationStatus {
        AVCaptureDevice.authorizationStatus(for: .audio)
    }

    static func requestMicrophone(_ completion: @escaping (Bool) -> Void) {
        AVCaptureDevice.requestAccess(for: .audio) { granted in
            DispatchQueue.main.async { completion(granted) }
        }
    }

    // MARK: Speech recognition

    static var speechStatus: SFSpeechRecognizerAuthorizationStatus {
        SFSpeechRecognizer.authorizationStatus()
    }

    static func requestSpeech(_ completion: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            DispatchQueue.main.async { completion(status == .authorized) }
        }
    }
}
