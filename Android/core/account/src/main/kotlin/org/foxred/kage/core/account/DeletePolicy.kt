package org.foxred.kage.core.account

sealed interface DeletePolicy {
    data object Never : DeletePolicy

    data object OnDelete : DeletePolicy

    data object MarkAsRead : DeletePolicy

    data class After(val days: Int = 7) : DeletePolicy {
        init {
            require(days >= 0)
        }
    }
}
