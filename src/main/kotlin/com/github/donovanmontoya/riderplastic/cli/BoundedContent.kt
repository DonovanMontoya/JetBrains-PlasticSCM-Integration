package com.github.donovanmontoya.riderplastic.cli

import java.io.InputStream

/** Read at most the limit plus one sentinel byte, without loading an entire large file. */
object BoundedContent {
    fun read(input: InputStream, maxBytes: Int): ByteArray {
        require(maxBytes in 1..1024 * 1024) { "Invalid content limit." }
        val result = input.readNBytes(maxBytes + 1)
        require(result.size <= maxBytes) { "Selected content exceeds the AI size limit." }
        return result
    }
}
