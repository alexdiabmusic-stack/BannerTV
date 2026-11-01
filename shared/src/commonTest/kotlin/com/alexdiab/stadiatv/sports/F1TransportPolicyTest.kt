package com.alexdiab.stadiatv.sports

import com.alexdiab.stadiatv.net.HttpStatusException
import com.alexdiab.stadiatv.sports.f1.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class F1TransportPolicyTest {
    @Test fun accessDeniedStopsWithoutReconnectionOrLeakingServerDetails() = runTest {
        var attempts=0
        val transport=object : F1SessionTransport() {
            override suspend fun connect(deliver: suspend (F1TransportEvent) -> Unit) { attempts++; throw HttpStatusException(403,message="private server details") }
        }
        val events=mutableListOf<F1Connection>()
        transport.run { if(it is F1Connection) events+=it }
        assertEquals(1,attempts)
        assertEquals("failed",events.last().state)
        assertFalse(events.toString().contains("private server details"))
    }
}
