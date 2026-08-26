package com.flowtube.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PageTokenTest {

    @Test
    fun page_token_models_hold_data_purely() {
        val idToken = PageToken.Id("test-id-123")
        assertEquals("test-id-123", idToken.id)

        val urlToken = PageToken.Url("https://example.com/continuation")
        assertEquals("https://example.com/continuation", urlToken.url)
    }

    @Test
    fun page_token_source_has_no_extractor_or_external_package_imports() {
        val sourceFile = File("src/main/java/com/flowtube/app/model/PageToken.kt")
            .let { if (it.exists()) it else File("app/src/main/java/com/flowtube/app/model/PageToken.kt") }
        assertTrue("PageToken.kt must exist", sourceFile.exists())

        val content = sourceFile.readText()
        assertFalse("PageToken must not import extractor packages", content.contains("com.flowtube.app.extractor"))
        assertFalse("PageToken must not reference org.schabi", content.contains("org.schabi"))
        assertFalse("PageToken must not contain validation logic", content.contains("isValidHttpUrl"))
    }
}

