// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.voice.local

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

internal fun interface HttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

internal object DefaultHttpConnectionFactory : HttpConnectionFactory {
    override fun open(url: URL): HttpURLConnection = url.openConnection() as HttpURLConnection
}

internal fun interface ModelFileOutputFactory {
    fun open(file: File, append: Boolean): FileOutputStream
}

internal object DefaultModelFileOutputFactory : ModelFileOutputFactory {
    override fun open(file: File, append: Boolean): FileOutputStream = FileOutputStream(file, append)
}

/** A bounded HTTPS downloader that resumes only when the server proves the requested range. */
internal class ResumableModelDownloader(
    private val outputFactory: ModelFileOutputFactory = DefaultModelFileOutputFactory,
    private val connectionFactory: HttpConnectionFactory = DefaultHttpConnectionFactory,
) {
    fun download(
        artifact: LocalModelArtifact,
        partialFile: File,
        shouldContinue: () -> Boolean = { true },
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val downloadDirectory = requireNotNull(partialFile.parentFile)
        if (!downloadDirectory.mkdirs() && !downloadDirectory.isDirectory) {
            throw filesystemFailure("Could not create model download directory")
        }

        var existingBytes = partialFile.takeIf(File::isFile)?.length() ?: 0L
        if (existingBytes > artifact.archiveBytes) {
            if (!partialFile.delete()) throw filesystemFailure("Could not discard oversized download")
            existingBytes = 0L
        }
        if (existingBytes == artifact.archiveBytes) {
            if (Sha256.of(partialFile) == artifact.archiveSha256) {
                onProgress(existingBytes, artifact.archiveBytes)
                return
            }
            if (!partialFile.delete()) throw filesystemFailure("Could not discard corrupt download")
            existingBytes = 0L
        }

        checkCancelled(shouldContinue)
        val uri = URI(artifact.url)
        if (uri.scheme != "https") {
            throw integrityFailure("Refusing a non-HTTPS model download")
        }
        val connection = try {
            connectionFactory.open(uri.toURL())
        } catch (failure: IOException) {
            throw networkFailure("Could not open model download", failure)
        }

        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            if (existingBytes > 0L) {
                connection.setRequestProperty("Range", "bytes=$existingBytes-")
            }

            val status = try {
                connection.responseCode
            } catch (failure: IOException) {
                throw networkFailure("Could not connect to the model host", failure)
            }

            val append: Boolean
            val writeOffset: Long
            val expectedResponseBytes: Long
            when (status) {
                HttpURLConnection.HTTP_PARTIAL -> {
                    if (existingBytes == 0L) {
                        throw integrityFailure("The model host returned an unexpected partial response")
                    }
                    val range = parseContentRange(connection.getHeaderField("Content-Range"))
                    if (range.start != existingBytes || range.total != artifact.archiveBytes ||
                        range.end < range.start || range.end >= artifact.archiveBytes
                    ) {
                        throw integrityFailure("The model host returned a mismatched byte range")
                    }
                    append = true
                    writeOffset = existingBytes
                    expectedResponseBytes = range.end - range.start + 1L
                }

                HttpURLConnection.HTTP_OK -> {
                    // A server may ignore Range. Restart safely instead of appending duplicate data.
                    append = false
                    writeOffset = 0L
                    expectedResponseBytes = artifact.archiveBytes
                }

                HttpURLConnection.HTTP_CLIENT_TIMEOUT,
                HTTP_TOO_MANY_REQUESTS,
                in 500..599,
                -> throw networkFailure("The model host is temporarily unavailable (HTTP $status)")

                else -> throw LocalModelInstallException(
                    LocalModelInstallError.HTTP,
                    retryable = false,
                    message = "Model download failed (HTTP $status)",
                )
            }

            val contentEncoding = connection.getHeaderField("Content-Encoding")
            if (!contentEncoding.isNullOrBlank() && !contentEncoding.equals("identity", true)) {
                throw integrityFailure("The model host returned encoded content")
            }
            val contentLength = connection.contentLengthLong
            if (contentLength >= 0L && contentLength != expectedResponseBytes) {
                throw integrityFailure("The model host returned an unexpected content length")
            }

            var downloaded = writeOffset
            var responseBytes = 0L
            val input = try {
                BufferedInputStream(connection.inputStream)
            } catch (failure: IOException) {
                throw networkFailure("Could not read the model download", failure)
            }
            val fileOutput = try {
                outputFactory.open(partialFile, append)
            } catch (failure: IOException) {
                runCatching(input::close)
                throw filesystemFailure("Could not open local model storage", failure)
            }
            val output = BufferedOutputStream(fileOutput)
            try {
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    checkCancelled(shouldContinue)
                    val read = try {
                        input.read(buffer)
                    } catch (failure: IOException) {
                        throw networkFailure("Model download was interrupted", failure)
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    responseBytes = Math.addExact(responseBytes, read.toLong())
                    downloaded = Math.addExact(downloaded, read.toLong())
                    if (responseBytes > expectedResponseBytes || downloaded > artifact.archiveBytes) {
                        throw integrityFailure("The model download exceeded its pinned size")
                    }
                    try {
                        output.write(buffer, 0, read)
                    } catch (failure: IOException) {
                        throw filesystemFailure("Could not write local model storage", failure)
                    }
                    onProgress(downloaded, artifact.archiveBytes)
                }
                try {
                    output.flush()
                    fileOutput.fd.sync()
                } catch (failure: IOException) {
                    throw filesystemFailure("Could not finish writing local model storage", failure)
                }
            } catch (failure: LocalModelInstallException) {
                throw failure
            } catch (failure: ArithmeticException) {
                throw integrityFailure("Model download size overflowed", failure)
            } finally {
                runCatching(output::close)
                runCatching(input::close)
            }

            if (responseBytes != expectedResponseBytes || partialFile.length() != artifact.archiveBytes) {
                throw networkFailure("Model download ended before all bytes arrived")
            }
            if (Sha256.of(partialFile) != artifact.archiveSha256) {
                partialFile.delete()
                throw integrityFailure("Downloaded model failed its SHA-256 check")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parseContentRange(value: String?): ContentRange {
        val match = value?.let(CONTENT_RANGE::matchEntire)
            ?: throw integrityFailure("The model host omitted a valid Content-Range")
        return try {
            ContentRange(
                start = match.groupValues[1].toLong(),
                end = match.groupValues[2].toLong(),
                total = match.groupValues[3].toLong(),
            )
        } catch (failure: NumberFormatException) {
            throw integrityFailure("The model host returned an invalid Content-Range", failure)
        }
    }

    private fun checkCancelled(shouldContinue: () -> Boolean) {
        if (!shouldContinue()) throw IOException("Local model download was cancelled")
    }

    private fun networkFailure(message: String, cause: Throwable? = null) =
        LocalModelInstallException(
            LocalModelInstallError.NETWORK,
            retryable = true,
            message = message,
            cause = cause,
        )

    private fun integrityFailure(message: String, cause: Throwable? = null) =
        LocalModelInstallException(
            LocalModelInstallError.INTEGRITY,
            retryable = false,
            message = message,
            cause = cause,
        )

    private fun filesystemFailure(message: String, cause: Throwable? = null) =
        LocalModelInstallException(
            LocalModelInstallError.FILESYSTEM,
            retryable = true,
            message = message,
            cause = cause,
        )

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val COPY_BUFFER_BYTES = 32 * 1024
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val USER_AGENT = "SpeakKeys-Android/local-model-installer"
        private val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
