package com.github.donovanmontoya.riderplastic.ai

/** Captures UI state without coupling provider code to the editor or project lifecycle. */
class GenerationGuard {
    class Ticket internal constructor(val id: Long, val documentStamp: Long, val selection: List<String>)

    @Volatile private var currentId = 0L

    /** Called on the UI thread; the immutable ticket can be checked by background work. */
    fun begin(documentStamp: Long, selection: List<String>): Ticket =
        Ticket(++currentId, documentStamp, selection.sorted().toList())

    fun cancel() { currentId++ }

    fun isCurrent(ticket: Ticket): Boolean = ticket.id == currentId

    fun canApply(ticket: Ticket, documentStamp: Long, selection: List<String>): Boolean =
        isCurrent(ticket) && ticket.documentStamp == documentStamp && ticket.selection == selection.sorted()
}
