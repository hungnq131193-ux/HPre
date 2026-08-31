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
    fun repeated_prewarm_materializes_one_app_scoped_controller_with_io_before_main() {
        val executionOrder = mutableListOf<String>()
        val customIoDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                executionOrder.add("IO")
                block.run()
            }
        }
        val customMainDispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                executionOrder.add("Main")
                block.run()
            }
        }

        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }
            val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)
            val container = DefaultAppContainer(
                fakeContext,
                applicationScope = testScope,
                ioDispatcher = customIoDispatcher,
                mainDispatcher = customMainDispatcher
            )

            assertNull("player must not exist before prewarm", container.peekPlayerController())

            // Trigger prewarm multiple times
            container.prewarmPlaybackInfrastructure()
            container.prewarmPlaybackInfrastructure()

            // Run coroutines on testScope
            testDispatcher.scheduler.advanceUntilIdle()

            val controller = container.peekPlayerController()
            assertNotNull("prewarm must materialize the controller", controller)
            org.junit.Assert.assertSame(
                "repeated prewarm must return the same app-scoped controller",
                controller,
                container.createPlayerController()
            )

            // Assert IO before Main execution order
            assertEquals(listOf("IO", "Main"), executionOrder)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun prewarm_does_not_prepare_controller_or_request_streams() {
        val testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)
        try {
            val fakeContext = object : android.content.ContextWrapper(null) {
                override fun getApplicationContext(): android.content.Context = this
            }
            val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + testDispatcher)
            val container = DefaultAppContainer(
                fakeContext,
                applicationScope = testScope,
                ioDispatcher = testDispatcher,
                mainDispatcher = testDispatcher
            )

            container.prewarmPlaybackInfrastructure()
            testDispatcher.scheduler.advanceUntilIdle()

            val controller = container.peekPlayerController()
            assertNotNull(controller)
            // Empty state, no key, no media, not prepared, not playing
            assertEquals(com.hpre.app.player.PlaybackState(), controller!!.state.value)
        } finally {
            Dispatchers.resetMain()
        }
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
        val provider = com.hpre.app.player.AppScopedPlayerControllerProvider {
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

