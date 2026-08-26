package com.hpre.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentKeyTest {

    @Test
    fun identity_includes_service_and_native_id() {
        assertNotEquals(ContentKey(1, "abc"), ContentKey(2, "abc"))
        assertNotEquals(ContentKey(1, "abc"), ContentKey(1, "def"))
        assertEquals(ContentKey(1, "abc"), ContentKey(1, "abc"))
    }
}
