package org.evsyukov.hermes.ui.setup

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.evsyukov.hermes.data.server.LocalForwardProxy
import org.evsyukov.hermes.data.server.ProxySettings
import java.util.concurrent.Executors

/**
 * The sign-in page inside the app, for a server the system browser cannot reach: WebView traffic goes
 * through [LocalForwardProxy] along the server's route, the loopback callback stays direct.
 * Passkeys work here once the identity provider lists the app in its Digital Asset Links.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebSignIn(url: String, route: ProxySettings, onCancel: () -> Unit) {
    var ready by remember { mutableStateOf(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) }
    val forward = remember(route) { LocalForwardProxy(route) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(forward) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance().setProxyOverride(
                ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${forward.port}").build(),
                executor,
            ) { ready = true }
        }
        onDispose {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance().clearProxyOverride(executor) {}
            }
            forward.close()
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = { Text("Вход", fontSize = 17.sp) },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Отмена") } },
        )
        if (ready) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                            WebSettingsCompat.setWebAuthenticationSupport(settings, WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP)
                        }
                        webViewClient = object : WebViewClient() {
                            // The page after the callback links back to the app; the sign-in already finished.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                request.url.scheme != "http" && request.url.scheme != "https"
                        }
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
