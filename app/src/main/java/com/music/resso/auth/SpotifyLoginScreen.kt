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

private const val JS_EXTRACT_USER = """
    (function() {
        try {
            var name = '';
            var avatar = '';
            var s = document.getElementById('session');
            if (s) {
                var d = JSON.parse(s.textContent || s.innerText);
                if (d && d.user) {
                    name = d.user.displayName || d.user.name || d.user.username || '';
                    if (d.user.images && d.user.images.length > 0) {
                        avatar = d.user.images[0].url || '';
                    }
                }
            }
            if (!avatar) {
                var img = document.querySelector('img[data-testid="user-widget-avatar"]') ||
                          document.querySelector('button[data-testid="user-widget-dropdown-button"] img') ||
                          document.querySelector('a[data-testid="user-widget-link"] img') ||
                          document.querySelector('figure[data-testid="user-widget-avatar"] img');
                if (img && img.src) avatar = img.src;
            }
            if (!name) {
                var b = document.querySelector('[data-testid="user-widget-link"]') ||
                        document.querySelector('button[data-testid="user-widget-dropdown-button"]');
                if (b) {
                    name = b.getAttribute('aria-label') || b.innerText || '';
                }
            }
            if (!name) {
                var u = document.getElementById('login-username');
                if (u && u.value) name = u.value;
            }
            return JSON.stringify({ name: (name || '').trim(), avatar: (avatar || '').trim() });
        } catch(e) {}
        return '';
    })()
"""

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
    onConnected: (token: String, userName: String?, userAvatar: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }

    val deliverToken = { view: WebView?, token: String ->
        if (!sent) {
            sent = true
            view?.visibility = View.GONE
            if (view != null) {
                view.evaluateJavascript(JS_EXTRACT_USER) { rawResult ->
                    val cleanStr = rawResult?.trim().orEmpty()
                    val unescaped = if (cleanStr.startsWith("\"") && cleanStr.endsWith("\"") && cleanStr.length >= 2) {
                        try {
                            org.json.JSONTokener(cleanStr).nextValue() as? String ?: cleanStr
                        } catch (_: Throwable) {
                            cleanStr.removeSurrounding("\"").replace("\\\"", "\"")
                        }
                    } else cleanStr
                    val json = runCatching { org.json.JSONObject(unescaped) }.getOrNull()
                    val cleanName = json?.optString("name")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                    val cleanAvatar = json?.optString("avatar")?.trim()?.takeIf { it.isNotBlank() && it != "null" }
                    onConnected(token, cleanName, cleanAvatar)
                }
            } else {
                onConnected(token, null, null)
            }
        }
    }

    LaunchedEffect(sent) {
        if (sent) return@LaunchedEffect
        while (true) {
            val token = extractSpdcToken()
            if (token != null) {
                deliverToken(webView, token)
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
                            deliverToken(view, token)
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
