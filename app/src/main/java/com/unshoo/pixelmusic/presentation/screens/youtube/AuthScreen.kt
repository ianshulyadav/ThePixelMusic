package com.unshoo.pixelmusic.presentation.screens.youtube

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.unshoo.pixelmusic.data.remote.youtube.Constants
import kotlinx.coroutines.flow.collectLatest
import org.json.JSONObject

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AuthScreen(
    onBack: () -> Unit,
    authViewModel: AuthViewModel = hiltViewModel()
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        authViewModel.eventFlow.collectLatest { event ->
            when (event) {
                AuthViewModel.ScreenEvent.Out.LoginCompleted -> {
                    Toast.makeText(context, "Successfully logged in!", Toast.LENGTH_SHORT).show()
                    onBack()
                }
                AuthViewModel.ScreenEvent.Out.LoginFailed -> {
                    Toast.makeText(context, "Couldn't save your YouTube Music sign-in", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        val page = url?.let(Uri::parse) ?: return
                        if (view == null || page.scheme != "https" || page.host != "music.youtube.com") return
                        view.evaluateJavascript(
                            """
                            (function() {
                              if (location.origin !== 'https://music.youtube.com') return null;
                              function config(key) {
                                var value = window.ytcfg && window.ytcfg.get && window.ytcfg.get(key);
                                return value == null && window.yt && window.yt.config_
                                    ? window.yt.config_[key] : value;
                              }
                              var loggedIn = config('LOGGED_IN');
                              if (loggedIn !== true && loggedIn !== 'true') return null;
                              return {dataSyncId: config('DATASYNC_ID') || ''};
                            })()
                            """.trimIndent()
                        ) { result ->
                            if (view.url == url) {
                                val session = runCatching { JSONObject(result.orEmpty()) }.getOrNull()
                                session?.let { authViewModel.completeLogin(it.optString("dataSyncId")) }
                            }
                        }
                    }
                }
                loadUrl(Constants.Auth.START_URL)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        }
    )
}
