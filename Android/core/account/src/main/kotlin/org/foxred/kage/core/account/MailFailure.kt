// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

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
