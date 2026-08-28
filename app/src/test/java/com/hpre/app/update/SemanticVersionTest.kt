package com.hpre.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun parses_installed_version_and_release_tag() {
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parseInstalled("1.2.3"))
        assertEquals(SemanticVersion(1, 2, 3), SemanticVersion.parseTag("v1.2.3"))
    }

    @Test
    fun orders_major_minor_and_patch_numerically() {
        assertTrue(SemanticVersion(2, 0, 0) > SemanticVersion(1, 99, 99))
        assertTrue(SemanticVersion(1, 3, 0) > SemanticVersion(1, 2, 99))
        assertTrue(SemanticVersion(1, 2, 4) > SemanticVersion(1, 2, 3))
    }

    @Test
    fun rejects_noncanonical_versions() {
        listOf(
            "1",
            "1.2",
            "1.2.3.4",
            " 1.2.3",
            "1.2.3 ",
            "1.2.3-beta",
            "-1.2.3",
            "1.02.3",
            "2147483648.0.0"
        ).forEach { assertNull(SemanticVersion.parseInstalled(it)) }

        listOf("1.2.3", "V1.2.3", "v1.2", "v1.2.3-beta")
            .forEach { assertNull(SemanticVersion.parseTag(it)) }
    }

    @Test
    fun accepts_only_official_hpre_release_pages_with_strict_tags() {
        assertEquals(
            "https://github.com/hungnq131193-ux/HPre/releases/tag/v1.2.3",
            OfficialReleasePage.parse(
                "https://github.com/hungnq131193-ux/HPre/releases/tag/v1.2.3"
            )?.url
        )

        listOf(
            "http://github.com/hungnq131193-ux/HPre/releases/tag/v1.2.3",
            "https://github.com/other/HPre/releases/tag/v1.2.3",
            "https://github.com/hungnq131193-ux/HPre/releases/tag/v1.2",
            "https://github.com/hungnq131193-ux/HPre/releases/tag/v1.2.3?download=1",
            "https://github.com/hungnq131193-ux/HPre/releases/latest"
        ).forEach { assertNull(OfficialReleasePage.parse(it)) }
    }
}
