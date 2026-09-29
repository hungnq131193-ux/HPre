package com.hpre.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = TARGET_PACKAGE,
        maxIterations = 15,
        stableIterations = 3,
        includeInStartupProfile = true
    ) {
        uiAutomator {
            startApp(TARGET_PACKAGE)
        }
    }

    @Test
    fun homeScroll() = rule.collect(
        packageName = TARGET_PACKAGE,
        maxIterations = 15,
        stableIterations = 3
    ) {
        uiAutomator {
            startApp(TARGET_PACKAGE)
            val list = onElement(30_000) { viewIdResourceName == HOME_LIST_TAG }
            repeat(3) { list.fling(Direction.DOWN) }
            repeat(3) { list.fling(Direction.UP) }
        }
    }
}
