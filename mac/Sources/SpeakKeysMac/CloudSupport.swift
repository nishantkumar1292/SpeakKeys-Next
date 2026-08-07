import AVFoundation
import Foundation

// MARK: - WAV encoding

/// Encodes 16-bit mono PCM samples into an in-memory WAV file. Mirrors the
/// `WavEncoder` used by the Android/shared SpeakKeys code so cloud backends behave
/// identically across platforms. arm64/x86_64 are little-endian, which is what WAV
/// wants, so the sample bytes are copied verbatim.
enum WavWriter {
    static func wav(samples: [Int16], sampleRate: Int) -> Data {
        let byteRate = sampleRate * 2          // mono, 16-bit
        let dataSize = samples.count * 2
        let fileSize = 36 + dataSize

        var d = Data(capacity: 44 + dataSize)

        func ascii(_ s: String) { d.append(s.data(using: .ascii)!) }
        func u32(_ v: Int) {
            var x = UInt32(truncatingIfNeeded: v).littleEndian
            withUnsafeBytes(of: &x) { d.append(contentsOf: $0) }
        }
        func u16(_ v: Int) {
            var x = UInt16(truncatingIfNeeded: v).littleEndian
            withUnsafeBytes(of: &x) { d.append(contentsOf: $0) }
        }

        ascii("RIFF"); u32(fileSize); ascii("WAVE")
        ascii("fmt "); u32(16); u16(1); u16(1)          // PCM, mono
        u32(sampleRate); u32(byteRate); u16(2); u16(16) // block align, bits/sample
        ascii("data"); u32(dataSize)

        samples.withUnsafeBufferPointer { d.append($0) }
        return d
    }
}

// MARK: - Thread-safe audio accumulation for batch (cloud) engines

/// Collects audio while the user holds the key and exposes it as a 16 kHz mono WAV
/// blob on release. Accepts the microphone's native buffers and downsamples them to
/// 16 kHz mono Int16 internally (via an `AVAudioConverter` set up in `begin`). Buffers
/// arrive on the audio thread, so appends are lock-guarded. Capped at 60s.
final class AudioAccumulator {
    private var samples: [Int16] = []
    private let lock = NSLock()
    private let maxSamples = Int(AudioFormats.sampleRate) * 60

    private var converter: AVAudioConverter?
    private var inputFormat: AVAudioFormat?

    /// Prepare for a new utterance with the mic's native format.
    func begin(inputFormat: AVAudioFormat) {
        lock.lock(); samples.removeAll(keepingCapacity: true); lock.unlock()
        self.inputFormat = inputFormat

        let alreadyTarget = inputFormat.sampleRate == AudioFormats.sampleRate
            && inputFormat.channelCount == 1
            && inputFormat.commonFormat == .pcmFormatInt16
        converter = alreadyTarget ? nil : AVAudioConverter(from: inputFormat, to: AudioFormats.mono16k)
    }

    func reset() {
        lock.lock(); samples.removeAll(keepingCapacity: true); lock.unlock()
    }

    func append(_ buffer: AVAudioPCMBuffer) {
        let target = convertToTarget(buffer)
        guard let channel = target.int16ChannelData else { return }
        let count = Int(target.frameLength)
        guard count > 0 else { return }
        lock.lock()
        if samples.count < maxSamples {
            samples.append(contentsOf: UnsafeBufferPointer(start: channel[0], count: count))
        }
        lock.unlock()
    }

    /// Downsamples/downmixes a native buffer to 16 kHz mono Int16.
    private func convertToTarget(_ buffer: AVAudioPCMBuffer) -> AVAudioPCMBuffer {
        guard let converter, let inputFormat else { return buffer }
        let ratio = AudioFormats.sampleRate / inputFormat.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: AudioFormats.mono16k, frameCapacity: capacity) else {
            return buffer
        }
        var fed = false
        let inputBlock: AVAudioConverterInputBlock = { _, statusOut in
            if fed { statusOut.pointee = .noDataNow; return nil }
            fed = true
            statusOut.pointee = .haveData
            return buffer
        }
        var error: NSError?
        converter.convert(to: out, error: &error, withInputFrom: inputBlock)
        return out
    }

    var isEmpty: Bool {
        lock.lock(); defer { lock.unlock() }
        return samples.isEmpty
    }

    func makeWav() -> Data {
        lock.lock(); let snapshot = samples; lock.unlock()
        return WavWriter.wav(samples: snapshot, sampleRate: Int(AudioFormats.sampleRate))
    }

    /// Build a single 16 kHz mono Int16 PCM buffer holding all accumulated audio
    /// (used by the Apple engine, which recognizes the whole utterance at once).
    func makeBuffer() -> AVAudioPCMBuffer? {
        lock.lock(); let snapshot = samples; lock.unlock()
        guard !snapshot.isEmpty,
              let buffer = AVAudioPCMBuffer(pcmFormat: AudioFormats.mono16k,
                                            frameCapacity: AVAudioFrameCount(snapshot.count)),
              let channel = buffer.int16ChannelData else { return nil }
        buffer.frameLength = AVAudioFrameCount(snapshot.count)
        snapshot.withUnsafeBufferPointer { src in
            channel[0].update(from: src.baseAddress!, count: snapshot.count)
        }
        return buffer
    }
}

// MARK: - Multipart HTTP helpers

private extension Data {
    mutating func appendString(_ s: String) {
        if let d = s.data(using: .utf8) { append(d) }
    }
}

/// Minimal multipart/form-data transcription clients for the cloud backends.
/// Endpoints/fields match the shared SpeakKeys recognizers.
enum CloudHTTP {

    static func transcribeWhisper(apiKey: String, wav: Data, language: String? = nil) async throws -> String {
        var fields: [(String, String)] = [("model", "whisper-1")]
        if let language, !language.isEmpty, language != "und" { fields.append(("language", language)) }

        let data = try await postMultipart(
            url: URL(string: "https://api.openai.com/v1/audio/transcriptions")!,
            headers: ["Authorization": "Bearer \(apiKey)"],
            fields: fields,
            fileData: wav,
            errorLabel: "Whisper"
        )
        return jsonString(data, key: "text")
    }

    static func transcribeSarvam(apiKey: String, wav: Data, languageCode: String = "unknown") async throws -> String {
        let data = try await postMultipart(
            url: URL(string: "https://api.sarvam.ai/speech-to-text")!,
            headers: ["api-subscription-key": apiKey],
            fields: [
                ("model", "saaras:v3"),
                ("mode", "translit"),
                ("language_code", languageCode),
                ("with_timestamps", "false"),
            ],
            fileData: wav,
            errorLabel: "Sarvam"
        )
        return jsonString(data, key: "transcript")
    }

    // MARK: helpers

    private static func postMultipart(
        url: URL,
        headers: [String: String],
        fields: [(String, String)],
        fileData: Data,
        errorLabel: String
    ) async throws -> Data {
        let boundary = "Boundary-\(UUID().uuidString)"
        var body = Data()

        body.appendString("--\(boundary)\r\n")
        body.appendString("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n")
        body.appendString("Content-Type: audio/wav\r\n\r\n")
        body.append(fileData)
        body.appendString("\r\n")

        for (name, value) in fields {
            body.appendString("--\(boundary)\r\n")
            body.appendString("Content-Disposition: form-data; name=\"\(name)\"\r\n\r\n")
            body.appendString("\(value)\r\n")
        }
        body.appendString("--\(boundary)--\r\n")

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        for (k, v) in headers { request.setValue(v, forHTTPHeaderField: k) }
        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw SpeechEngineError.failed("\(errorLabel): no HTTP response")
        }
        guard (200..<300).contains(http.statusCode) else {
            let snippet = String(data: data, encoding: .utf8)?.prefix(200) ?? ""
            throw SpeechEngineError.failed("\(errorLabel) API error \(http.statusCode): \(snippet)")
        }
        return data
    }

    private static func jsonString(_ data: Data, key: String) -> String {
        guard
            let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
            let text = obj[key] as? String
        else { return "" }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}
