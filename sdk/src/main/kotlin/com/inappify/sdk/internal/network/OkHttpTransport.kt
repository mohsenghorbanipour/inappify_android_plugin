package com.inappify.sdk.internal.network

import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.LinkedHashSet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Cancellable production transport with a strict close boundary and bounded
 * response buffering.
 */
internal class OkHttpTransport private constructor(
    private val baseUrl: HttpUrl,
    private val client: OkHttpClient,
) : HttpTransport {

    private val lifecycleLock = Any()
    private val activeCalls = LinkedHashSet<ActiveCall>()

    @Volatile
    private var closed = false

    override suspend fun execute(request: HttpRequest): TransportResult {
        if (closed) {
            return TransportResult.Failure(TransportFailureKind.CANCELLED)
        }

        val url = baseUrl.resolve(request.path)
            ?: return TransportResult.Failure(TransportFailureKind.NETWORK)
        val httpRequest = Request.Builder()
            .url(url)
            .header("Accept", JSON_ACCEPT_HEADER)
            .post(request.jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val activeCall = ActiveCall(client.newCall(httpRequest))

        return try {
            activeCall.awaitResponse().use { response ->
                val responseBody = runInterruptible {
                    response.readBodyWithinLimit()
                }
                TransportResult.Response(
                    HttpResponse(
                        statusCode = response.code,
                        body = responseBody,
                        requestId = response.header(REQUEST_ID_HEADER),
                    ),
                )
            }
        } catch (_: ResponseBodyTooLargeException) {
            TransportResult.Failure(TransportFailureKind.MALFORMED_RESPONSE)
        } catch (_: RequestCancelledException) {
            TransportResult.Failure(TransportFailureKind.CANCELLED)
        } catch (_: SocketTimeoutException) {
            activeCall.failure(TransportFailureKind.TIMEOUT)
        } catch (_: InterruptedIOException) {
            activeCall.failure(TransportFailureKind.TIMEOUT)
        } catch (_: IOException) {
            activeCall.failure(TransportFailureKind.NETWORK)
        } catch (error: IllegalStateException) {
            if (activeCall.call.isCanceled() || closed) {
                TransportResult.Failure(TransportFailureKind.CANCELLED)
            } else {
                throw error
            }
        } finally {
            release(activeCall)
        }
    }

    override fun close() {
        val callsToCancel = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            activeCalls.toList()
        }
        callsToCancel.forEach(ActiveCall::cancelAndCloseResponse)
        client.connectionPool.evictAll()
        client.cache?.close()
    }

    private suspend fun ActiveCall.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                cancelAndCloseResponse()
            }

            var enqueueFailure: IOException? = null
            val enqueued = synchronized(lifecycleLock) {
                if (closed || !continuation.isActive) {
                    false
                } else {
                    activeCalls.add(this)
                    try {
                        call.enqueue(
                            object : Callback {
                                override fun onFailure(call: Call, e: IOException) {
                                    val failure = if (call.isCanceled()) {
                                        RequestCancelledException()
                                    } else {
                                        e
                                    }
                                    if (continuation.isActive) {
                                        continuation.resumeWithException(failure)
                                    }
                                }

                                override fun onResponse(call: Call, response: Response) {
                                    pendingResponse.set(response)
                                    if (!continuation.isActive) {
                                        pendingResponse.compareAndSet(response, null)
                                        response.close()
                                    } else {
                                        continuation.resume(response)
                                    }
                                }
                            },
                        )
                        true
                    } catch (_: RuntimeException) {
                        activeCalls.remove(this)
                        enqueueFailure = IOException("Unable to enqueue the HTTP request.")
                        false
                    }
                }
            }

            if (!enqueued && continuation.isActive) {
                continuation.resumeWithException(
                    enqueueFailure ?: RequestCancelledException(),
                )
            }
        }

    private fun release(activeCall: ActiveCall) {
        activeCall.pendingResponse.getAndSet(null)?.close()
        synchronized(lifecycleLock) {
            activeCalls.remove(activeCall)
        }
    }

    private fun ActiveCall.failure(defaultKind: TransportFailureKind): TransportResult.Failure =
        TransportResult.Failure(
            if (call.isCanceled() || closed) {
                TransportFailureKind.CANCELLED
            } else {
                defaultKind
            },
        )

    private fun Response.readBodyWithinLimit(): String? {
        val responseBody = body ?: return null
        val declaredLength = responseBody.contentLength()
        if (declaredLength > MAX_RESPONSE_BODY_BYTES) {
            throw ResponseBodyTooLargeException()
        }

        val source = responseBody.source()
        source.request(MAX_RESPONSE_BODY_BYTES + 1L)
        if (source.buffer.size > MAX_RESPONSE_BODY_BYTES) {
            throw ResponseBodyTooLargeException()
        }
        return source.readUtf8()
    }

    private class ActiveCall(
        internal val call: Call,
    ) {
        internal val pendingResponse = AtomicReference<Response?>()

        internal fun cancelAndCloseResponse() {
            call.cancel()
            pendingResponse.getAndSet(null)?.close()
        }
    }

    internal companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val JSON_ACCEPT_HEADER = "application/json"
        private const val REQUEST_ID_HEADER = "X-Request-ID"
        internal const val MAX_RESPONSE_BODY_BYTES = 1024L * 1024L
        private const val PRODUCTION_BASE_URL =
            "https://service.inappify.com/app/v1/"

        internal fun createProduction(): OkHttpTransport =
            OkHttpTransport(
                baseUrl = PRODUCTION_BASE_URL.toHttpUrl(),
                client = OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(45, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(false)
                    .build(),
            )

        internal fun create(
            baseUrl: HttpUrl,
            client: OkHttpClient,
        ): OkHttpTransport = OkHttpTransport(baseUrl, client)
    }
}

private class RequestCancelledException : IOException()

private class ResponseBodyTooLargeException : IOException()
