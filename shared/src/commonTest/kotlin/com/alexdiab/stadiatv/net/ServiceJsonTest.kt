package com.alexdiab.stadiatv.net

import kotlinx.serialization.SerializationException
import kotlin.test.*

class ServiceJsonTest {
    @Test fun validScalarAndStructuredJsonRemainsSupported() {
        for (body in listOf("{}","[]"," \ntrue\n ","false","null","-1.2e3","\"text\"")) assertNotNull(parseServiceJson(body))
    }
    @Test fun htmlAndUnquotedErrorStringsAreRejected() {
        for (body in listOf("<html>","Access denied","undefined","NaN","01","truee"))
            assertFailsWith<SerializationException> { parseServiceJson(body) }
    }
}
