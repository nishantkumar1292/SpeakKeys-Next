import AppKit

// SpeakKeys for Mac — push-to-talk voice dictation that types wherever your cursor is.
// Menu-bar agent: no Dock icon, no main window (see Info.plist LSUIElement).
let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.accessory)
app.run()
