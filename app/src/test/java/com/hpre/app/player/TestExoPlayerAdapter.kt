package com.hpre.app.player

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Clean, stable ExoPlayer test double wrapping dynamic proxy with exact typed state and assertions.
 */
@OptIn(UnstableApi::class)
class TestExoPlayerState {
    var isPlaying: Boolean = false
    var currentPosition: Long = 0L
    var duration: Long = 120_000L
    var playbackSpeed: Float = 1.0f
    var playWhenReady: Boolean = false
    var mediaSourceSet: MediaSource? = null
    var prepared: Boolean = false
    var released: Boolean = false
    var prepareCount: Int = 0
    var releaseCount: Int = 0
    val listeners = mutableListOf<Player.Listener>()
    val analyticsListeners = mutableListOf<AnalyticsListener>()

    fun notifyPlaybackState(state: Int) {
        listeners.toList().forEach { it.onPlaybackStateChanged(state) }
    }

    fun notifyIsPlaying(playing: Boolean) {
        isPlaying = playing
        listeners.toList().forEach { it.onIsPlayingChanged(playing) }
    }

    fun notifyError(error: androidx.media3.common.PlaybackException) {
        listeners.toList().forEach { it.onPlayerError(error) }
    }

    fun createPlayer(): ExoPlayer {
        lateinit var handler: InvocationHandler
        handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "addListener" -> {
                    if (args != null && args.isNotEmpty() && args[0] is Player.Listener) {
                        listeners.add(args[0] as Player.Listener)
                    }
                    null
                }
                "removeListener" -> {
                    if (args != null && args.isNotEmpty() && args[0] is Player.Listener) {
                        listeners.remove(args[0] as Player.Listener)
                    }
                    null
                }
                "addAnalyticsListener" -> {
                    if (args != null && args.isNotEmpty() && args[0] is AnalyticsListener) {
                        analyticsListeners.add(args[0] as AnalyticsListener)
                    }
                    null
                }
                "removeAnalyticsListener" -> {
                    if (args != null && args.isNotEmpty() && args[0] is AnalyticsListener) {
                        analyticsListeners.remove(args[0] as AnalyticsListener)
                    }
                    null
                }
                "isPlaying" -> isPlaying
                "getCurrentPosition" -> currentPosition
                "getDuration" -> duration
                "setPlayWhenReady" -> {
                    playWhenReady = args[0] as Boolean
                    null
                }
                "getPlayWhenReady" -> playWhenReady
                "prepare" -> {
                    prepared = true
                    prepareCount++
                    null
                }
                "play" -> {
                    playWhenReady = true
                    isPlaying = true
                    notifyIsPlaying(true)
                    null
                }
                "pause" -> {
                    playWhenReady = false
                    isPlaying = false
                    notifyIsPlaying(false)
                    null
                }
                "seekTo" -> {
                    val oldPos = currentPosition
                    val target = if (args.size == 1 && args[0] is Long) {
                        args[0] as Long
                    } else if (args.size == 2 && args[1] is Long) {
                        args[1] as Long
                    } else {
                        currentPosition
                    }
                    currentPosition = target
                    val oldPosInfo = Player.PositionInfo(null, 0, null, null, 0, oldPos, oldPos, 0, 0)
                    val newPosInfo = Player.PositionInfo(null, 0, null, null, 0, target, target, 0, 0)
                    listeners.toList().forEach { it.onPositionDiscontinuity(oldPosInfo, newPosInfo, Player.DISCONTINUITY_REASON_SEEK) }
                    null
                }
                "setPlaybackParameters" -> {
                    val params = args[0] as PlaybackParameters
                    playbackSpeed = params.speed
                    null
                }
                "getPlaybackParameters" -> PlaybackParameters(playbackSpeed)
                "setMediaSource" -> {
                    mediaSourceSet = args[0] as MediaSource
                    null
                }
                "release" -> {
                    released = true
                    releaseCount++
                    null
                }
                "equals" -> {
                    val other = args?.getOrNull(0)
                    other != null && Proxy.isProxyClass(other.javaClass) && Proxy.getInvocationHandler(other) == handler
                }
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "TestExoPlayerStateProxy"
                else -> {
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        Double::class.javaPrimitiveType -> 0.0
                        Void.TYPE -> null
                        else -> null
                    }
                }
            }
        }
        return Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            handler
        ) as ExoPlayer
    }
}
