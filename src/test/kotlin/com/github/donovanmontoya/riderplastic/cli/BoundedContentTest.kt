package com.github.donovanmontoya.riderplastic.cli

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class BoundedContentTest {
    @Test fun allowsEmptyAndExactLimit() {
        assertArrayEquals(byteArrayOf(), BoundedContent.read(ByteArrayInputStream(byteArrayOf()), 4))
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, BoundedContent.read(ByteArrayInputStream(bytes), 4))
    }

    @Test fun oversizedStreamStopsAfterOneSentinelByte() {
        var count = 0
        val input = object : InputStream() {
            override fun read(): Int { count++; return 65 }
        }
        assertThrows(IllegalArgumentException::class.java) { BoundedContent.read(input, 32) }
        assertEquals(33, count)
    }

    @Test fun rejectsInvalidLimitsBeforeReading() {
        val input = object : InputStream() {
            override fun read(): Int = error("Must not read")
        }
        assertThrows(IllegalArgumentException::class.java) { BoundedContent.read(input, 0) }
        assertThrows(IllegalArgumentException::class.java) { BoundedContent.read(input, Int.MAX_VALUE) }
    }
}
