package com.kemzy.liveavatar

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.util.regex.Pattern

/**
 * Controls the Kaggle notebook from inside Kémzy.
 *
 * Flow:
 * 1) Load the configured Kaggle notebook in an in-app WebView.
 * 2) Click "Run All"/"Save & Run All" when available.
 * 3) Poll the notebook output for the temporary tunnel URL printed by the worker.
 *
 * No Kaggle API key is stored in the APK.
 */
class KaggleNotebookRunner(
    private val host: FrameLayout,
    private val statusView: TextView,
    private val notebookUrl: String,
    private val onTunnelReady: (String) -> Unit,
    private val onLoginRequired: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private var webView: WebView? = null
    private var started = false

    private val tunnelPattern = Pattern.compile(
        "(wss?://[^\\s\\\"'<>]+|https?://[^\\s\\\"'<>]+)",
        Pattern.CASE_INSENSITIVE
    )

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
        view.settings.loadsImagesAutomatically = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)

        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                statusView.text = "Connecting to GPU…"
                clickRunAll(view)
                pollNotebook(view)
            }
        }
        view.webChromeClient = WebChromeClient()

        host.addView(
            view,
            FrameLayout.LayoutParams(2, 2).apply {
                leftMargin = 1
                topMargin = 1
            }
        )
        view.loadUrl(notebookUrl)
    }

    private fun clickRunAll(view: WebView) {
        val script = """
            (function() {
              const wanted = [
                'save & run all',
                'run all',
                'run all cells',
                'run'
              ];
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

    private fun pollNotebook(view: WebView) {
        val script = """
            (function() {
              const text = document.body ? document.body.innerText : '';
              const login = /sign in|log in|login/i.test(text) &&
                            /kaggle/i.test(document.title + ' ' + text);
              const m = text.match(/(?:wss?:\/\/|https?:\/\/)[^\s"'<>]+/i);
              return JSON.stringify({
                login: login,
                text: text.slice(-12000),
                url: m ? m[0] : ''
              });
            })();
        """.trimIndent()

        view.evaluateJavascript(script) { raw ->
            val decoded = raw
                .removePrefix(""")
                .removeSuffix(""")
                .replace("\\"", """)
                .replace("\\\\", "\\")
            val urlMatch = tunnelPattern.matcher(decoded)
            if (urlMatch.find()) {
                val candidate = urlMatch.group(1)
                if (candidate != null && looksLikeTunnel(candidate)) {
                    statusView.text = "Loading LivePortrait…"
                    onTunnelReady(candidate.trimEnd('/'))
                    return@evaluateJavascript
                }
            }

            if (decoded.contains(""login":true")) {
                onLoginRequired()
                return@evaluateJavascript
            }

            statusView.text = "Loading LivePortrait…"
            view.postDelayed({ pollNotebook(view) }, 2500)
        }
    }

    private fun looksLikeTunnel(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("wss://") ||
            lower.startsWith("ws://") ||
            lower.contains("loca.lt") ||
            lower.contains("trycloudflare.com")
    }

    fun stop() {
        started = false
        webView?.stopLoading()
        webView?.destroy()
        webView = null
    }
}
