package com.github.donovanmontoya.riderplastic.vcs

import com.intellij.openapi.vcs.history.VcsRevisionNumber

/** A Plastic changeset number. */
data class PlasticRevisionNumber(val changeset: Long) : VcsRevisionNumber {

    override fun asString(): String = changeset.toString()

    override fun compareTo(other: VcsRevisionNumber?): Int =
        if (other is PlasticRevisionNumber) changeset.compareTo(other.changeset) else 0

    override fun toString(): String = "cs:$changeset"
}
