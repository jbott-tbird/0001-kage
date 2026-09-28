package org.foxred.kage.core.account

data class Namespace(val prefix: String, val delimiter: Char, val shared: Boolean = false)
