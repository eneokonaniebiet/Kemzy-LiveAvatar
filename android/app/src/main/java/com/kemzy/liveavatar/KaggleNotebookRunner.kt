package com.kemzy.liveavatar

import android.annotation.SuppressLint
import android.graphics.Color
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Starts the user's existing Kaggle notebook from an authenticated WebView,
 * then waits for the existing Render/Kaggle GPU worker to register.
 *
 * No Kaggle credential is stored in the APK and no temporary GPU URL is shown.
 */
class KaggleNotebookRunner(
    private val host: FrameLayout,
    private val statusView: TextView,
    private val notebookUrl: String,
    private val apiBaseUrl: String,
    private val onReady: () -> Unit,
    private val onLoginRequired: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var webView: WebView? = null
    private var started = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        if (started) return
        if (notebookUrl.isBlank() || notebookUrl.contains("YOUR_KAGGLE")) {
            onError("Kaggle notebook URL is not configured")
            return
        }
        started = true
        statusView.text = "Starting Kémzy AI…"

        val view = WebView(host.context)
        webView = view
        view.setBackgroundColor(Color.TRANSPARENT)
        view.alpha = 0.01f
        view.isClickable = false
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                statusView.text = "Connecting to GPU…"
                clickRunAll(view)
                pollReady()
            }
        }
        view.webChromeClient = WebChromeClient()

        host.addView(view, FrameLayout.LayoutParams(2, 2).apply {
            leftMargin = 1
            topMargin = 1
        })
        view.loadUrl(notebookUrl)
    }

    private fun clickRunAll(view: WebView) {
        val script = """
            (function() {
              const wanted = ['save & run all','run all','run all cells'];
              const nodes = Array.from(document.querySelectorAll('button,[role="button"],div'));
              for (const n of nodes) {
                const t = (n.innerText || n.getAttribute('aria-label') || '').trim().toLowerCase();
                if (wanted.some(w => t === w || t.includes(w))) {
                  try { n.click(); return 'clicked'; } catch(e) {}
                }
              }
              return 'not-found';
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    private fun pollReady() {
        if (!started) return
        Thread {
            try {
                val request = Request.Builder()
                    .url(apiBaseUrl.trimEnd('/') + "/ready")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    val json = runCatching { JSONObject(body) }.getOrNull()
                    val ready = response.isSuccessful && json?.optString("status") == "ready"
                    host.post {
                        if (!started) return@post
                        if (ready) {
                            statusView.text = "Loading LivePortrait…"
                            onReady()
                        } else {
                            statusView.text = "Connecting to GPU…"
                            host.postDelayed({ pollReady() }, 3000)
                        }
                    }
                }
            } catch (_: Throwable) {
                host.postDelayed({ pollReady() }, 3000)
            }
        }.start()
    }

    fun stop() {
        started = false
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }
}
