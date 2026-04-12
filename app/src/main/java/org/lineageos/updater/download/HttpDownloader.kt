/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.updater.download

import android.os.SystemClock
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.BodyProgress
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers as KtorHeaders
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlinx.io.asSink
import kotlin.math.roundToLong

/**
 * Downloads update ZIPs while handling direct links, ordinary redirects, and duplicate mirrors.
 */
class HttpDownloader(
    private val sourceUrl: String,
    private val destination: File,
    private val progressListener: DownloadClient.ProgressListener?,
    private val callback: DownloadClient.DownloadCallback,
) : DownloadClient {
    companion object {
        private const val TAG = "HttpDownloader"

        // Same value as AOSP DownloadThread.DEFAULT_TIMEOUT.
        private const val DEFAULT_TIMEOUT = 20_000L

        // Same redirect shape as AOSP DownloadThread, but bounded to avoid loops.
        private const val MAX_REDIRECTS = 5

        /**
         * This interval was decided on by balancing the limit of the system (200ms) and allowing
         * users to press buttons on the notification. If a new notification is presented while a
         * user is tapping a button, their press will be canceled.
         * Same as Firefox AbstractFetchDownloadService.PROGRESS_UPDATE_INTERVAL
         */
        private const val PROGRESS_UPDATE_INTERVAL = 750L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = HttpClient(Android) {
        // AOSP DownloadThread handles redirects explicitly; do the same so duplicate mirrors
        // and downstream redirectors go through one path.
        followRedirects = false
        install(BodyProgress)
        install(HttpTimeout) {
            connectTimeoutMillis = DEFAULT_TIMEOUT
            socketTimeoutMillis = DEFAULT_TIMEOUT
        }
    }

    // Java callers can still race start/resume/cancel through UpdaterController.
    private val jobRef = AtomicReference<Job?>(null)

    override fun start() {
        launchDownload(resume = false)
    }

    override fun resume() {
        launchDownload(resume = true)
    }

    override fun cancel() {
        jobRef.get()?.cancel(CancellationException("Download cancelled"))
    }

    private fun launchDownload(resume: Boolean) {
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                performDownload(resume)
                callback.onSuccess()
            } catch (e: CancellationException) {
                callback.onFailure(true)
                throw e
            } catch (e: Exception) {
                if (!isActive) {
                    callback.onFailure(true)
                    return@launch
                }
                Log.e(TAG, "Download failed for $sourceUrl", e)
                callback.onFailure(false)
            } finally {
                if (jobRef.compareAndSet(this.coroutineContext[Job], null)) {
                    client.close()
                }
            }
        }

        if (jobRef.compareAndSet(null, newJob)) {
            newJob.start()
        } else {
            newJob.cancel()
        }
    }

    private suspend fun performDownload(resume: Boolean) {
        destination.parentFile?.mkdirs()

        if (resume && !destination.exists()) {
            throw CannotResumeException("Destination file is missing")
        }

        val candidateQueue = ArrayDeque<RequestCandidate>().apply {
            add(RequestCandidate(sourceUrl, MAX_REDIRECTS))
        }
        val visitedUrls = mutableSetOf(sourceUrl)
        var lastException: IOException? = null

        while (candidateQueue.isNotEmpty()) {
            val candidate = candidateQueue.removeFirst()
            try {
                val bytesDownloadedSoFar = if (resume) {
                    destination.length().takeIf { it > 0L } ?: 0L
                } else {
                    0L
                }
                if (executeRequest(
                    candidate = candidate,
                    bytesDownloadedSoFar = bytesDownloadedSoFar,
                    candidateQueue = candidateQueue,
                    visitedUrls = visitedUrls,
                )) {
                    return
                }
            } catch (e: CannotResumeException) {
                throw e
            } catch (e: IOException) {
                lastException = e
                if (candidateQueue.isNotEmpty()) {
                    Log.w(TAG, "Download candidate failed, trying next: ${candidate.url}", e)
                }
            }
        }

        throw lastException ?: IOException("No download candidates available for $sourceUrl")
    }

    private suspend fun executeRequest(
        candidate: RequestCandidate,
        bytesDownloadedSoFar: Long,
        candidateQueue: ArrayDeque<RequestCandidate>,
        visitedUrls: MutableSet<String>,
    ): Boolean {
        val progressReporter = progressListener?.let {
            DownloadProgressReporter(bytesDownloadedSoFar, it)
        }
        // OTA ZIPs must stay on the streaming API so the response body is never buffered in
        // memory while we inspect headers, enforce resume semantics, and write to disk.
        return client.prepareGet(candidate.url) {
            applyDownloadHeaders(bytesDownloadedSoFar)
            progressReporter?.let { reporter ->
                onDownload { bytesRead, _ ->
                    reporter.onBytesRead(bytesRead)
                }
            }
        }.execute { httpResponse ->
            when {
                DownloadUtils.isRedirectCode(httpResponse.status.value) -> {
                    enqueueRedirectCandidates(
                        candidate = candidate,
                        responseHeaders = httpResponse.headers,
                        candidateQueue = candidateQueue,
                        visitedUrls = visitedUrls,
                    )
                    false
                }
                httpResponse.status == HttpStatusCode.OK ||
                    httpResponse.status == HttpStatusCode.PartialContent -> {
                    if (bytesDownloadedSoFar == 0L &&
                        httpResponse.status == HttpStatusCode.PartialContent
                    ) {
                        // Matches AOSP DownloadThread: reject unexpected 206 on a fresh download.
                        throw CannotResumeException(
                            "Expected 200 OK on fresh download but received 206 for ${candidate.url}"
                        )
                    }
                    handleDownloadResponse(
                        bytesDownloadedSoFar = bytesDownloadedSoFar,
                        httpResponse = httpResponse,
                        progressReporter = progressReporter,
                    )
                    true
                }
                httpResponse.status == HttpStatusCode.PreconditionFailed ||
                    httpResponse.status == HttpStatusCode.RequestedRangeNotSatisfiable -> {
                    // Matches AOSP DownloadThread HTTP_PRECON_FAILED / HTTP_REQUESTED_RANGE_NOT_SATISFIABLE:
                    // these are resume failures, not transient errors — do not retry on other mirrors.
                    throw CannotResumeException(
                        "Server rejected range request with ${httpResponse.status} for ${candidate.url}"
                    )
                }
                else -> {
                    throw IOException(
                        "Unexpected HTTP status ${httpResponse.status} for ${candidate.url}"
                    )
                }
            }
        }
    }

    private fun enqueueRedirectCandidates(
        candidate: RequestCandidate,
        responseHeaders: KtorHeaders,
        candidateQueue: ArrayDeque<RequestCandidate>,
        visitedUrls: MutableSet<String>,
    ) {
        if (candidate.redirectsRemaining <= 0) {
            throw IOException("Too many redirects for ${candidate.url}")
        }

        val location = responseHeaders[HttpHeaders.Location]
            ?: throw IOException("Redirect response missing Location for ${candidate.url}")
        val candidateUrls = buildRedirectCandidates(
            requestUrl = candidate.url,
            location = location,
            responseHeaders = responseHeaders,
        )

        candidateUrls.asReversed().forEach { requestUrl ->
            if (visitedUrls.add(requestUrl)) {
                candidateQueue.addFirst(
                    RequestCandidate(
                        url = requestUrl,
                        redirectsRemaining = candidate.redirectsRemaining - 1,
                    )
                )
            }
        }
    }

    private fun buildRedirectCandidates(
        requestUrl: String,
        location: String,
        responseHeaders: KtorHeaders,
    ): List<String> {
        val resolvedLocation = try {
            DownloadUtils.resolveRequestUrl(requestUrl, location)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid redirect target $location for $requestUrl", e)
        }

        return buildList {
            add(resolvedLocation)
            DownloadUtils.parseDuplicateLinks(responseHeaders)
                .map(DuplicateLink::url)
                .mapNotNull { duplicateUrl ->
                    try {
                        DownloadUtils.resolveRequestUrl(requestUrl, duplicateUrl)
                    } catch (e: IllegalArgumentException) {
                        Log.w(TAG, "Ignoring invalid duplicate link $duplicateUrl for $requestUrl", e)
                        null
                    }
                }
                .filter { it != resolvedLocation }
                .forEach(::add)
        }
    }

    private suspend fun handleDownloadResponse(
        bytesDownloadedSoFar: Long,
        httpResponse: HttpResponse,
        progressReporter: DownloadProgressReporter?,
    ) {
        val totalSizeBytes = determineTotalBytes(
            httpResponse.headers,
            httpResponse.status,
            bytesDownloadedSoFar,
        )

        if (bytesDownloadedSoFar > 0L) {
            ensureResumeIsSupported(
                responseHeaders = httpResponse.headers,
                status = httpResponse.status,
                bytesDownloadedSoFar = bytesDownloadedSoFar,
            )
        }

        val responseHeaders = ResponseHeaders(httpResponse.headers, totalSizeBytes)
        callback.onResponse(responseHeaders)

        progressReporter?.setTotalSizeBytes(totalSizeBytes)
        progressReporter?.reportResumeBaseline()

        copyResponseBody(
            append = bytesDownloadedSoFar > 0L,
            channel = httpResponse.bodyAsChannel(),
        )

        if (totalSizeBytes > 0L && destination.length() < totalSizeBytes) {
            throw IOException(
                "Download ended early: expected $totalSizeBytes bytes, found ${destination.length()}"
            )
        }
    }

    private fun HttpRequestBuilder.applyDownloadHeaders(bytesDownloadedSoFar: Long) {
        headers {
            // Matches AOSP DownloadThread.addRequestHeaders() for range-safe resume.
            append(HttpHeaders.AcceptEncoding, "identity")
            // Matches AOSP DownloadThread.addRequestHeaders(): avoid connection reuse so
            // cancellation does not leave the server streaming on a kept-alive socket.
            append(HttpHeaders.Connection, "close")
            if (bytesDownloadedSoFar > 0L) {
                append(HttpHeaders.Range, "bytes=$bytesDownloadedSoFar-")
            }
        }
    }

    private fun determineTotalBytes(
        headers: KtorHeaders,
        status: HttpStatusCode,
        bytesDownloadedSoFar: Long,
    ): Long {
        // For 206, Ktor exposes only the remaining Content-Length.
        val rangeInfo = DownloadUtils.parseContentRange(headers[HttpHeaders.ContentRange])
        if (rangeInfo?.totalBytes != null) {
            return rangeInfo.totalBytes
        }

        val contentLength = headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: return 0L
        return if (status == HttpStatusCode.PartialContent) {
            bytesDownloadedSoFar + contentLength
        } else {
            contentLength
        }
    }

    private fun ensureResumeIsSupported(
        responseHeaders: KtorHeaders,
        status: HttpStatusCode,
        bytesDownloadedSoFar: Long,
    ) {
        if (status != HttpStatusCode.PartialContent) {
            throw CannotResumeException("Server ignored byte range resume")
        }

        val rangeInfo = DownloadUtils.parseContentRange(responseHeaders[HttpHeaders.ContentRange])
            ?: throw CannotResumeException("Missing or invalid Content-Range header")

        if (rangeInfo.start != bytesDownloadedSoFar) {
            throw CannotResumeException(
                "Requested byte $bytesDownloadedSoFar but server resumed from ${rangeInfo.start}"
            )
        }
    }

    private suspend fun copyResponseBody(
        append: Boolean,
        channel: ByteReadChannel,
    ) {
        FileOutputStream(destination, append).use { stream ->
            val sink = stream.asSink()
            channel.readTo(sink)
            sink.flush()
        }
    }

    // Matches AOSP DownloadThread: do not silently restart when resume is rejected.
    private class CannotResumeException(message: String) : IOException(message)

    private data class RequestCandidate(
        val url: String,
        val redirectsRemaining: Int,
    )

    private class DownloadProgressReporter(
        private val bytesDownloadedSoFar: Long,
        private val progressListener: DownloadClient.ProgressListener,
    ) {
        private var totalSizeBytes = 0L
        private var speed = 0L
        private var smoothedEtaSeconds = -1.0
        private var lastBytesCopied = bytesDownloadedSoFar
        private var lastReportTime = SystemClock.elapsedRealtime()

        fun setTotalSizeBytes(totalSizeBytes: Long) {
            this.totalSizeBytes = totalSizeBytes
        }

        fun reportResumeBaseline() {
            if (bytesDownloadedSoFar > 0L && totalSizeBytes > 0L) {
                progressListener.update(bytesDownloadedSoFar, totalSizeBytes, 0L, -1L)
                lastReportTime = SystemClock.elapsedRealtime()
            }
        }

        fun onBytesRead(responseBytesRead: Long) {
            val currentBytesCopied = bytesDownloadedSoFar + responseBytesRead
            val now = SystemClock.elapsedRealtime()
            val isComplete = totalSizeBytes in 1..currentBytesCopied
            val shouldReport = now - lastReportTime >= PROGRESS_UPDATE_INTERVAL || isComplete
            if (!shouldReport) {
                return
            }

            val elapsed = (now - lastReportTime).coerceAtLeast(1L)
            val bytesDelta = currentBytesCopied - lastBytesCopied
            // Matches AOSP DownloadThread.updateProgress() speed EMA.
            val sampleSpeed = bytesDelta * 1000L / elapsed
            speed = if (speed == 0L) sampleSpeed else ((speed * 3) + sampleSpeed) / 4

            val etaSeconds = if (isComplete) {
                0L
            } else {
                smoothedEtaSeconds = DownloadUtils.calculateEta(
                    totalSizeBytes = totalSizeBytes,
                    bytesDownloadedSoFar = currentBytesCopied,
                    speed = speed,
                    lastSmoothedEta = smoothedEtaSeconds,
                )
                smoothedEtaSeconds.takeIf { it >= 0.0 }?.roundToLong() ?: -1L
            }

            progressListener.update(currentBytesCopied, totalSizeBytes, speed, etaSeconds)

            lastBytesCopied = currentBytesCopied
            lastReportTime = now
        }
    }

    private class ResponseHeaders(
        headers: KtorHeaders,
        totalBytes: Long,
    ) : DownloadClient.Headers {
        // UpdaterController expects full file size even when the server returned 206.
        private val values = buildMap {
            headers.names().forEach { name ->
                headers[name]?.let { put(name.lowercase(Locale.US), it) }
            }
            if (totalBytes > 0L) {
                put(HttpHeaders.ContentLength.lowercase(Locale.US), totalBytes.toString())
            }
        }

        override fun get(name: String): String? = values[name.lowercase(Locale.US)]
    }
}
