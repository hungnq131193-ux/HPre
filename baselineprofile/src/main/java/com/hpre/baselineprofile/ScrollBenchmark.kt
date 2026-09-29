package com.hpre.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.uiAutomator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollNoCompilation() = scroll(CompilationMode.None())

    @Test
    fun scrollPartialWithoutBaselineProfile() = scroll(
        CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Disable,
            warmupIterations = 3
        )
    )

    @Test
    fun scrollPartialWithBaselineProfile() = scroll(
        CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)
    )

    private fun scroll(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            iterations = DEFAULT_ITERATIONS,
            startupMode = null,
            setupBlock = {
                uiAutomator {
                    if (onElementOrNull(5_000) { viewIdResourceName == HOME_LIST_TAG } == null) {
                        startApp(TARGET_PACKAGE)
                        onElement(30_000) { viewIdResourceName == HOME_LIST_TAG }
                    }
                }
            }
        ) {
            uiAutomator {
                val list = onElement(20_000) { viewIdResourceName == HOME_LIST_TAG }
                repeat(3) { list.fling(Direction.DOWN) }
                repeat(3) { list.fling(Direction.UP) }
            }
        }
    }
}
