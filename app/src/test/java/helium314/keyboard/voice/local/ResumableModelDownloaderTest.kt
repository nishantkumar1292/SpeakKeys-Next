// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class ResumableModelDownloaderTest {
    private lateinit var temporaryDirectory: File

    @BeforeTest
    fun setUp() {
        temporaryDirectory = Files.createTempDirectory("speakkeys-model-download").toFile()
    }

    @AfterTest
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun resumesOnlyFromMatchingContentRange() {
        val expected = "hello world".encodeToByteArray()
        val partial = File(temporaryDirectory, "model.part").apply {
            writeBytes("hello".encodeToByteArray())
        }
        val connection = FakeHttpConnection(
            status = HttpURLConnection.HTTP_PARTIAL,
            body = " world".encodeToByteArray(),
            headers = mapOf("Content-Range" to "bytes 5-10/11"),
            contentLength = 6L,
        )

        ResumableModelDownloader { connection }.download(artifact(expected), partial)

        assertEquals("bytes=5-", connection.getRequestProperty("Range"))
        assertTrue(expected.contentEquals(partial.readBytes()))
    }

    @Test
    fun restartsWhenServerIgnoresRange() {
        val expected = "complete model".encodeToByteArray()
        val partial = File(temporaryDirectory, "model.part").apply {
            writeText("stale")
        }
        val connection = FakeHttpConnection(
            status = HttpURLConnection.HTTP_OK,
            body = expected,
            contentLength = expected.size.toLong(),
        )

        ResumableModelDownloader { connection }.download(artifact(expected), partial)

        assertEquals("bytes=5-", connection.getRequestProperty("Range"))
        assertTrue(expected.contentEquals(partial.readBytes()))
    }

    @Test
    fun rejectsMismatchedContentRangeBeforeAppending() {
        val expected = "hello world".encodeToByteArray()
        val partial = File(temporaryDirectory, "model.part").apply { writeText("hello") }
        val connection = FakeHttpConnection(
            status = HttpURLConnection.HTTP_PARTIAL,
            body = " world".encodeToByteArray(),
            headers = mapOf("Content-Range" to "bytes 4-10/11"),
            contentLength = 7L,
        )

        val failure = expectInstallFailure {
            ResumableModelDownloader { connection }.download(artifact(expected), partial)
        }

        assertEquals(LocalModelInstallError.INTEGRITY, failure.error)
        assertEquals("hello", partial.readText())
    }

    @Test
    fun deletesCompletedDownloadWhenDigestDoesNotMatch() {
        val expected = "expected".encodeToByteArray()
        val received = "different".encodeToByteArray()
        val partial = File(temporaryDirectory, "model.part")
        val connection = FakeHttpConnection(
            status = HttpURLConnection.HTTP_OK,
            body = received,
            contentLength = received.size.toLong(),
        )
        val pinnedToReceivedSize = artifact(
            bytes = ByteArray(received.size) { 1 },
            expectedSizeOverride = received.size.toLong(),
        )

        val failure = expectInstallFailure {
            ResumableModelDownloader { connection }.download(pinnedToReceivedSize, partial)
        }

        assertEquals(LocalModelInstallError.INTEGRITY, failure.error)
        assertFalse(partial.exists())
        assertFalse(expected.contentEquals(received))
    }

    @Test
    fun preservesPartialBytesWhenConnectionEndsEarly() {
        val expected = "a longer model".encodeToByteArray()
        val received = "short".encodeToByteArray()
        val partial = File(temporaryDirectory, "model.part")
        val connection = FakeHttpConnection(
            status = HttpURLConnection.HTTP_OK,
            body = received,
            contentLength = -1L,
        )

        val failure = expectInstallFailure {
            ResumableModelDownloader { connection }.download(artifact(expected), partial)
        }

        assertEquals(LocalModelInstallError.NETWORK, failure.error)
        assertTrue(failure.retryable)
        assertTrue(received.contentEquals(partial.readBytes()))
    }

    @Test
    fun localOutputFailureIsNotMisreportedAsANetworkProblem() {
        val expected = "model".encodeToByteArray()
        val partial = File(temporaryDirectory, "model.part")
        val connection = FakeHttpConnection(
            status = HttpURLConnection.HTTP_OK,
            body = expected,
            contentLength = expected.size.toLong(),
        )

        val failure = expectInstallFailure {
            ResumableModelDownloader(
                outputFactory = ModelFileOutputFactory { _, _ ->
                    throw IOException("disk unavailable")
                },
                connectionFactory = HttpConnectionFactory { connection },
            ).download(artifact(expected), partial)
        }

        assertEquals(LocalModelInstallError.FILESYSTEM, failure.error)
        assertTrue(failure.retryable)
    }

    private fun artifact(bytes: ByteArray, expectedSizeOverride: Long = bytes.size.toLong()) =
        LocalModelArtifact(
            url = "https://example.test/model.zip",
            archiveFileName = "model.zip",
            archiveSha256 = sha256(bytes),
            archiveBytes = expectedSizeOverride,
            extractedBytes = 1L,
            archiveRootDirectory = "model",
            installationDirectory = "model-v1",
            requiredRelativeFiles = setOf("am/final.mdl"),
        )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun expectInstallFailure(block: () -> Unit): LocalModelInstallException = try {
        block()
        fail("Expected LocalModelInstallException")
    } catch (failure: LocalModelInstallException) {
        failure
    }

    private class FakeHttpConnection(
        private val status: Int,
        private val body: ByteArray,
        private val headers: Map<String, String> = emptyMap(),
        private val contentLength: Long = body.size.toLong(),
    ) : HttpURLConnection(URL("https://example.test/model.zip")) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream(): InputStream = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?): String? = headers[name]
        override fun getContentLengthLong(): Long = contentLength
    }
}
