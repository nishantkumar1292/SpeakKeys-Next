// swift-tools-version: 6.0
import PackageDescription

// SpeakKeys for Mac — a standalone menu-bar push-to-talk dictation app.
// This Swift package is completely independent of the Android Gradle build
// that lives in the repository root; nothing here is shared with it.
let package = Package(
    name: "SpeakKeysMac",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(
            name: "SpeakKeysMac",
            path: "Sources/SpeakKeysMac",
            // Use the Swift 5 language mode: relaxed strict-concurrency checking
            // keeps the AppKit / AVFoundation / CoreGraphics interop pragmatic.
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    ]
)
