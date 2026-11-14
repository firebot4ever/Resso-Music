package com.music.Resso.ui.overlay

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import coil3.compose.AsyncImage
import com.music.Resso.MainActivity
import com.music.Resso.data.model.Song
import com.music.Resso.data.settings.AppSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Lightweight LifecycleOwner for standalone ComposeViews in WindowManager overlays.
 */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    fun onCreate() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun onResume() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
    }
}

/**
 * Floating Dynamic Island / Live Capsule System Overlay.
 *
 * Sits directly at the top status bar / camera punch hole on the Android Home Screen
 * and over other apps whenever music is playing in the background.
 *
 * - Collapsed: Compact punch-hole pill matching native OEM capsules (art | punch hole | dancing bars).
 * - Expanded: Interactive floating capsule with song info, Spotify green equalizer, and playback controls.
 */
object DynamicIslandOverlay {

    data class OverlayState(
        val song: Song? = null,
        val isPlaying: Boolean = false,
        val isAppInForeground: Boolean = false,
    )

    private val _state = MutableStateFlow(OverlayState())
    val state = _state

    private var overlayView: ComposeView? = null
    private var windowManager: WindowManager? = null
    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var playPauseAction: (() -> Unit)? = null
    private var nextAction: (() -> Unit)? = null
    private var prevAction: (() -> Unit)? = null

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        mainHandler.post {
            runCatching {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        _state.value = _state.value.copy(isAppInForeground = true)
                        evaluateOverlay(context.applicationContext)
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        _state.value = _state.value.copy(isAppInForeground = false)
                        evaluateOverlay(context.applicationContext)
                    }
                })
            }
        }
    }

    fun updatePlayback(
        context: Context,
        song: Song?,
        isPlaying: Boolean,
        onPlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ) {
        playPauseAction = onPlayPause
        nextAction = onNext
        prevAction = onPrevious

        _state.value = _state.value.copy(
            song = song,
            isPlaying = isPlaying,
        )

        mainHandler.post {
            evaluateOverlay(context.applicationContext)
        }
    }

    fun hideOverlay() {
        mainHandler.post {
            removeView()
        }
    }

    private fun evaluateOverlay(context: Context) {
        val s = _state.value
        val enabled = AppSettings.dynamicIslandEnabled.value
        val hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true

        // Show ONLY when:
        // 1. Dynamic Island setting is enabled
        // 2. Permission is granted
        // 3. A song exists and is active/playing
        // 4. The user is in the BACKGROUND (on Home screen or other apps)
        val shouldShow = enabled && hasOverlayPermission && s.song != null && !s.isAppInForeground

        if (shouldShow) {
            showView(context)
        } else {
            removeView()
        }
    }

    private fun showView(context: Context) {
        if (overlayView != null) return

        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            windowManager = wm

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 8
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }

            val owner = OverlayLifecycleOwner().apply {
                onCreate()
                onResume()
            }
            lifecycleOwner = owner

            val view = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(owner)
                setViewTreeSavedStateRegistryOwner(owner)
                setViewTreeViewModelStoreOwner(owner)

                setContent {
                    DynamicIslandFloatingContent(
                        context = context,
                        onPlayPause = { playPauseAction?.invoke() },
                        onNext = { nextAction?.invoke() },
                        onPrevious = { prevAction?.invoke() },
                    )
                }
            }

            wm.addView(view, layoutParams)
            overlayView = view
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeView() {
        overlayView?.let { view ->
            try {
                windowManager?.removeViewImmediate(view)
            } catch (e: Exception) {
                try {
                    windowManager?.removeView(view)
                } catch (_: Exception) {}
            }
        }
        overlayView = null
        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
    }
}

@Composable
private fun DynamicIslandFloatingContent(
    context: Context,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
) {
    val state by DynamicIslandOverlay.state.collectAsState()
    val song = state.song ?: return
    val isPlaying = state.isPlaying
    var expanded by remember { mutableStateOf(false) }

    // Auto-collapse after 5 seconds of inactivity when expanded
    LaunchedEffect(expanded) {
        if (expanded) {
            delay(5000)
            expanded = false
        }
    }

    Box(
        modifier = Modifier
            .animateContentSize(spring(stiffness = 500f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                expanded = !expanded
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!expanded) {
            // Collapsed: status-bar camera punch-hole capsule (Photo 2)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black)
                    .border(0.6.dp, Color(0xFF262626), RoundedCornerShape(20.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // Left: Circular song artwork
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E1E)),
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                // Middle: Camera punch-hole clearance spacer
                Spacer(modifier = Modifier.width(20.dp))

                // Right: White dancing equalizer bars
                CompactEqualizer(
                    isPlaying = isPlaying,
                    barColor = Color.White,
                )
            }
        } else {
            // Expanded: interactive pill controls (Photo 1)
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(32.dp))
                    .background(Color(0xFF0C0C0C))
                    .border(0.8.dp, Color(0xFF282828), RoundedCornerShape(32.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Left: Circular artwork with click to open full Resso app
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1A1A1A))
                        .clickable {
                            val intent = Intent(context, MainActivity::class.java).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            }
                            context.startActivity(intent)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = song.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }

                // Song Title & Artist
                Column(
                    modifier = Modifier.widthIn(max = 110.dp),
                ) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.5.sp,
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontSize = 10.5.sp,
                        ),
                        color = Color(0xFF9E9E9E),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                // Triple dancing equalizer bars: Green - White - Green (matching Photo 1)
                TriColorEqualizer(isPlaying = isPlaying)

                // Previous
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Play / Pause circular button (Photo 1)
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF242424))
                        .clickable { onPlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Next
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/** 3 dancing equalizer bars with Green - White - Green (matching Photo 1) */
@Composable
private fun TriColorEqualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "TriColorEqualizer")
    val b1 by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = if (isPlaying) 1f else 0.35f,
        animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse),
        label = "tb1",
    )
    val b2 by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = if (isPlaying) 0.25f else 0.85f,
        animationSpec = infiniteRepeatable(tween(250), RepeatMode.Reverse),
        label = "tb2",
    )
    val b3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (isPlaying) 0.95f else 0.4f,
        animationSpec = infiniteRepeatable(tween(420), RepeatMode.Reverse),
        label = "tb3",
    )

    val green = Color(0xFF1DB954)
    val white = Color.White
    val maxHeight = 16.dp

    Row(
        modifier = modifier.height(18.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(maxHeight * b1)
                .clip(RoundedCornerShape(2.dp))
                .background(green),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(maxHeight * b2)
                .clip(RoundedCornerShape(2.dp))
                .background(white),
        )
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(maxHeight * b3)
                .clip(RoundedCornerShape(2.dp))
                .background(green),
        )
    }
}

/** 3 dancing equalizer bars for status bar cutout */
@Composable
private fun CompactEqualizer(
    isPlaying: Boolean,
    barColor: Color,
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "CompactEqualizer")
    val b1 by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isPlaying) 1f else 0.3f,
        animationSpec = infiniteRepeatable(tween(380), RepeatMode.Reverse),
        label = "b1",
    )
    val b2 by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = if (isPlaying) 0.2f else 0.8f,
        animationSpec = infiniteRepeatable(tween(260), RepeatMode.Reverse),
        label = "b2",
    )
    val b3 by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (isPlaying) 0.95f else 0.4f,
        animationSpec = infiniteRepeatable(tween(440), RepeatMode.Reverse),
        label = "b3",
    )

    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val maxHeight = 13.dp
        Box(
            modifier = Modifier
                .width(2.8.dp)
                .height(maxHeight * b1)
                .clip(RoundedCornerShape(1.5.dp))
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .width(2.8.dp)
                .height(maxHeight * b2)
                .clip(RoundedCornerShape(1.5.dp))
                .background(barColor),
        )
        Box(
            modifier = Modifier
                .width(2.8.dp)
                .height(maxHeight * b3)
                .clip(RoundedCornerShape(1.5.dp))
                .background(barColor),
        )
    }
}
