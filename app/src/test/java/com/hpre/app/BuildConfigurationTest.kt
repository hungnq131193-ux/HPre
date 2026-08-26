package com.hpre.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BuildConfigurationTest {
    @Test fun application_id_is_hpre() {
        assertEquals("com.hpre.app", BuildConfig.APPLICATION_ID)
    }
}
