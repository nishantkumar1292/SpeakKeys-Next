import AppKit
import CoreGraphics

/// Inserts text at the current cursor in whatever app is frontmost. Two strategies:
///
///  - `.paste`     — put the text on the clipboard, synthesize ⌘V, then restore the
///                   previous clipboard. Fast and reliable for any length of text in
///                   essentially every standard text field. (Default.)
///  - `.keystroke` — synthesize Unicode key events directly. Doesn't touch the
///                   clipboard, but slower for long text and a few apps ignore it.
///
/// Both require Accessibility permission (to post events via `cghidEventTap`).
final class TextInserter {

    func insert(_ text: String) {
        guard !text.isEmpty else { return }
        switch Settings.insertMode {
        case .paste:     pasteInsert(text)
        case .keystroke: keystrokeInsert(text)
        }
    }

    // MARK: paste strategy

    private func pasteInsert(_ text: String) {
        let pasteboard = NSPasteboard.general
        let previous = pasteboard.string(forType: .string)

        pasteboard.clearContents()
        pasteboard.setString(text, forType: .string)

        sendCommandV()

        // Restore the user's clipboard shortly after the paste lands.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
            pasteboard.clearContents()
            if let previous { pasteboard.setString(previous, forType: .string) }
        }
    }

    private func sendCommandV() {
        let source = CGEventSource(stateID: .combinedSessionState)
        let vKey: CGKeyCode = 0x09   // "v"

        let down = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: true)
        down?.flags = .maskCommand
        let up = CGEvent(keyboardEventSource: source, virtualKey: vKey, keyDown: false)
        up?.flags = .maskCommand

        down?.post(tap: .cghidEventTap)
        up?.post(tap: .cghidEventTap)
    }

    // MARK: keystroke strategy

    private func keystrokeInsert(_ text: String) {
        let source = CGEventSource(stateID: .combinedSessionState)
        let units = Array(text.utf16)
        let chunkSize = 20   // CGEvent recommends short Unicode strings per event

        var index = 0
        while index < units.count {
            var chunk = Array(units[index..<min(index + chunkSize, units.count)])

            if let down = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: true) {
                down.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: &chunk)
                down.post(tap: .cghidEventTap)
            }
            if let up = CGEvent(keyboardEventSource: source, virtualKey: 0, keyDown: false) {
                up.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: &chunk)
                up.post(tap: .cghidEventTap)
            }
            index += chunkSize
        }
    }
}
