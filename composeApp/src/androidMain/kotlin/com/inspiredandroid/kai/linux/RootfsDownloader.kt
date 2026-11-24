package com.inspiredandroid.kai.linux

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

private const val BUFFER_SIZE = 64 * 1024

/**
 * Streams a rootfs tarball to disk, trying each candidate URL in turn.
 *
 * Alpine publishes the same minirootfs on several mirrors, so a mirror that is
 * down or rate-limiting costs a retry rather than the install. Debian can fall
 * back to the independent Termux release if the LXC image is unavailable.
 */
class RootfsDownloader(private val httpClient: HttpClient) {

    /**
     * Cancellable through the calling coroutine. A failed URL deletes its partial
     * file and resets progress so the UI does not show a bar walking backwards.
     */
    suspend fun download(
        sources: List<RootfsSource>,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ): RootfsSource {
        require(sources.isNotEmpty()) { "No download URL for this rootfs" }
        targetFile.parentFile?.mkdirs()
        var lastError: Exception? = null
        val failures = mutableListOf<String>()
        for ((index, source) in sources.withIndex()) {
            try {
                downloadFrom(source, targetFile, onProgress)
                return source
            } catch (e: CancellationException) {
                targetFile.delete()
                throw e
            } catch (e: Exception) {
                lastError = e
                val host = URI.create(source.url).host
                failures += "$host: ${e.message?.lineSequence()?.firstOrNull()?.take(120) ?: e.javaClass.simpleName}"
                targetFile.delete()
                if (index < sources.lastIndex) onProgress(0f)
            }
        }
        throw IOException("Rootfs downloads failed (${failures.joinToString("; ")})", lastError)
    }

    private suspend fun downloadFrom(
        source: RootfsSource,
        targetFile: File,
        onProgress: (Float) -> Unit,
    ) {
        httpClient.prepareGet(source.url).execute { response ->
            if (response.status != HttpStatusCode.OK) {
                throw IOException("HTTP ${response.status.value}")
            }
            val totalBytes = response.contentLength() ?: -1L
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(BUFFER_SIZE)
            var downloadedBytes = 0L
            val digest = source.sha256?.let { MessageDigest.getInstance("SHA-256") }

            FileOutputStream(targetFile).use { output ->
                while (!channel.isClosedForRead) {
                    currentCoroutineContext().ensureActive()
                    val bytesRead = channel.readAvailable(buffer)
                    if (bytesRead < 0) break
                    if (bytesRead == 0) continue
                    output.write(buffer, 0, bytesRead)
                    digest?.update(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalBytes > 0) {
                        onProgress((downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
            if (downloadedBytes == 0L || (totalBytes > 0 && downloadedBytes != totalBytes)) throw IOException("Incomplete download")
            if (digest != null && digest.digest().joinToString("") { "%02x".format(it) } != source.sha256) throw IOException("SHA-256 mismatch")
            onProgress(1f)
        }
    }
}
