package com.hpre.app.ui.watch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareUrlValidatorTest {

    @Test
    fun valid_http_and_https_canonical_urls_are_accepted() {
        assertTrue(ShareUrlValidator.isValid("https://hpre.test/watch?v=abc123xyz"))
        assertTrue(ShareUrlValidator.isValid("http://hpre.test/watch?v=abc123xyz"))
        assertTrue(ShareUrlValidator.isValid("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertTrue(ShareUrlValidator.isValid("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=42s"))
    }

    @Test
    fun null_blank_and_whitespace_urls_are_rejected() {
        assertFalse(ShareUrlValidator.isValid(null))
        assertFalse(ShareUrlValidator.isValid(""))
        assertFalse(ShareUrlValidator.isValid("   "))
        assertFalse(ShareUrlValidator.isValid("\t\n"))
    }

    @Test
    fun non_http_schemes_are_rejected() {
        assertFalse(ShareUrlValidator.isValid("file:///etc/passwd"))
        assertFalse(ShareUrlValidator.isValid("content://media/external/images/media/1"))
        assertFalse(ShareUrlValidator.isValid("intent:#Intent;action=android.intent.action.VIEW;end"))
        assertFalse(ShareUrlValidator.isValid("javascript:alert(1)"))
        assertFalse(ShareUrlValidator.isValid("data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg=="))
        assertFalse(ShareUrlValidator.isValid("android-app://com.example/app"))
    }

    @Test
    fun urls_with_userinfo_are_rejected() {
        assertFalse(ShareUrlValidator.isValid("https://user:password@hpre.test/watch?v=123"))
        assertFalse(ShareUrlValidator.isValid("http://admin@hpre.test/watch"))
    }

    @Test
    fun malformed_and_hostless_urls_are_rejected() {
        assertFalse(ShareUrlValidator.isValid("https://"))
        assertFalse(ShareUrlValidator.isValid("https:///watch?v=123"))
        assertFalse(ShareUrlValidator.isValid("invalid-url"))
        assertFalse(ShareUrlValidator.isValid("://missing-scheme"))
        assertFalse(ShareUrlValidator.isValid("https:// bad host/watch"))
    }
}
