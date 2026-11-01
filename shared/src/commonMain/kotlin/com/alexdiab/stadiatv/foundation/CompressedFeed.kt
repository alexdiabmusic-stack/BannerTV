package com.alexdiab.stadiatv.foundation

import okio.Buffer
import okio.GzipSource
import okio.buffer
import okio.use
import com.alexdiab.stadiatv.net.ResponseTooLargeException

internal fun gunzip(bytes: ByteArray, max: Long): String =
    GzipSource(Buffer().write(bytes)).buffer().use { source ->
        if (source.request(max + 1)) throw ResponseTooLargeException(max)
        source.readUtf8()
    }
