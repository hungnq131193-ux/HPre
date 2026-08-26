package com.hpre.app.testing

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

class HPreTestRunner : AndroidJUnitRunner() {
    companion object {
        @Volatile
        var isLivePlaybackActive: Boolean = false
    }

    private var runnerArguments: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        val modifiedArgs = Bundle(arguments ?: Bundle())
        runnerArguments = modifiedArgs

        val isLive = isLiveRequested(modifiedArgs)
        if (isLive) {
            isLivePlaybackActive = true
        } else {
            // Exclude LivePlaybackGateTest from standard connected runs unless explicitly requested
            val explicitClass = modifiedArgs.getString("class")
            if (explicitClass.isNullOrBlank()) {
                val notClass = modifiedArgs.getString("notClass")
                val gateTestName = "com.hpre.app.integration.playback.LivePlaybackGateTest"
                val newNotClass = if (notClass.isNullOrBlank()) {
                    gateTestName
                } else if (!notClass.contains(gateTestName)) {
                    "$notClass,$gateTestName"
                } else {
                    notClass
                }
                modifiedArgs.putString("notClass", newNotClass)
            }
        }
        super.onCreate(modifiedArgs)
    }

    private fun isLiveRequested(args: Bundle?): Boolean {
        val arg = args?.getString("hpreLivePlayback")
            ?: System.getProperty("hpreLivePlayback")
        if (arg?.toBoolean() == true || arg?.equals("true", ignoreCase = true) == true) {
            return true
        }
        val classArg = args?.getString("class") ?: ""
        return classArg.contains("LivePlaybackGateTest")
    }

    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?
    ): Application {
        val arg = runnerArguments?.getString("hpreLivePlayback")
            ?: System.getProperty("hpreLivePlayback")
        if (arg?.toBoolean() == true || arg?.equals("true", ignoreCase = true) == true) {
            isLivePlaybackActive = true
        }

        val targetAppClass = if (isLivePlaybackActive) {
            LivePlaybackTestApplication::class.java.name
        } else {
            TestHPreApplication::class.java.name
        }
        return super.newApplication(cl, targetAppClass, context)
    }
}











