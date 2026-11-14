package com.music.Resso.presence

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.music.Resso.R
import com.music.Resso.data.model.Song
import com.music.Resso.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes what is playing to Discord as a Rich Presence activity via IPC.
 *
 * Speaks directly to the Discord Android app over Android AIDL IPC (IDiscordRpcService).
 * Zero user token required; presence is published natively under Resso's application ID.
 */
class DiscordPresenceManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val lock = Mutex()
    private var transport: DiscordTransport? = null
    private var lastKey = ""
    private var lastPushMs = 0L
    private var lastAttemptMs = 0L

    val isConnected: Boolean
        get() = transport?.isOpen == true

    suspend fun update(
        song: Song,
        positionMs: Long,
        durationMs: Long,
        isPlaying: Boolean,
        speed: Float = 1.0f,
        audioQuality: String? = null,
    ) {
        if (!AppSettings.discordRpcEnabled.value) {
            clearQuiet()
            return
        }

        val key = DiscordPresence.signature(song, isPlaying, durationMs, audioQuality)
        val now = SystemClock.elapsedRealtime()
        if (key == lastKey && now - lastPushMs < DiscordPresence.PUSH_INTERVAL_MS) {
            return
        }

        lock.withLock {
            val active = ensureConnected(now) ?: return

            val appName = runCatching {
                context.getString(R.string.app_name).removeSuffix(" Dev")
            }.getOrDefault("Resso")

            val activity = DiscordPresence.buildActivity(
                song = song,
                currentPlaybackTimeMillis = positionMs,
                durationMillis = durationMs,
                isPlaying = isPlaying,
                playbackSpeed = speed,
                audioQuality = audioQuality,
                appName = AppSettings.discordActivityName.value.ifBlank { appName },
                button1Text = AppSettings.discordButton1Text.value,
                button1Visible = AppSettings.discordButton1Visible.value,
                button2Text = AppSettings.discordButton2Text.value,
                button2Visible = AppSettings.discordButton2Visible.value,
                activityType = AppSettings.discordActivityType.value,
            )

            val sent = runCatching { active.setActivity(activity) }.getOrDefault(false)
            if (!sent) {
                Log.w(TAG, "Discord IPC setActivity rejected; dropping connection")
                runCatching { active.close() }
                transport = null
                lastAttemptMs = SystemClock.elapsedRealtime()
                return
            }

            lastKey = key
            lastPushMs = now
            Log.d(TAG, "Discord IPC presence pushed: ${song.title}")
        }
    }

    suspend fun clear() {
        lock.withLock {
            clearQuiet()
        }
    }

    private fun clearQuiet() {
        lastKey = ""
        val active = transport ?: return
        runCatching { active.clearActivity() }
    }

    private suspend fun ensureConnected(now: Long): DiscordTransport? {
        val existing = transport
        if (existing != null && existing.isOpen) return existing
        if (existing != null && !existing.isOpen) {
            runCatching { existing.close() }
            transport = null
        }
        if (now - lastAttemptMs < DiscordPresence.RETRY_INTERVAL_MS) return null
        lastAttemptMs = now
        val fresh = DiscordIpcTransport.connectOrNull(context)
        return fresh?.also { transport = it }
    }

    suspend fun shutdown() {
        lock.withLock {
            clearQuiet()
            runCatching { transport?.close() }
            transport = null
        }
    }

    companion object {
        private const val TAG = "DiscordPresenceManager"

        @Volatile
        private var instance: DiscordPresenceManager? = null

        fun get(context: Context, scope: CoroutineScope): DiscordPresenceManager {
            return instance ?: synchronized(this) {
                instance ?: DiscordPresenceManager(context.applicationContext, scope).also {
                    instance = it
                }
            }
        }
    }
}
