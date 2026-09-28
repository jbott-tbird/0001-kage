package org.foxred.kage.core.account

enum class FailureKind {
    CANCELLED,
    AUTHENTICATION,
    CONNECTION,
    PROTOCOL,
    INVALID_MESSAGE,
    LIMIT_EXCEEDED,
    UNCERTAIN_DELIVERY,
}

class MailFailure(val kind: FailureKind, message: String, cause: Throwable? = null) :
    Exception(message, cause)
