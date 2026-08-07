import Foundation
import os

/// Diagnostic logger. Writes to BOTH the unified log and a plain text file at
/// `/tmp/speakkeys.log` (the file is the reliable one for debugging — read it with
/// `tail -f /tmp/speakkeys.log`). The file is truncated once per app launch.
enum Log {
    private static let osLogger = Logger(subsystem: "com.speakkeys.mac", category: "speakkeys")
    private static let lock = NSLock()
    static let path = "/tmp/speakkeys.log"

    private static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss.SSS"
        return f
    }()

    /// Start a fresh log file for this launch.
    static func start() {
        lock.lock(); defer { lock.unlock() }
        try? "=== SpeakKeys launched ===\n".data(using: .utf8)?.write(to: URL(fileURLWithPath: path))
    }

    static func info(_ message: @autoclosure () -> String) { write("INFO", message()) }
    static func error(_ message: @autoclosure () -> String) { write("ERROR", message()) }

    private static func write(_ level: String, _ text: String) {
        osLogger.notice("\(text, privacy: .public)")
        let line = "\(formatter.string(from: Date())) [\(level)] \(text)\n"
        guard let data = line.data(using: .utf8) else { return }
        lock.lock(); defer { lock.unlock() }
        if let handle = FileHandle(forWritingAtPath: path) {
            handle.seekToEndOfFile()
            handle.write(data)
            try? handle.close()
        } else {
            try? data.write(to: URL(fileURLWithPath: path))
        }
    }
}
