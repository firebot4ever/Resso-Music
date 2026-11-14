package com.music.Resso.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.Resso.data.settings.AppSettings
import com.music.Resso.data.spotify.SpotifyLibrary
import com.music.Resso.data.spotify.SpotifyPlaylist
import com.music.Resso.sharedui.resources.Res
import com.music.Resso.sharedui.resources.spotify_logo
import com.music.Resso.ui.icons.SfIcons
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource

private val SPOTIFY_GREEN = Color(0xFF1DB954)
private val SPOTIFY_CARD_BG = Color(0xFF282828)
private val SPOTIFY_CHIP_BG = Color(0xFF2A2A2A)

/**
 * Spotify-styled Home screen that recreates Spotify's native layout:
 * - Filter pills ("All", "Music", "Podcasts")
 * - 2-column quick access grid with Liked Songs and recent playlists
 * - "Made For You" horizontal cards with Spotify logo badge
 * - "Your Playlists" collection
 */
@Composable
fun SpotifyHomeScreen(
    onOpenPlaylist: (SpotifyPlaylist) -> Unit,
    onOpenAccount: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val cookie by AppSettings.spotifySpdcToken.collectAsStateWithLifecycle()
    val spotifyAvatar by AppSettings.spotifyUserAvatar.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var playlists by remember { mutableStateOf<List<SpotifyPlaylist>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var selectedFilter by remember { mutableStateOf("All") }

    val fetchPlaylists = {
        scope.launch {
            loading = true
            runCatching { SpotifyLibrary.playlists() }
                .onSuccess { playlists = it }
            loading = false
        }
    }

    LaunchedEffect(cookie) {
        if (cookie.isNotBlank()) {
            fetchPlaylists()
        } else {
            playlists = emptyList()
            loading = false
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 6.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        // 1. Filter Chips Row ("All", "Music", "Podcasts")
        item(key = "spotify-filter-chips") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf("All", "Music", "Podcasts").forEach { filter ->
                    val isSelected = filter == selectedFilter
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(if (isSelected) SPOTIFY_GREEN else SPOTIFY_CHIP_BG)
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = filter,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.Black else Color.White,
                        )
                    }
                }
            }
        }

        if (loading && playlists.isEmpty()) {
            item(key = "loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = SPOTIFY_GREEN,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
        } else if (playlists.isNotEmpty()) {
            // 2. Quick Access 2-Column Grid (Up to 8 items)
            val quickItems = playlists.take(8)
            val rows = quickItems.chunked(2)
            item(key = "spotify-quick-grid") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    rows.forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            rowItems.forEach { playlist ->
                                SpotifyQuickCard(
                                    playlist = playlist,
                                    onClick = { onOpenPlaylist(playlist) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowItems.size == 1) {
                                Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // 3. "Made For You" Section
            // Find playlists with "Mix", "Daily", "Discover", "Radar", or fallback to remaining playlists
            val madeForYouPlaylists = playlists.filter {
                it.name.contains("Mix", ignoreCase = true) ||
                    it.name.contains("Daily", ignoreCase = true) ||
                    it.name.contains("Discover", ignoreCase = true) ||
                    it.name.contains("Radar", ignoreCase = true) ||
                    it.owner?.equals("Spotify", ignoreCase = true) == true
            }.ifEmpty {
                playlists.drop(1).take(6)
            }

            if (madeForYouPlaylists.isNotEmpty()) {
                item(key = "made-for-you-header") {
                    Text(
                        text = "Made For You",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
                    )
                }

                item(key = "made-for-you-row") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(madeForYouPlaylists, key = { "mfy-${it.id}" }) { playlist ->
                            SpotifyMediumCard(
                                playlist = playlist,
                                badgeWithLogo = true,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }

            // 4. "Your Playlists" / Library Section
            val remainingPlaylists = playlists.filter { it !in madeForYouPlaylists && it.id != SpotifyLibrary.LIKED_ID }
            if (remainingPlaylists.isNotEmpty()) {
                item(key = "your-playlists-header") {
                    Text(
                        text = "Your Playlists",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp, bottom = 12.dp),
                    )
                }

                item(key = "your-playlists-row") {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                    ) {
                        items(remainingPlaylists, key = { "yp-${it.id}" }) { playlist ->
                            SpotifyMediumCard(
                                playlist = playlist,
                                badgeWithLogo = false,
                                onClick = { onOpenPlaylist(playlist) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 56dp height card in the 2-column quick access grid. */
@Composable
private fun SpotifyQuickCard(
    playlist: SpotifyPlaylist,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLiked = playlist.id == SpotifyLibrary.LIKED_ID
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SPOTIFY_CARD_BG)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (isLiked) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SfIcons.HeartFill,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            AsyncImage(
                model = playlist.imageUrl,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .background(Color(0xFF333333)),
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = playlist.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(end = 8.dp)
                .weight(1f),
        )
    }
}

/** 152dp square artwork card with optional Spotify badge on top-left. */
@Composable
private fun SpotifyMediumCard(
    playlist: SpotifyPlaylist,
    badgeWithLogo: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isLiked = playlist.id == SpotifyLibrary.LIKED_ID
    Column(
        modifier = modifier
            .width(148.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .size(148.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF242424)),
        ) {
            if (isLiked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF450AF5), Color(0xFF8E8EE5)),
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = SfIcons.HeartFill,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }
            } else {
                AsyncImage(
                    model = playlist.imageUrl,
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Spotify green badge icon on top-left corner (matches screenshot)
            if (badgeWithLogo) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .align(Alignment.TopStart),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.spotify_logo),
                        contentDescription = "Spotify",
                        tint = SPOTIFY_GREEN,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = playlist.name,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = playlist.owner?.takeIf { it.isNotBlank() } ?: "Playlist • Spotify",
            fontSize = 12.sp,
            color = Color(0xFFA7A7A7),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
