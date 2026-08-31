package com.hpre.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRestoreDecisionTest {

    @Test
    fun prewarm_connection_hints_skip_session_restore() {
        val shouldRestore = decideSessionRestore(alreadyEvaluated = false, isPrewarm = true)
        assertFalse("Prewarm connection hints must skip session restore", shouldRestore)
    }

    @Test
    fun normal_connection_hints_trigger_session_restore() {
        val shouldRestoreNormal = decideSessionRestore(alreadyEvaluated = false, isPrewarm = false)
        assertTrue("Normal connection without prewarm must trigger session restore", shouldRestoreNormal)
    }

    @Test
    fun repeated_connect_does_not_restore_twice() {
        // First connection restores
        assertTrue(decideSessionRestore(alreadyEvaluated = false, isPrewarm = false))
        // Subsequent connection does not restore
        assertFalse(decideSessionRestore(alreadyEvaluated = true, isPrewarm = false))
        assertFalse(decideSessionRestore(alreadyEvaluated = true, isPrewarm = true))
    }
}

