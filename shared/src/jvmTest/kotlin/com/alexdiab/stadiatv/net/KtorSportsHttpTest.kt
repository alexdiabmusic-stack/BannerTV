package com.alexdiab.stadiatv.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class KtorSportsHttpTest {
    @Test fun cacheSeparatesAuthorizationAndNeverCachesMalformedJson() = runTest {
        var calls=0
        val engine=MockEngine { calls++; respond(if (calls == 1) "<html>" else """{"ok":true}""",HttpStatusCode.OK) }
        val http=KtorSportsHttp(HttpClient(engine))
        try {
            assertFailsWith<Exception> { http.getJson("https://sports.example/data",ttlMillis=60000) }
            http.getJson("https://sports.example/data",ttlMillis=60000)
            http.getJson("https://sports.example/data",ttlMillis=60000)
            assertEquals(2,calls)
            http.getJson("https://sports.example/data",headers=mapOf("Authorization" to "different"),ttlMillis=60000)
            assertEquals(3,calls)
        } finally { http.close() }
    }
    @Test fun bodyLimitsAndAccessDenialDoNotRetry() = runTest {
        var calls=0
        val engine=MockEngine { calls++; if(it.url.encodedPath=="/denied") respond("no",HttpStatusCode.Forbidden) else respond("0123456789") }
        val http=KtorSportsHttp(HttpClient(engine))
        try {
            assertFailsWith<ResponseTooLargeException> { http.getBytes("https://sports.example/large",maxResponseBytes=5,retries=2) }
            assertEquals(1,calls)
            val denied=assertFailsWith<HttpStatusException> { http.getJson("https://sports.example/denied",retries=2) }
            assertEquals(403,denied.code); assertEquals(2,calls)
        } finally { http.close() }
    }
    @Test fun cancellingOneConsumerDoesNotKillAnActivePeer() = runTest {
        val started=CompletableDeferred<Unit>(); val proceed=CompletableDeferred<Unit>(); var calls=0
        val engine=MockEngine { calls++; started.complete(Unit); proceed.await(); respond("{}") }
        val http=KtorSportsHttp(HttpClient(engine))
        try {
            val first=async { http.getString("https://sports.example/data") }
            started.await()
            val second=async(start=CoroutineStart.UNDISPATCHED) { http.getString("https://sports.example/data") }
            first.cancelAndJoin(); proceed.complete(Unit)
            assertEquals("{}",second.await()); assertEquals(1,calls)
        } finally { http.close() }
    }
    @Test fun lastConsumerCancellationReleasesTheNetworkRequest() = runTest {
        val started=CompletableDeferred<Unit>(); val cancelled=CompletableDeferred<Unit>()
        val engine=MockEngine {
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        }
        val http=KtorSportsHttp(HttpClient(engine))
        try {
            val only=async { http.getString("https://sports.example/data") }
            started.await(); only.cancelAndJoin(); cancelled.await()
        } finally { http.close() }
    }
}
