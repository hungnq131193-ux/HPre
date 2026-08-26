package com.flowtube.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigurationTest {
    @Test
    fun application_id_is_flowtube() {
        assertEquals("com.flowtube.app", BuildConfig.APPLICATION_ID)
    }
}
