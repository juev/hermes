package org.evsyukov.hermes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs the call on OkHttp's dispatcher and cancels it with the coroutine. */
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, _, _ -> response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }
        },
    )
}

/**
 * Hands the response to [block] off the main thread and closes it. The body may still be on its way
 * when the headers are in, and reading it on the main thread fails with NetworkOnMainThreadException.
 */
internal suspend fun <T> Response.consume(block: suspend (Response) -> T): T = withContext(Dispatchers.IO) { use { block(it) } }
