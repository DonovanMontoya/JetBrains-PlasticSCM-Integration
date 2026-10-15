package com.github.donovanmontoya.riderplastic.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationGuardTest {
    @Test fun appliesOnlyToOriginalUneditedSelection() {
        val guard = GenerationGuard()
        val ticket = guard.begin(10L, listOf("b", "a"))
        assertTrue(guard.canApply(ticket, 10L, listOf("a", "b")))
        assertFalse(guard.canApply(ticket, 11L, listOf("a", "b")))
        assertFalse(guard.canApply(ticket, 10L, listOf("a")))
    }

    @Test fun cancelledAndSupersededRequestsCannotWrite() {
        val guard = GenerationGuard()
        val first = guard.begin(1L, listOf("file"))
        guard.cancel()
        assertFalse(guard.canApply(first, 1L, listOf("file")))
        val second = guard.begin(1L, listOf("file"))
        assertFalse(guard.isCurrent(first))
        assertTrue(guard.canApply(second, 1L, listOf("file")))
    }

    @Test fun selectionIsDefensivelyCopied() {
        val guard = GenerationGuard()
        val selection = mutableListOf("first")
        val ticket = guard.begin(1L, selection)
        selection.add("second")
        assertTrue(guard.canApply(ticket, 1L, listOf("first")))
        assertFalse(guard.canApply(ticket, 1L, selection))
    }
}
