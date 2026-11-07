package com.artem.blackoutwidget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class DtekException(message: String) : Exception(message)

/** Receives the JSON result that the injected script hands back. Must be a public class. */
class DtekBridge(private val onResult: (String) -> Unit) {
    @JavascriptInterface
    fun result(json: String) {
        onResult(json)
    }
}

/**
 * Loads the DTEK shutdowns page in a hidden WebView and reads the data from inside the page.
 *
 * A real WebView is used on purpose: the site sits behind bot protection (Incapsula) and its ajax
 * endpoint needs the page's cookies and CSRF token. Inside the page both are handled by the browser
 * engine, so we just call fetch() from there.
 */
class DtekClient(context: Context) {
    private val appContext = context.applicationContext

    class Raw(val fact: String, val home: String?, val warning: String?)

    /** Pass blank [city]/[street] to skip the address lookup and only read the schedule. */
    suspend fun fetch(city: String, street: String): Raw {
        val raw = withContext(Dispatchers.Main) {
            withTimeoutOrNull(TIMEOUT_MS) { loadInWebView(city, street) }
        }
        return raw ?: throw DtekException("Timed out while loading the DTEK page")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadInWebView(city: String, street: String): Raw =
        suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            val web = WebView(appContext)
            var done = false
            var polling = false
            var attempts = 0

            fun finish(result: Result<Raw>) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                web.stopLoading()
                web.destroy()
                if (cont.isActive) {
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { cont.resumeWithException(it) },
                    )
                }
            }

            fun poll() {
                if (done) return
                attempts++
                web.evaluateJavascript(READY_JS) { value ->
                    if (done) return@evaluateJavascript
                    if (value == "true") {
                        web.evaluateJavascript(buildScript(city, street), null)
                    } else if (attempts >= MAX_ATTEMPTS) {
                        finish(
                            Result.failure(
                                DtekException("The DTEK page did not expose the schedule (blocked or page changed)"),
                            ),
                        )
                    } else {
                        handler.postDelayed({ poll() }, 1000L)
                    }
                }
            }

            web.settings.javaScriptEnabled = true
            web.settings.domStorageEnabled = true
            // The default WebView user agent contains "; wv", which bot protection can flag as an
            // embedded browser. Present the same string a normal mobile Chrome would.
            web.settings.userAgentString = web.settings.userAgentString.replace("; wv)", ")")
            CookieManager.getInstance().setAcceptCookie(true)

            web.addJavascriptInterface(
                DtekBridge { json ->
                    handler.post {
                        try {
                            val o = JSONObject(json)
                            val fact = o.optString("fact", "")
                            val warning = o.optString("error", "").ifEmpty { null }
                            if (fact.isEmpty()) {
                                finish(Result.failure(DtekException(warning ?: "Empty schedule data")))
                            } else {
                                finish(Result.success(Raw(fact, o.optString("home", "").ifEmpty { null }, warning)))
                            }
                        } catch (e: Exception) {
                            finish(Result.failure(e))
                        }
                    }
                },
                "AndroidBridge",
            )

            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // The page may redirect a few times (bot-protection challenge); start polling once.
                    if (!polling) {
                        polling = true
                        poll()
                    }
                }
            }

            cont.invokeOnCancellation {
                handler.post { finish(Result.failure(DtekException("Cancelled"))) }
            }

            web.loadUrl(PAGE_URL)
        }

    private fun buildScript(city: String, street: String): String =
        SCRIPT
            .replace("__CITY__", JSONObject.quote(city))
            .replace("__STREET__", JSONObject.quote(street))

    private companion object {
        const val PAGE_URL = "https://www.dtek-oem.com.ua/ua/shutdowns"
        const val TIMEOUT_MS = 90_000L
        const val MAX_ATTEMPTS = 45

        const val READY_JS =
            "(function(){return typeof DisconSchedule !== 'undefined' && !!DisconSchedule.fact;})()"

        // Runs inside the page. Reports back through AndroidBridge.result(json).
        val SCRIPT = """
            (function () {
              var out = {};
              try { out.fact = JSON.stringify(DisconSchedule.fact); }
              catch (e) { out.error = 'fact: ' + e; }
              var city = __CITY__;
              var street = __STREET__;
              if (!city || !street) { AndroidBridge.result(JSON.stringify(out)); return; }
              try {
                var meta = document.querySelector('meta[name="csrf-token"]');
                var token = meta ? meta.getAttribute('content')
                  : (window.yii && yii.getCsrfToken ? yii.getCsrfToken() : '');
                var body = new URLSearchParams();
                body.append('method', 'getHomeNum');
                body.append('data[0][name]', 'city');
                body.append('data[0][value]', city);
                body.append('data[1][name]', 'street');
                body.append('data[1][value]', street);
                body.append('data[2][name]', 'updateFact');
                body.append('data[2][value]', (DisconSchedule.fact && DisconSchedule.fact.update) || '');
                fetch('/ua/ajax', {
                  method: 'POST',
                  credentials: 'same-origin',
                  headers: {
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-CSRF-Token': token,
                    'X-Requested-With': 'XMLHttpRequest',
                    'Accept': 'application/json, text/javascript, */*; q=0.01'
                  },
                  body: body.toString()
                }).then(function (r) { return r.text(); })
                  .then(function (t) { out.home = t; AndroidBridge.result(JSON.stringify(out)); })
                  .catch(function (e) {
                    out.error = (out.error || '') + ' home: ' + e;
                    AndroidBridge.result(JSON.stringify(out));
                  });
              } catch (e) {
                out.error = (out.error || '') + ' home: ' + e;
                AndroidBridge.result(JSON.stringify(out));
              }
            })();
        """.trimIndent()
    }
}
