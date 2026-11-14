package com.music.Resso.presence

import com.music.Resso.data.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/**
 * "Show what you're playing on your Discord profile."
 * Wraps [AppSettings.discordRpcEnabled] for discord presence control.
 */
class DiscordPresencePreferences {
    val enabled: StateFlow<Boolean>
        get() = AppSettings.discordRpcEnabled

    fun setEnabled(enabled: Boolean) {
        AppSettings.setDiscordRpcEnabled(enabled)
    }
}
