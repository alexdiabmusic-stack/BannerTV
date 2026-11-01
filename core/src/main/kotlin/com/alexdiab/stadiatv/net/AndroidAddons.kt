package com.alexdiab.stadiatv.net

import com.alexdiab.stadiatv.addons.*
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** No shared HTTP cache, request logger, cookies or redirects on these credential-bearing URLs. */
internal class AndroidAddonHttp : AddonHttp {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .callTimeout(15,TimeUnit.SECONDS).build()
    override suspend fun get(url: String): String = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(Request.Builder().url(url).header("Accept","application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) { continuation.resumeWithException(AddonProblem("Could not connect to the add-on.")) }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!it.isSuccessful) throw AddonProblem("The add-on returned HTTP ${it.code}.")
                        val source = it.body.source()
                        if (source.request(4_194_305L)) throw AddonProblem("The add-on response was too large.")
                        source.readUtf8()
                    }
                    continuation.resume(value)
                } catch (e: Exception) { continuation.resumeWithException(e as? AddonProblem ?: AddonProblem("Could not read the add-on response.")) }
            }
        })
    }
}
