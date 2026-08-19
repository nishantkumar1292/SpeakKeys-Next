// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import java.net.URI

/** Who owns audio capture for a recognition session. */
enum class AudioOwnership {
    /** SpeakKeys records 16-bit PCM and feeds it to the recognizer. */
    APP_PCM,

    /** The recognition engine opens and owns the microphone. */
    ENGINE_MIC,
}

enum class LocalSpeechRuntime {
    VOSK,
    ANDROID_ON_DEVICE,
}

enum class SpeechStyle {
    MOSTLY_HINDI,
    INDIAN_ENGLISH,
    HINDI_AND_ENGLISH,
}

data class LocalModelLicense(
    val name: String,
    val url: String,
)

/**
 * Immutable, security-sensitive metadata for a downloadable model.
 *
 * URLs, sizes, roots, and digests are intentionally part of the shipped catalog instead of being
 * accepted from UI or worker input. A worker receives only a stable model ID and resolves the rest
 * here, preventing arbitrary URLs and filesystem paths from entering the installer.
 */
data class LocalModelArtifact(
    val url: String,
    val archiveFileName: String,
    val archiveSha256: String,
    val archiveBytes: Long,
    val extractedBytes: Long,
    val archiveRootDirectory: String,
    val installationDirectory: String,
    val requiredRelativeFiles: Set<String>,
) {
    init {
        val uri = URI(url)
        require(uri.scheme == "https") { "Model downloads must use HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Model download URL must have a host" }
        require(SAFE_FILE_NAME.matches(archiveFileName)) { "Unsafe archive filename" }
        require(SHA_256.matches(archiveSha256)) { "Expected a lowercase SHA-256 digest" }
        require(archiveBytes > 0L) { "Archive size must be positive" }
        require(extractedBytes > 0L) { "Extracted size must be positive" }
        require(SAFE_FILE_NAME.matches(archiveRootDirectory)) { "Unsafe archive root" }
        require(SAFE_FILE_NAME.matches(installationDirectory)) { "Unsafe install directory" }
        require(requiredRelativeFiles.isNotEmpty()) { "At least one model file is required" }
        require(requiredRelativeFiles.all(::isSafeRelativePath)) { "Unsafe required model path" }
    }

    companion object {
        private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        private val SHA_256 = Regex("[0-9a-f]{64}")

        internal fun isSafeRelativePath(path: String): Boolean {
            if (path.isBlank() || path.startsWith('/') || '\\' in path || '\u0000' in path) {
                return false
            }
            return path.split('/').all { component ->
                component.isNotEmpty() && component != "." && component != ".."
            }
        }
    }
}

data class LocalSpeechModelDescriptor(
    /** Stable persistence and integration identifier. Never derive a filesystem path from it. */
    val stableId: String,
    val runtime: LocalSpeechRuntime,
    val displayName: String,
    val shortDescription: String,
    val languageTags: List<String>,
    val speechStyle: SpeechStyle,
    val audioOwnership: AudioOwnership,
    val supportsStreamingResults: Boolean,
    val supportsLanguageSwitch: Boolean,
    val license: LocalModelLicense,
    val artifact: LocalModelArtifact? = null,
)

/**
 * Curated local recognition choices. Artifact values were verified against Alpha Cephei's official
 * downloads on 2026-08-05. The catalog is deliberately closed; adding a model is a code-reviewed
 * release operation.
 */
object LocalSpeechModelCatalog {
    const val VOSK_HINDI_SMALL_ID = "vosk://hi-small-0.22"
    const val VOSK_ENGLISH_INDIA_SMALL_ID = "vosk://en-in-small-0.4"
    const val ANDROID_ON_DEVICE_ID = "android://on-device"

    private val apache2 = LocalModelLicense(
        name = "Apache License 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0",
    )

    private val voskRequiredFiles = setOf(
        "am/final.mdl",
        "conf/mfcc.conf",
        "conf/model.conf",
        "graph/Gr.fst",
        "graph/HCLr.fst",
        "graph/phones/word_boundary.int",
    )

    val voskHindiSmall = LocalSpeechModelDescriptor(
        stableId = VOSK_HINDI_SMALL_ID,
        runtime = LocalSpeechRuntime.VOSK,
        displayName = "Instant Hindi",
        shortDescription = "Works without internet and is best when you speak mostly Hindi.",
        languageTags = listOf("hi-IN"),
        speechStyle = SpeechStyle.MOSTLY_HINDI,
        audioOwnership = AudioOwnership.APP_PCM,
        supportsStreamingResults = true,
        supportsLanguageSwitch = false,
        license = apache2,
        artifact = LocalModelArtifact(
            url = "https://alphacephei.com/vosk/models/vosk-model-small-hi-0.22.zip",
            archiveFileName = "vosk-model-small-hi-0.22.zip",
            archiveSha256 = "7c50a10866889f0ac21d912c20537a055a597ed09fc1d3e5bcd798f9f0017e48",
            archiveBytes = 44_458_845L,
            extractedBytes = 82_084_166L,
            archiveRootDirectory = "vosk-model-small-hi-0.22",
            installationDirectory = "vosk-small-hi-0.22-7c50a108",
            requiredRelativeFiles = voskRequiredFiles,
        ),
    )

    val voskEnglishIndiaSmall = LocalSpeechModelDescriptor(
        stableId = VOSK_ENGLISH_INDIA_SMALL_ID,
        runtime = LocalSpeechRuntime.VOSK,
        displayName = "Instant Indian English",
        shortDescription = "Works without internet and is best when you speak mostly English.",
        languageTags = listOf("en-IN"),
        speechStyle = SpeechStyle.INDIAN_ENGLISH,
        audioOwnership = AudioOwnership.APP_PCM,
        supportsStreamingResults = true,
        supportsLanguageSwitch = false,
        license = apache2,
        artifact = LocalModelArtifact(
            url = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip",
            archiveFileName = "vosk-model-small-en-in-0.4.zip",
            archiveSha256 = "20663dcac4d5cb783a579c54d98339344a688e4ec6e1b4a4b059fd1235454cc7",
            archiveBytes = 37_573_330L,
            extractedBytes = 56_700_192L,
            archiveRootDirectory = "vosk-model-small-en-in-0.4",
            installationDirectory = "vosk-small-en-in-0.4-20663dca",
            requiredRelativeFiles = voskRequiredFiles,
        ),
    )

    val androidOnDevice = LocalSpeechModelDescriptor(
        stableId = ANDROID_ON_DEVICE_ID,
        runtime = LocalSpeechRuntime.ANDROID_ON_DEVICE,
        displayName = "Phone's built-in voice typing",
        shortDescription = "Uses speech recognition already available on this phone.",
        languageTags = listOf("hi-IN", "en-IN"),
        speechStyle = SpeechStyle.HINDI_AND_ENGLISH,
        audioOwnership = AudioOwnership.ENGINE_MIC,
        supportsStreamingResults = true,
        // Android 14 exposes a language-switch request, but recognition services may ignore it.
        // Runtime UI reports installed packs without promising that the service will switch.
        supportsLanguageSwitch = false,
        license = LocalModelLicense(
            name = "Android system component",
            url = "https://developer.android.com/reference/android/speech/SpeechRecognizer",
        ),
    )

    val all: List<LocalSpeechModelDescriptor> = listOf(
        androidOnDevice,
        voskHindiSmall,
        voskEnglishIndiaSmall,
    )

    val downloadable: List<LocalSpeechModelDescriptor> = all.filter { it.artifact != null }

    private val byId = all.associateBy(LocalSpeechModelDescriptor::stableId)

    fun find(stableId: String): LocalSpeechModelDescriptor? = byId[stableId]

    fun require(stableId: String): LocalSpeechModelDescriptor =
        requireNotNull(find(stableId)) { "Unknown local speech model: $stableId" }
}
