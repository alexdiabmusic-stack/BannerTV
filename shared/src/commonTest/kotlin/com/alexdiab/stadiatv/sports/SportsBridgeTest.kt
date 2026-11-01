package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.TestHttp
import com.alexdiab.stadiatv.domain.League
import com.alexdiab.stadiatv.foundation.SportsIOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SportsBridgeTest {
    @Test fun providerExceptionsBecomeSanitizedHostErrors() = runTest {
        val http=TestHttp { throw SportsIOException("sensitive request URL and headers") }
        val bridge=SportsBridge(SportsRepository(emptyMap(),EspnApi(http)),this)
        var failure: SportsFailure?=null
        bridge.scores(League.ALL.first(),null) { matches,error -> assertNull(matches); failure=error }
        runCurrent()
        assertNotNull(failure)
        assertFalse(failure!!.message.contains("sensitive"))
    }
    @Test fun disposingTheSwiftRequestCancelsItsCoroutineAndDoesNotCallADismissedView() = runTest {
        var released=false; var callbacks=0
        val http=TestHttp { try { awaitCancellation() } finally { released=true } }
        val bridge=SportsBridge(SportsRepository(emptyMap(),EspnApi(http)),this)
        val request=bridge.scores(League.ALL.first(),null) { _,_ -> callbacks++ }
        runCurrent(); request.cancel(); runCurrent()
        assertTrue(released); assertEquals(0,callbacks)
    }
}
