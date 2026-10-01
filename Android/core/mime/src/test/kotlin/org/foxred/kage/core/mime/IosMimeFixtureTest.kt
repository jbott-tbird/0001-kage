// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.core.mime

import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class IosMimeFixtureTest(private val fixture: String) {
    companion object {
        private val expected =
            Properties().apply {
                IosMimeFixtureTest::class
                    .java
                    .getResourceAsStream("/ios/expected.properties")!!
                    .reader(Charsets.UTF_8)
                    .use { load(it) }
            }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Array<String>> =
            expected
                .stringPropertyNames()
                .filter { it.endsWith(".rawSha256") }
                .sorted()
                .map { arrayOf(it.removeSuffix(".rawSha256")) }
    }

    private fun sha(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    private fun contentHash(value: String?): String =
        value?.let { sha(it.replace("\r\n", "\n").toByteArray()) } ?: "-"

    @Test
    fun decodedContentAndAttachmentBytesMatchIndependentGoldenExpectations() {
        val raw = javaClass.getResourceAsStream("/ios/$fixture.eml")!!.use { it.readBytes() }
        assertEquals(expected.getProperty("$fixture.rawSha256"), sha(raw))
        val codec = AngusMimeCodec()
        val decoded = codec.decode(raw)
        assertEquals(
            String(
                Base64.getDecoder().decode(expected.getProperty("$fixture.subjectBase64")),
                Charsets.UTF_8,
            ),
            decoded.subject,
        )
        assertEquals(
            "plain body",
            expected.getProperty("$fixture.textSha256"),
            contentHash(decoded.body.text),
        )
        assertEquals(
            "HTML body",
            expected.getProperty("$fixture.htmlSha256"),
            contentHash(decoded.body.html),
        )
        assertEquals(
            expected.getProperty("$fixture.attachmentHashes"),
            decoded.attachments.joinToString(",") { sha(codec.attachment(raw, it.partId)) },
        )
    }
}
