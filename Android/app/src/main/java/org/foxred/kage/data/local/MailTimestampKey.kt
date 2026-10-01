// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.data.local

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

/** Fixed-width UTC text keeps Room's indexed lexical date order chronological. */
private val MAIL_TIMESTAMP_FORMAT = DateTimeFormatterBuilder().appendInstant(9).toFormatter()

fun mailTimestampKey(value: Instant): String = MAIL_TIMESTAMP_FORMAT.format(value)

/** Older sample rows can contain date-only values; keep those unchanged. */
fun mailTimestampKey(value: String): String =
    runCatching { mailTimestampKey(Instant.parse(value)) }.getOrDefault(value)
