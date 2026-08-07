import AppKit
import CoreGraphics

/// Watches for the **right Option (⌥) key** being held down anywhere in the system
/// using a low-level CGEventTap on `flagsChanged` events.
///
/// We distinguish the right Option key from the left one via the device-dependent
/// modifier bit `NX_DEVICERALTKEYMASK` (0x40), which appears in the raw event flags.
/// The tap is *listen-only*, so it never swallows the key — Option still behaves
/// normally for everything else.
///
/// Requires Accessibility permission; `start()` returns `false` if it isn't granted
/// yet (the tap fails to create), letting the caller prompt the user.
final class GlobalHotkeyMonitor {

    var onPress: (() -> Void)?
    var onRelease: (() -> Void)?

    private var eventTap: CFMachPort?
    private var runLoopSource: CFRunLoopSource?
    private var rightOptionDown = false

    /// keycode reported by `flagsChanged` for the right Option key.
    private static let rightOptionKeyCode: Int64 = 61
    /// Device-dependent flag bit set while the right Option key is held.
    private static let rightAltMask: UInt64 = 0x40

    var isRunning: Bool { eventTap != nil }

    @discardableResult
    func start() -> Bool {
        guard eventTap == nil else { return true }

        let mask = CGEventMask(1 << CGEventType.flagsChanged.rawValue)

        // @convention(c) callback: must not capture context, so `self` is threaded
        // through `userInfo`.
        let callback: CGEventTapCallBack = { _, type, event, userInfo in
            guard let userInfo else { return Unmanaged.passUnretained(event) }
            let monitor = Unmanaged<GlobalHotkeyMonitor>.fromOpaque(userInfo).takeUnretainedValue()
            monitor.handle(type: type, event: event)
            return Unmanaged.passUnretained(event)
        }

        guard let tap = CGEvent.tapCreate(
            tap: .cgSessionEventTap,
            place: .headInsertEventTap,
            options: .listenOnly,
            eventsOfInterest: mask,
            callback: callback,
            userInfo: Unmanaged.passUnretained(self).toOpaque()
        ) else {
            return false   // almost always: Accessibility permission not granted yet
        }

        let source = CFMachPortCreateRunLoopSource(kCFAllocatorDefault, tap, 0)
        CFRunLoopAddSource(CFRunLoopGetMain(), source, .commonModes)
        CGEvent.tapEnable(tap: tap, enable: true)

        eventTap = tap
        runLoopSource = source
        Log.info("hotkey: event tap created and enabled")
        return true
    }

    func stop() {
        if let tap = eventTap { CGEvent.tapEnable(tap: tap, enable: false) }
        if let source = runLoopSource {
            CFRunLoopRemoveSource(CFRunLoopGetMain(), source, .commonModes)
        }
        eventTap = nil
        runLoopSource = nil
        rightOptionDown = false
    }

    private func handle(type: CGEventType, event: CGEvent) {
        // The OS disables a tap if it ever blocks; just re-enable and move on.
        if type == .tapDisabledByTimeout || type == .tapDisabledByUserInput {
            if let tap = eventTap { CGEvent.tapEnable(tap: tap, enable: true) }
            return
        }
        guard type == .flagsChanged else { return }

        let keyCode = event.getIntegerValueField(.keyboardEventKeycode)
        guard keyCode == Self.rightOptionKeyCode else { return }

        let isDown = (event.flags.rawValue & Self.rightAltMask) != 0
        if isDown, !rightOptionDown {
            rightOptionDown = true
            Log.info("hotkey: right Option DOWN")
            DispatchQueue.main.async { [weak self] in self?.onPress?() }
        } else if !isDown, rightOptionDown {
            rightOptionDown = false
            Log.info("hotkey: right Option UP")
            DispatchQueue.main.async { [weak self] in self?.onRelease?() }
        }
    }
}
