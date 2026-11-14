package com.music.Resso.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlinx.coroutines.delay

private const val LOGIN_URL =
    "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"

private val SPOTIFY_COOKIE_URLS = listOf(
    "https://open.spotify.com",
    "https://accounts.spotify.com",
    "https://www.spotify.com",
    "https://spotify.com",
)

private val LOGIN_HOST_SUFFIXES = listOf(
    "spotify.com", "scdn.co", "google.com", "gstatic.com", "facebook.com", "apple.com",
)

private fun hostMatches(uri: Uri?, suffixes: List<String>): Boolean {
    if (uri?.scheme != "https") return false
    val host = uri.host?.lowercase() ?: return false
    return suffixes.any { host == it || host.endsWith(".$it") }
}

private fun isLoginHost(uri: Uri?) = hostMatches(uri, LOGIN_HOST_SUFFIXES)

private fun extractSpdcToken(): String? {
    val cookies = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val raw = cookies.getCookie(url) ?: continue
        val token = raw.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("sp_dc=") }
            ?.substringAfter("=")
            ?.takeIf { it.isNotBlank() }
        if (token != null) return token
    }
    return null
}

/**
 * Signs the login WebView out of Spotify. Disconnecting only forgot the
 * stored cookie, and the WebView's own jar would have signed the next
 * "Sign in" straight back in. Only Spotify's cookies go — the same jar holds
 * the YouTube Music sign-in.
 */
fun clearSpotifyWebSession() {
    val cookies = CookieManager.getInstance()
    for (url in SPOTIFY_COOKIE_URLS) {
        val names = cookies.getCookie(url)?.split(";")
            ?.map { it.substringBefore("=").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        for (name in names) {
            val expired = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
            cookies.setCookie(url, expired)
            cookies.setCookie(url, "$expired; Domain=.spotify.com")
        }
    }
    cookies.flush()
}

/**
 * In-app Spotify sign-in. Lets the real accounts.spotify.com page handle the
 * login, then hands the `sp_dc` cookie to [onConnected] once the page lands on
 * open.spotify.com.
 *
 * Must be hosted as a root overlay, like [DiscordLoginScreen], never inside the
 * page content: that subtree is the `hazeSource` and the glass backdrop, both of
 * which record it into a layer and draw it a second time. A WebView drawn twice
 * flickers, drops parts of the page, and on some devices takes the app down in
 * Chromium's draw functor.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SpotifyLoginScreen(
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    LaunchedEffect(sent) {
        if (sent) return@LaunchedEffect
        while (true) {
            val token = extractSpdcToken()
            if (token != null) {
                sent = true
                webView?.visibility = View.GONE
                onConnected(token)
                break
            }
            delay(350L)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = browserUserAgent(settings.userAgentString)
                setBackgroundColor(0xFF121212.toInt())
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                val checkAndDeliver = { view: WebView, url: String? ->
                    if (!sent) {
                        val token = extractSpdcToken()
                        if (token != null) {
                            sent = true
                            view.visibility = View.GONE
                            onConnected(token)
                        } else if (url?.startsWith("https://open.spotify.com") == true) {
                            // If landed on open.spotify.com before token read, hide webview so playback disabled is not visible
                            view.visibility = View.GONE
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val urlStr = request.url.toString()
                        if (urlStr.startsWith("https://open.spotify.com")) {
                            checkAndDeliver(view, urlStr)
                        }
                        return !isLoginHost(request.url)
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        canGoBack = view.canGoBack()
                        checkAndDeliver(view, url)
                    }

                    override fun onLoadResource(view: WebView, url: String?) {
                        checkAndDeliver(view, url)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        canGoBack = view.canGoBack()
                        checkAndDeliver(view, url)
                    }
                }
                webView = this
                loadUrl(LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )

    BackHandler(enabled = canGoBack) {
        webView?.goBack()
    }
}
