// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/

package org.foxred.kage.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.foxred.kage.domain.model.Attachment
import org.foxred.kage.ui.emaildisplay.SafeMessageHtml
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafeMessageHtmlTest {
    @Test
    fun stripsExecutableMarkupRemoteImagesAndUntrustedDataSources() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val safe = SafeMessageHtml.render(context, """
            <style>body { background: url(https://tracker.test/style) }</style>
            <script>alert('unsafe')</script>
            <p onclick="alert(1)">Hello <b>there</b></p>
            <a href="javascript:alert(2)">Bad link</a>
            <img src="https://tracker.test/open.png" onerror="alert(3)">
            <img src="data:image/svg+xml;base64,PHN2Zz4=" >
            <iframe src="https://tracker.test/frame"></iframe>
        """.trimIndent(), emptyList())
        val body = Jsoup.parseBodyFragment(safe)
        assertEquals("Hello there Bad link", body.text())
        assertTrue(body.select("img,script,style,iframe").isEmpty())
        assertFalse(body.selectFirst("a")!!.hasAttr("href"))
        assertFalse(safe.contains("onclick"))
        assertFalse(safe.contains("tracker.test"))
    }

    @Test
    fun embedsOnlyCachedBoundedCidImagesFromPrivateDirectory() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val file = File(directory, "safe-cid-test-${System.nanoTime()}")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        try {
            val image = Attachment("part", "message", "logo.png", "image/png", 4,
                cached = true, asset = "", localFile = file.name,
                contentId = "<Logo@Fixture>", inline = true)
            val html = "<p>Notice</p><img src='cid:logo@fixture'>" +
                "<img src='cid:missing'><img src='https://tracker.test/pixel'>"
            val safe = SafeMessageHtml.render(context, html, listOf(image))
            val images = Jsoup.parseBodyFragment(safe).select("img")
            assertEquals(1, images.size)
            assertEquals("data:image/png;base64,AQIDBA==", images.single().attr("src"))
            assertFalse(safe.contains("tracker.test"))
            assertTrue(SafeMessageHtml.render(context, html,
                listOf(image.copy(cached = false))).let {
                    Jsoup.parseBodyFragment(it).select("img").isEmpty()
                })
        } finally {
            file.delete()
        }
    }
}
