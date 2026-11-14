package com.music.Resso.presence

import com.music.Resso.data.discord.DiscordApplication
import com.music.Resso.data.model.Song
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Discord Rich Presence ("Listening to Resso") payload builder for IPC transport.
 *
 * Speaks the native Discord IPC wire protocol:
 * `{"cmd":"SET_ACTIVITY","args":{"pid":...,"activity":...},"nonce":...}`.
 * No user token required; presence is published through Discord's local Android RPC service.
 */
object DiscordPresence {

    /**
     * Application ID from the Discord Developer Portal for Resso.
     */
    const val APPLICATION_ID = DiscordApplication.ID

    const val PROJECT_URL = "https://github.com/firebot4ever/Resso"
    const val DEFAULT_BUTTON_1 = "Listen on YouTube Music"
    const val DEFAULT_BUTTON_2 = "Visit Resso"

    /** Discord truncates nothing for us: over-long fields are dropped. */
    const val MAX_FIELD = 120
    const val MAX_STATE = 125

    /** Push and reconnect throttles. */
    const val PUSH_INTERVAL_MS = 15_000L
    const val RETRY_INTERVAL_MS = 15_000L

    /** Asset key for default logo. */
    const val FALLBACK_ASSET = "logo"

    /** 2 = "Listening to". */
    const val TYPE_LISTENING = 2

    fun isConfigured(): Boolean = APPLICATION_ID.isNotEmpty()

    fun clip(value: String, limit: Int = MAX_FIELD): String {
        val trimmed = value.trim()
        return if (trimmed.length > limit) trimmed.substring(0, limit - 1) + "..." else trimmed
    }

    fun stateLine(artist: String, album: String?, title: String, quality: String): String {
        val head = artist.trim().ifEmpty { album?.trim()?.takeIf { it.isNotEmpty() } ?: "Resso" }
        val raw = if (quality.isEmpty()) clip(head) else clip(head) + " · " + quality
        return if (raw.length > MAX_STATE) raw.substring(0, MAX_STATE - 1) + "..." else raw
    }

    fun signature(
        song: Song?,
        isPlaying: Boolean,
        durationMs: Long,
        audioQuality: String?,
    ): String {
        if (song == null) return "none"
        return "${song.videoId}|${song.title}|${song.artist}|${song.thumbnailUrl}|playing=$isPlaying|dur=${durationMs / 1000L}|q=$audioQuality"
    }

    /**
     * Builds the `SET_ACTIVITY` activity object.
     */
    fun buildActivity(
        song: Song,
        currentPlaybackTimeMillis: Long,
        durationMillis: Long,
        isPlaying: Boolean,
        playbackSpeed: Float = 1.0f,
        audioQuality: String? = null,
        appName: String = "Resso",
        button1Text: String = "",
        button1Visible: Boolean = true,
        button2Text: String = "",
        button2Visible: Boolean = true,
        activityType: String = "listening",
        nowMs: Long = System.currentTimeMillis(),
    ): JsonObject {
        val title = clip(song.title)
        val speedAdjustedDuration = if (playbackSpeed > 0f) (durationMillis / playbackSpeed).toLong() else durationMillis
        val speedAdjustedPosition = if (playbackSpeed > 0f) (currentPlaybackTimeMillis / playbackSpeed).toLong() else currentPlaybackTimeMillis

        val timestamps = if (isPlaying && durationMillis > 0L) {
            val position = speedAdjustedPosition.coerceIn(0L, speedAdjustedDuration)
            val endMs = nowMs + (speedAdjustedDuration - position)
            val startMs = endMs - speedAdjustedDuration
            buildJsonObject {
                put("start", startMs / 1000L)
                put("end", endMs / 1000L)
            }
        } else null

        val largeImage = song.thumbnailUrl
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: (if (song.videoId.isNotBlank()) "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg" else FALLBACK_ASSET)

        val state = stateLine(song.artist, song.albumName, song.title, audioQuality.orEmpty())

        val typeInt = when (activityType) {
            "playing" -> 0
            "watching" -> 3
            "competing" -> 5
            else -> TYPE_LISTENING
        }

        return buildJsonObject {
            put("type", typeInt)
            put("name", appName)
            put("details", title)
            put("state", state)
            timestamps?.let { put("timestamps", it) }
            val isSpotify = com.music.Resso.data.settings.AppSettings.spotifySpdcToken.value.isNotBlank()

            put("assets", buildJsonObject {
                put("large_image", largeImage)
                val largeText = when {
                    !song.albumName.isNullOrBlank() -> clip(song.albumName.orEmpty())
                    !audioQuality.isNullOrBlank() -> clip(audioQuality.orEmpty())
                    else -> title
                }
                put("large_text", largeText)
                if (isSpotify) {
                    put("small_image", "spotify")
                    put("small_text", "Spotify Account")
                }
            })

            val buttons = mutableListOf<JsonObject>()
            if (button1Visible) {
                val defaultLabel = if (isSpotify) "Play on Resso" else DEFAULT_BUTTON_1
                val defaultUrl = if (isSpotify) {
                    val encoded = runCatching { java.net.URLEncoder.encode("${song.title} ${song.artist}", "UTF-8") }.getOrDefault("")
                    "https://open.spotify.com/search/$encoded"
                } else if (song.videoId.isNotBlank()) {
                    "https://music.youtube.com/watch?v=${song.videoId}"
                } else PROJECT_URL
                val label = if (button1Text.isNotBlank()) button1Text else defaultLabel
                buttons.add(buildJsonObject {
                    put("label", clip(label, 32))
                    put("url", defaultUrl)
                })
            }
            if (button2Visible) {
                val label = if (button2Text.isNotBlank()) button2Text else DEFAULT_BUTTON_2
                buttons.add(buildJsonObject {
                    put("label", clip(label, 32))
                    put("url", PROJECT_URL)
                })
            }
            if (buttons.isNotEmpty()) {
                putJsonArray("buttons") {
                    buttons.forEach { add(it) }
                }
            }
            put("instance", JsonPrimitive(true))
        }
    }

    /** `{"cmd":"SET_ACTIVITY", ...}` — the frame the transport hands to Discord. */
    fun setActivityFrame(activity: JsonObject, pid: Int, nonce: String): String =
        frame(
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("args", buildJsonObject {
                    put("pid", pid)
                    put("activity", activity)
                })
                put("nonce", nonce)
            },
        )

    /** The clear frame. */
    fun clearActivityFrame(pid: Int, nonce: String): String =
        frame(
            buildJsonObject {
                put("cmd", "SET_ACTIVITY")
                put("args", buildJsonObject {
                    put("pid", pid)
                    put("activity", JsonNull)
                })
                put("nonce", nonce)
            },
        )

    private fun frame(payload: JsonObject): String = payload.toString()
}
