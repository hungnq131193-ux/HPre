package com.hpre.app.di

import com.hpre.app.HPreApplication
import com.hpre.app.ui.watch.FullscreenHostHandlerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

@OptIn(ExperimentalCoroutinesApi::class)
class AppContainerSeamTest {

    @Test
    fun hPreApplication_has_no_public_setter_or_container_replacement_method() {
        val appClass = HPreApplication::class.java
        val methods = appClass.methods

        val disallowedMethodNames = listOf(
            "setContainer",
            "replaceContainer",
            "setAppContainer",
            "initContainer"
        )

        for (method in methods) {
            assertFalse(
                "HPreApplication must not expose public container mutation method: ${method.name}",
                disallowedMethodNames.contains(method.name)
            )
        }

        // Check declared fields: no public fields
        val declaredPublicFields = appClass.declaredFields.filter {
            Modifier.isPublic(it.modifiers) && !it.name.startsWith("\$")
        }
        assertTrue("HPreApplication must not declare public fields", declaredPublicFields.isEmpty())

        // Check createContainer is internal: in bytecode, internal methods get mangled like createContainer$app_debug
        val declaredMethods = appClass.declaredMethods
        val createContainerMethods = declaredMethods.filter { it.name.startsWith("createContainer") }
        assertFalse("createContainer method must exist", createContainerMethods.isEmpty())
        for (m in createContainerMethods) {
            // Kotlin internal methods compile to public with a $app_debug suffix in Java bytecode to prevent accidental consumption outside the module
            assertTrue(
                "createContainer in Kotlin must be compiled with internal mangled name in bytecode: ${m.name}",
                m.name.contains("$") || !Modifier.isPublic(m.modifiers)
            )
        }
    }

    @Test
    fun appContainer_interface_exposes_immutable_fullscreenHostHandlerFactory() {
        val containerClass = AppContainer::class.java
        val factoryMethod = containerClass.methods.find { it.name == "getFullscreenHostHandlerFactory" }
        assertNotNull("AppContainer must expose getFullscreenHostHandlerFactory", factoryMethod)
        assertEquals(FullscreenHostHandlerFactory::class.java, factoryMethod!!.returnType)
    }

    @Test
    fun defaultAppContainer_shares_single_okHttpClient_between_mediaSourceFactory_and_controllers() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }

            val container = DefaultAppContainer(fakeContext)
            val client1 = container.okHttpClient
            val client2 = container.okHttpClient
            org.junit.Assert.assertSame("okHttpClient must be pooled and app-scoped", client1, client2)

            val msf1 = container.mediaSourceFactory
            val msf2 = container.mediaSourceFactory
            org.junit.Assert.assertSame("mediaSourceFactory must be pooled and app-scoped", msf1, msf2)

            // Controller uses the container's pooled mediaSourceFactory instance
            val controller = container.createPlayerController() as com.hpre.app.player.SessionPlayerController
            val secondController = container.createPlayerController()
            org.junit.Assert.assertSame(
                "session controller must be app-scoped so recomposition and PiP do not create extra connections",
                controller,
                secondController
            )
            val controllerMsfField = com.hpre.app.player.SessionPlayerController::class.java.getDeclaredField("mediaSourceFactory")
            controllerMsfField.isAccessible = true
            val controllerMsf = controllerMsfField.get(controller)
            org.junit.Assert.assertSame("controller must use container's pooled mediaSourceFactory instance", msf1, controllerMsf)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /**
     * Cold start regression guard: opening the app on Home must not build ExoPlayer.
     *
     * Observing [AppContainer.playbackState] and calling [AppContainer.peekPlayerController] are the
     * only playback touch points on the Home path (MainActivity + RootScaffold), so neither may
     * construct the controller.
     */
    @Test
    fun observing_playback_state_does_not_construct_the_player() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }
            val container = DefaultAppContainer(fakeContext)

            assertNull("player must not exist before a video is opened", container.peekPlayerController())
            assertEquals(
                "state observed before playback must be the default empty state",
                com.hpre.app.player.PlaybackState(),
                container.playbackState.value
            )
            assertNull("reading playbackState must not construct the player", container.peekPlayerController())

            // Lifecycle policy updates arrive on every onStart/PiP change and must stay side-effect free.
            container.updatePlayerLifecyclePolicy(backgroundEnabled = true, pipActiveOrEntering = false)
            assertNull(
                "updating lifecycle policy must not construct the player",
                container.peekPlayerController()
            )

            // Opening a video is the only trigger.
            val controller = container.createPlayerController()
            org.junit.Assert.assertSame(controller, container.peekPlayerController())
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun orchestratePlaybackPrewarm_executes_each_operation_once_with_strict_io_before_main_confinement() {
        val opEvents = mutableListOf<String>()
        val testDispatcher = StandardTestDispatcher()
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)

        var ioActive = false
        var mainActive = false

        val customIoDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                ioActive = true
                try {
                    block.run()
                } finally {
                    ioActive = false
                }
            }
        }

        val customMainDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                mainActive = true
                try {
                    block.run()
                } finally {
                    mainActive = false
                }
            }
        }

        var msfInitCount = 0
        var controllerCreateCount = 0
        val guard = java.util.concurrent.atomic.AtomicBoolean(false)

        // Invoke orchestrator twice with same guard
        orchestratePlaybackPrewarm(
            guard = guard,
            scope = testScope,
            ioDispatcher = customIoDispatcher,
            mainDispatcher = customMainDispatcher,
            initMediaSourceFactory = {
                msfInitCount++
                opEvents.add("initMediaSourceFactory (ioActive=$ioActive, mainActive=$mainActive)")
            },
            initPlayerController = {
                controllerCreateCount++
                opEvents.add("initPlayerController (ioActive=$ioActive, mainActive=$mainActive)")
            }
        )

        orchestratePlaybackPrewarm(
            guard = guard,
            scope = testScope,
            ioDispatcher = customIoDispatcher,
            mainDispatcher = customMainDispatcher,
            initMediaSourceFactory = { msfInitCount++ },
            initPlayerController = { controllerCreateCount++ }
        )

        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, msfInitCount)
        assertEquals(1, controllerCreateCount)
        assertEquals(
            listOf(
                "initMediaSourceFactory (ioActive=true, mainActive=false)",
                "initPlayerController (ioActive=false, mainActive=true)"
            ),
            opEvents
        )
    }

    @Test
    fun defaultAppContainer_prewarm_materializes_controller_and_preserves_empty_playback_state() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }
            val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)
            val container = DefaultAppContainer(
                context = fakeContext,
                applicationScope = testScope,
                ioDispatcher = testDispatcher,
                mainDispatcher = testDispatcher
            )

            assertNull("controller must not exist prior to prewarm", container.peekPlayerController())

            // Prewarm repeatedly
            container.prewarmPlaybackInfrastructure()
            container.prewarmPlaybackInfrastructure()

            testDispatcher.scheduler.advanceUntilIdle()

            val controller = container.peekPlayerController()
            assertNotNull("controller must be initialized after prewarm", controller)
            org.junit.Assert.assertSame(controller, container.createPlayerController())

            // Assert state remains default empty state: no active content key, no duration, not prepared, not playing
            assertEquals(com.hpre.app.player.PlaybackState(), controller!!.state.value)

            val sessionController = controller as com.hpre.app.player.SessionPlayerController
            assertEquals(
                "DefaultAppContainer controller initialized by prewarm must have PREWARM purpose",
                com.hpre.app.player.ConnectionPurpose.PREWARM,
                sessionController.currentConnectionPurpose
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun direct_create_before_idle_yields_normal_purpose() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }
            val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)
            val container = DefaultAppContainer(
                context = fakeContext,
                applicationScope = testScope,
                ioDispatcher = testDispatcher,
                mainDispatcher = testDispatcher
            )

            val controller = container.createPlayerController() as com.hpre.app.player.SessionPlayerController
            assertEquals(
                "Direct createPlayerController without prewarm must have NORMAL purpose",
                com.hpre.app.player.ConnectionPurpose.NORMAL,
                controller.currentConnectionPurpose
            )
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun idle_before_direct_yields_one_prewarm_controller() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }
            val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)
            val container = DefaultAppContainer(
                context = fakeContext,
                applicationScope = testScope,
                ioDispatcher = testDispatcher,
                mainDispatcher = testDispatcher
            )

            container.prewarmPlaybackInfrastructure()
            testDispatcher.scheduler.advanceUntilIdle()

            val prewarmedController = container.peekPlayerController() as com.hpre.app.player.SessionPlayerController
            assertEquals(com.hpre.app.player.ConnectionPurpose.PREWARM, prewarmedController.currentConnectionPurpose)

            val directController = container.createPlayerController()
            org.junit.Assert.assertSame("Must return same prewarmed controller instance", prewarmedController, directController)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun concurrent_provider_calls_yield_one_controller() {
        var factoryCalls = 0
        var recordedPurpose: com.hpre.app.player.ConnectionPurpose? = null
        val controller = object : com.hpre.app.player.PlayerController {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(com.hpre.app.player.PlaybackState())
            override fun attachSurface(playerView: androidx.media3.ui.PlayerView) = Unit
            override fun detachSurface(playerView: androidx.media3.ui.PlayerView) = Unit
            override fun onLifecycleStart() = Unit
            override fun onLifecycleStop() = Unit
            override fun prepare(key: com.hpre.app.model.ContentKey, streamInfo: com.hpre.app.model.StreamInfo, startPositionMs: Long, playWhenReady: Boolean, initialQuality: com.hpre.app.player.QualityOption?) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun playPause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun seekBy(deltaMs: Long) = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun selectQuality(quality: com.hpre.app.player.QualityOption) = Unit
            override fun release() = Unit
        }
        val provider = com.hpre.app.player.AppScopedPlayerControllerProvider { purpose ->
            factoryCalls++
            recordedPurpose = purpose
            controller
        }

        val threads = (1..10).map { idx ->
            Thread {
                val purpose = if (idx % 2 == 0) com.hpre.app.player.ConnectionPurpose.PREWARM else com.hpre.app.player.ConnectionPurpose.NORMAL
                provider.get(purpose)
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(1, factoryCalls)
        assertNotNull(recordedPurpose)
        org.junit.Assert.assertSame(controller, provider.get())
    }

    @Test
    fun default_app_container_returns_one_global_session_controller() {
        var factoryCalls = 0
        val controller = object : com.hpre.app.player.PlayerController {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(com.hpre.app.player.PlaybackState())
            override fun attachSurface(playerView: androidx.media3.ui.PlayerView) = Unit
            override fun detachSurface(playerView: androidx.media3.ui.PlayerView) = Unit
            override fun onLifecycleStart() = Unit
            override fun onLifecycleStop() = Unit
            override fun prepare(key: com.hpre.app.model.ContentKey, streamInfo: com.hpre.app.model.StreamInfo, startPositionMs: Long, playWhenReady: Boolean, initialQuality: com.hpre.app.player.QualityOption?) = Unit
            override fun play() = Unit
            override fun pause() = Unit
            override fun playPause() = Unit
            override fun seekTo(positionMs: Long) = Unit
            override fun seekBy(deltaMs: Long) = Unit
            override fun setPlaybackSpeed(speed: Float) = Unit
            override fun selectQuality(quality: com.hpre.app.player.QualityOption) = Unit
            override fun release() = Unit
        }
        val provider = com.hpre.app.player.AppScopedPlayerControllerProvider.fromSimple {
            factoryCalls++
            controller
        }

        assertNull(provider.getIfInitialized())
        assertEquals(0, factoryCalls)
        val first = provider.get()
        val second = provider.get()

        org.junit.Assert.assertSame(first, second)
        org.junit.Assert.assertSame(first, provider.getIfInitialized())
        assertEquals(1, factoryCalls)
    }
}

