/*
 * Ported from ArchiveTune (2026) — © Rukamori, GPL-3.0 (ui/screens/settings/AppleMusicLoginScreen.kt).
 *
 * Apple Music sign-in. The Music User Token is captured from BOTH places the web player puts it:
 * the `media-user-token` cookie on the music.apple.com origin (the primary source — the web
 * player writes it there right after the Apple ID handshake, including HttpOnly & URI-encoded forms)
 * and MusicKit / localStorage once the player SPA boots. Every candidate is handed to the AMP API for
 * verification (`/v1/me/storefront` answers 200 only for a valid pairing), and the winner is
 * persisted alongside the verified developer token.
 */

package com.unshoo.pixelmusic.presentation.screens.lossless

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import com.unshoo.pixelmusic.R
import com.unshoo.pixelmusic.data.lossless.LosslessStreamResolver
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicAudioProvider
import com.unshoo.pixelmusic.data.lossless.applemusic.AppleMusicProvider
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicDevTokenKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicMediaUserTokenKey
import com.unshoo.pixelmusic.data.lossless.constants.AppleMusicSourceEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.LosslessStreamingEnabledKey
import com.unshoo.pixelmusic.data.lossless.constants.getAsync
import com.unshoo.pixelmusic.data.preferences.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

private const val LOGIN_URL = "https://music.apple.com/us/library/playlists"
private val COOKIE_ORIGINS = listOf(
    "https://music.apple.com",
    "https://beta.music.apple.com",
)
private const val TAG = "AppleMusicLogin"

private const val HOOK_JS = """
(function() {
  if (window.__appleHooked) return;
  window.__appleHooked = true;
  var wanted = { 'authorization': 1, 'media-user-token': 1 };
  var seen = {};
  function report(name, value, url) {
    try {
      name = String(name).toLowerCase();
      if (!wanted[name] || !value) return;
      var key = name + '|' + value;
      if (seen[key]) return;
      seen[key] = 1;
      if (window.AppleLoginBridge && window.AppleLoginBridge.onHeader) {
        window.AppleLoginBridge.onHeader(name, String(value), String(url || location.href));
      }
    } catch (e) {}
  }
  function scan(headers, url) {
    try {
      if (!headers) return;
      if (typeof Headers !== 'undefined' && headers instanceof Headers) {
        headers.forEach(function(v, k) { report(k, v, url); });
      } else if (Array.isArray(headers)) {
        headers.forEach(function(pair) { report(pair[0], pair[1], url); });
      } else if (typeof headers === 'object') {
        Object.keys(headers).forEach(function(k) { report(k, headers[k], url); });
      }
    } catch (e) {}
  }
  try {
    var originalFetch = window.fetch;
    if (originalFetch) {
      window.fetch = function(input, init) {
        try {
          var url = (typeof input === 'string') ? input : (input && input.url);
          if (input && typeof input === 'object' && input.headers) scan(input.headers, url);
          if (init && init.headers) scan(init.headers, url);
        } catch (e) {}
        return originalFetch.apply(this, arguments);
      };
    }
  } catch (e) {}
  try {
    var originalOpen = XMLHttpRequest.prototype.open;
    var originalSet = XMLHttpRequest.prototype.setRequestHeader;
    XMLHttpRequest.prototype.open = function(method, url) {
      try { this.__appleUrl = url; } catch (e) {}
      return originalOpen.apply(this, arguments);
    };
    XMLHttpRequest.prototype.setRequestHeader = function(name, value) {
      report(name, value, this.__appleUrl);
      return originalSet.apply(this, arguments);
    };
  } catch (e) {}
})();
"""

private const val APPLE_AUTO_SIGN_IN_JS = """
(function() {
  try {
    if (window.__appleSignInPressed) return 'pressed';
    var m = window.MusicKit && MusicKit.getInstance && MusicKit.getInstance();
    if (m && m.isAuthorized) return 'authorized';
    var button = document.querySelector('[data-testid="sign-in-button"]');
    if (!button) return 'waiting';
    window.__appleSignInPressed = true;
    button.click();
    return 'pressed';
  } catch (e) { return 'error'; }
})();
"""

private const val APPLE_PAGE_PROBE_JS = """
(function() {
  try {
    if (!window.MusicKit || !MusicKit.getInstance) return null;
    var m = MusicKit.getInstance();
    if (!m) return null;
    return JSON.stringify({ dev: m.developerToken || null, user: m.musicUserToken || null });
  } catch (e) { return null; }
})();
"""

private class AppleLoginBridge(
    private val onHeaderCaptured: (String, String) -> Unit,
) {
    @android.webkit.JavascriptInterface
    fun onHeader(name: String?, value: String?, url: String?) {
        if (!name.isNullOrBlank() && !value.isNullOrBlank()) {
            onHeaderCaptured(name.trim().lowercase(), value.trim())
        }
    }
}


private val MEDIA_TOKEN_JWT_REGEX = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
private val MEDIA_TOKEN_CLASSIC_REGEX = Regex("^0\\.[A-Za-z0-9+/=_-]{40,}$")
private val DEV_TOKEN_JWT_REGEX = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

/**
 * Standardized Apple Music WebView login & token manager:
 *  - Reads `media-user-token` from CookieManager (raw, quoted, and URI-decoded, including HttpOnly).
 *  - Detects Apple Music session cookies (`its.pod=`, `pxro=`, `media-user-token=`).
 *  - Resolves developer JWT via user override -> live MusicKit JS -> `AppleMusicProvider.currentDevToken()` -> forced refresh.
 *  - Verifies `(mediaToken, devToken)` against `/v1/me/storefront` via `AppleMusicAudioProvider.verifyTokens`.
 */
object AppleMusicLoginManager {
    val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    fun looksLikeMediaUserToken(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        if (value.length < 40 || value.length > 4096) return false
        return MEDIA_TOKEN_JWT_REGEX.matches(value) || MEDIA_TOKEN_CLASSIC_REGEX.matches(value)
    }

    fun looksLikeDeveloperJwt(value: String?): Boolean {
        if (value.isNullOrBlank()) return false
        return value.length in 80..4096 && DEV_TOKEN_JWT_REGEX.matches(value)
    }

    fun hasSessionCookie(): Boolean =
        COOKIE_ORIGINS.any { origin ->
            cookieManager.getCookie(origin)
                ?.split(';')
                ?.any { part ->
                    val trimmed = part.trim()
                    trimmed.startsWith("its.pod=", ignoreCase = true) ||
                        trimmed.startsWith("pxro=", ignoreCase = true) ||
                        trimmed.startsWith("media-user-token=", ignoreCase = true)
                } == true
        }

    fun readMediaUserToken(): String? {
        for (origin in COOKIE_ORIGINS) {
            val jar = cookieManager.getCookie(origin) ?: continue
            for (raw in jar.split(';')) {
                val entry = raw.trim()
                val eq = entry.indexOf('=')
                if (eq <= 0) continue
                if (!entry.substring(0, eq).equals("media-user-token", ignoreCase = true)) continue
                val value = entry.substring(eq + 1).trim().trim('"')
                if (looksLikeMediaUserToken(value)) return value
                val decoded = runCatching { Uri.decode(value).trim().trim('"') }.getOrDefault(value)
                if (looksLikeMediaUserToken(decoded)) return decoded
            }
        }
        return null
    }

    suspend fun currentDevToken(pastedOverride: String = "", webScrapedCandidate: String? = null): String? =
        withContext(Dispatchers.IO) {
            pastedOverride.trim().takeIf { it.isNotBlank() }
                ?: webScrapedCandidate?.trim()?.takeIf(::looksLikeDeveloperJwt)
                ?: runCatching { AppleMusicProvider.currentDevToken() }.getOrNull()
        }

    suspend fun verifyTokens(mediaToken: String, devToken: String): Boolean =
        withContext(Dispatchers.IO) {
            AppleMusicAudioProvider.verifyTokens(mediaToken, devToken)
        }
}

/**
 * Probes both `MusicKit.getInstance()` (if initialized by the SPA) and `localStorage` for
 * `media-user-token` candidates plus the active web-player developer JWT.
 */
private const val TOKEN_PROBE_JS = """
(function(){
  function okMedia(v){
    if(!v||typeof v!=='string')return false;
    if(v.length<40||v.length>4096)return false;
    return /^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(v)
      || /^0\.[A-Za-z0-9+\/=_-]{40,}$/.test(v);
  }
  var media=[];
  var dev="";
  try{
    if(window.MusicKit && typeof window.MusicKit.getInstance==='function'){
      var mk=window.MusicKit.getInstance();
      if(mk){
        if(okMedia(mk.musicUserToken)) media.push(mk.musicUserToken);
        if(typeof mk.developerToken==='string' && mk.developerToken.indexOf('eyJ')===0){
          dev=mk.developerToken;
        }
      }
    }
  }catch(e){}
  try{
    var d=localStorage.getItem('media-user-token');
    if(okMedia(d)) media.push(d);
  }catch(e){}
  try{
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i); if(!k)continue;
      var lk=k.toLowerCase();
      if(lk.indexOf('token')===-1)continue;
      if(lk.indexOf('developer')>=0||lk.indexOf('dev-')>=0||lk.indexOf('devtoken')>=0
        ||lk.indexOf('amtv')>=0||lk.indexOf('jwt')>=0||lk.indexOf('media-user-token')>=0){
        if(!dev){
          var dv=localStorage.getItem(k);
          if(dv && typeof dv==='string' && dv.indexOf('eyJ')===0) dev=dv;
        }
        continue;
      }
      var v=localStorage.getItem(k);
      if(okMedia(v)) media.push(v);
      else if(v&&v.length<2000&&v.charAt(0)==='{'){
        try{
          var j=JSON.parse(v);
          for(var key in j){ if(okMedia(j[key])) media.push(j[key]); }
        }catch(e2){}
      }
    }
  }catch(e){}
  return JSON.stringify({media: media, dev: dev});
})()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppleMusicLoginScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val handled = remember { AtomicBoolean(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var capturedDevToken by remember { mutableStateOf<String?>(null) }
    var capturedUserToken by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishLogin(mediaToken: String, webDevToken: String? = null) {
        if (!handled.compareAndSet(false, true)) return
        scope.launch {
            val pastedDev = withContext(Dispatchers.IO) {
                context.dataStore.getAsync(AppleMusicDevTokenKey, "").trim()
            }
            var devToken = AppleMusicLoginManager.currentDevToken(pastedDev, webDevToken ?: capturedDevToken)
            var verified = devToken != null && AppleMusicLoginManager.verifyTokens(mediaToken, devToken)

            if (!verified && pastedDev.isBlank()) {
                val refreshed = withContext(Dispatchers.IO) {
                    runCatching { AppleMusicProvider.refreshToken() }.getOrNull()
                }
                if (!refreshed.isNullOrBlank() && refreshed != devToken) {
                    devToken = refreshed
                    verified = AppleMusicLoginManager.verifyTokens(mediaToken, refreshed)
                }
            }

            if (!verified || devToken == null) {
                Timber.tag(TAG).w("media-user-token captured but verification failed; not persisting")
                handled.set(false)
                toast(context.getString(R.string.lossless_applemusic_login_failed))
                return@launch
            }

            context.dataStore.edit { prefs ->
                prefs[AppleMusicMediaUserTokenKey] = mediaToken
                if (devToken.isNotBlank()) prefs[AppleMusicDevTokenKey] = devToken
                prefs[AppleMusicSourceEnabledKey] = true
                prefs[LosslessStreamingEnabledKey] = true
            }
            AppleMusicAudioProvider.clearStorefrontCache()
            LosslessStreamResolver.clearAll()
            toast(context.getString(R.string.lossless_applemusic_login_success))
            onBack()
        }
    }

    fun probeForToken(view: WebView) {
        if (handled.get()) return
        view.evaluateJavascript(HOOK_JS, null)
        view.evaluateJavascript(APPLE_AUTO_SIGN_IN_JS, null)
        view.evaluateJavascript(APPLE_PAGE_PROBE_JS) { raw ->
            if (handled.get() || raw.isNullOrBlank() || raw == "null") return@evaluateJavascript
            runCatching {
                val decoded = if (raw.startsWith("\"")) JSONArray("[$raw]").optString(0) else raw
                val obj = JSONObject(decoded)
                val user = obj.optString("user").takeIf { it.isNotBlank() && it != "null" }
                val dev = obj.optString("dev").takeIf { it.isNotBlank() && it != "null" }
                if (dev != null && AppleMusicLoginManager.looksLikeDeveloperJwt(dev)) {
                    capturedDevToken = dev
                }
                if (user != null && AppleMusicLoginManager.looksLikeMediaUserToken(user)) {
                    capturedUserToken = user
                    finishLogin(user, dev)
                    return@evaluateJavascript
                }
            }
        }

        val cookieToken = AppleMusicLoginManager.readMediaUserToken()
        if (cookieToken != null && !handled.get()) {
            finishLogin(cookieToken, capturedDevToken)
            return
        }

        view.evaluateJavascript(TOKEN_PROBE_JS) { rawJson ->
            if (handled.get() || rawJson.isNullOrBlank() || rawJson == "null") return@evaluateJavascript
            runCatching {
                val decoded = if (rawJson.startsWith("\"")) JSONArray("[$rawJson]").optString(0) else rawJson
                val obj = JSONObject(decoded)
                val dev = obj.optString("dev").takeIf { it.isNotBlank() }
                if (dev != null && AppleMusicLoginManager.looksLikeDeveloperJwt(dev)) {
                    capturedDevToken = dev
                }
                val arr = obj.optJSONArray("media")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val cand = arr.optString(i).trim()
                        if (AppleMusicLoginManager.looksLikeMediaUserToken(cand)) {
                            finishLogin(cand, dev)
                            break
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            webView?.let { view ->
                if (!handled.get()) {
                    view.post { probeForToken(view) }
                }
            }
        }
    }

    AuthWebViewScreen(
        title = stringResource(R.string.lossless_applemusic_login),
        subtitle = stringResource(R.string.lossless_applemusic_login_subtitle),
        onBack = onBack,
        onRelease = { released -> if (webView === released) webView = null },
        factory = { ctx ->
            WebView(ctx).apply {
                webView = this
                addJavascriptInterface(
                    AppleLoginBridge { name, value ->
                        when (name) {
                            "authorization" -> {
                                val token = value.removePrefix("Bearer ").removePrefix("bearer ").trim()
                                if (AppleMusicLoginManager.looksLikeDeveloperJwt(token)) {
                                    capturedDevToken = token
                                }
                            }
                            "media-user-token" -> {
                                if (AppleMusicLoginManager.looksLikeMediaUserToken(value)) {
                                    capturedUserToken = value
                                    finishLogin(value, capturedDevToken)
                                }
                            }
                        }
                    },
                    "AppleLoginBridge"
                )
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            view.evaluateJavascript(HOOK_JS, null)
                        }

                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            view.evaluateJavascript(HOOK_JS, null)
                            probeForToken(view)
                        }
                    }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                resetAuthWebViewSession(ctx, this, clearCookies = false) {
                    loadUrl(LOGIN_URL)
                }
            }
        },
    )
}
