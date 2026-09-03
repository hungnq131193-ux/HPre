package com.hpre.app.ui.common

import com.hpre.app.model.ContentKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPrefetchPolicyTest {
    private val keys = (0..5).map { ContentKey(0, "v$it") }

    @Test fun selects_first_visible_video_and_two_following_videos() {
        assertEquals(keys.subList(2, 5), selectPrefetchKeys(keys, setOf(keys[2], keys[3])))
    }

    @Test fun returns_only_remaining_distinct_keys() {
        assertEquals(
            listOf(keys[0], keys[1]),
            selectPrefetchKeys(listOf(keys[0], keys[0], keys[1]), setOf(keys[0]))
        )
    }

    @Test fun returns_empty_when_no_video_key_is_visible() {
        assertTrue(selectPrefetchKeys(keys, emptySet()).isEmpty())
    }
}
