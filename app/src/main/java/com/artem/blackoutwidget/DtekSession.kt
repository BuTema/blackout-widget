package com.artem.blackoutwidget

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Receives the answer of one ajax call from the page. Must be a public class. */
class DtekSessionBridge(private val onReply: (String, Boolean, String) -> Unit) {
    @JavascriptInterface
    fun reply(id: String, ok: Boolean, payload: String) {
        onReply(id, ok, payload)
    }
}

/**
 * One hidden WebView on the DTEK page that stays open for several ajax calls.
 *
 * The address form needs many small calls (street list, house list); loading the page for each one, as
 * [DtekClient] does for a refresh, would be far too slow. The page is loaded once, on the first [post],
 * and every call after that is a fetch() from inside it, so cookies and the CSRF token come for free.
 *
 * Create it on the main thread, call [post] from any coroutine, and [close] it when done.
 */
class DtekSession(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    // Everything below is touched on the main thread only.
    private var web: WebView? = null
    private var ready: CompletableDeferred<Unit>? = null
    private val pending = HashMap<String, (Result<String>) -> Unit>()
    private var nextId = 0

    /** Sends one ajax call (`method` plus the indexed `data[i][name|value]` fields) and returns the raw body. */
    suspend fun post(method: String, fields: List<Pair<String, String>>): String {
        val body = withContext(Dispatchers.Main) {
            val answer = withTimeoutOrNull(CALL_TIMEOUT_MS) {
                ensureOpen()
                call(method, fields)
            }
            // A page that never became ready is thrown away, so the next call starts a fresh one.
            if (answer == null && ready?.isCompleted != true) teardownPage()
            answer
        }
        return body ?: throw DtekException("Timed out while talking to DTEK")
    }

    /** Must be called on the main thread (from a composable's onDispose, for instance). */
    fun close() {
        handler.removeCallbacksAndMessages(null)
        teardownPage()
        val waiting = pending.values.toList()
        pending.clear()
        for (callback in waiting) callback(Result.failure(DtekException("Closed")))
    }

    private fun teardownPage() {
        web?.stopLoading()
        web?.destroy()
        web = null
        val waiting = ready
        ready = null
        waiting?.completeExceptionally(DtekException("Closed"))
    }

    private suspend fun ensureOpen() {
        val existing = ready ?: CompletableDeferred<Unit>().also {
            ready = it
            open(it)
        }
        try {
            existing.await()
        } catch (e: DtekException) {
            // The page failed to load: forget it, so a later call may try again.
            if (ready === existing) teardownPage()
            throw e
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun open(deferred: CompletableDeferred<Unit>) {
        val w = WebView(appContext)
        web = w
        var polling = false
        var attempts = 0

        fun poll() {
            if (web !== w || deferred.isCompleted) return
            attempts++
            w.evaluateJavascript(READY_JS) { value ->
                if (deferred.isCompleted) return@evaluateJavascript
                if (value == "true") {
                    deferred.complete(Unit)
                } else if (attempts >= MAX_ATTEMPTS) {
                    deferred.completeExceptionally(
                        DtekException("The DTEK page did not load (blocked or page changed)"),
                    )
                } else {
                    handler.postDelayed({ poll() }, 1000L)
                }
            }
        }

        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        // Same reason as in DtekClient: look like a normal mobile Chrome, not an embedded WebView.
        w.settings.userAgentString = w.settings.userAgentString.replace("; wv)", ")")
        CookieManager.getInstance().setAcceptCookie(true)
        w.addJavascriptInterface(
            DtekSessionBridge { id, ok, payload -> handler.post { complete(id, ok, payload) } },
            "AndroidSession",
        )
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // The page may redirect a few times (bot-protection challenge); start polling once.
                if (!polling) {
                    polling = true
                    poll()
                }
            }
        }
        w.loadUrl(PAGE_URL)
    }

    private suspend fun call(method: String, fields: List<Pair<String, String>>): String =
        suspendCancellableCoroutine { cont ->
            val w = web
            if (w == null) {
                cont.resumeWithException(DtekException("Closed"))
                return@suspendCancellableCoroutine
            }
            val id = (++nextId).toString()
            pending[id] = { result ->
                result.fold(
                    onSuccess = { cont.resume(it) },
                    onFailure = { cont.resumeWithException(it) },
                )
            }
            cont.invokeOnCancellation { handler.post { pending.remove(id) } }
            w.evaluateJavascript(buildCall(id, method, fields), null)
        }

    private fun complete(id: String, ok: Boolean, payload: String) {
        val callback = pending.remove(id) ?: return
        callback(if (ok) Result.success(payload) else Result.failure(DtekException(payload)))
    }

    private fun buildCall(id: String, method: String, fields: List<Pair<String, String>>): String {
        val array = JSONArray()
        for ((name, value) in fields) array.put(JSONArray().put(name).put(value))
        return CALL_JS
            .replace("__ID__", JSONObject.quote(id))
            .replace("__METHOD__", JSONObject.quote(method))
            .replace("__FIELDS__", array.toString())
    }

    private companion object {
        const val PAGE_URL = "https://www.dtek-oem.com.ua/ua/shutdowns"
        const val CALL_TIMEOUT_MS = 60_000L
        const val MAX_ATTEMPTS = 45

        const val READY_JS =
            "(function(){return typeof DisconSchedule !== 'undefined' && !!DisconSchedule.fact;})()"

        // Runs inside the page: the same request the site's own address form sends.
        val CALL_JS = """
            (function () {
              var id = __ID__;
              try {
                var meta = document.querySelector('meta[name="csrf-token"]');
                var token = meta ? meta.getAttribute('content')
                  : (window.yii && yii.getCsrfToken ? yii.getCsrfToken() : '');
                var fields = __FIELDS__;
                var body = new URLSearchParams();
                body.append('method', __METHOD__);
                for (var i = 0; i < fields.length; i++) {
                  body.append('data[' + i + '][name]', fields[i][0]);
                  body.append('data[' + i + '][value]', fields[i][1]);
                }
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
                  .then(function (t) { AndroidSession.reply(id, true, t); })
                  .catch(function (e) { AndroidSession.reply(id, false, 'fetch: ' + e); });
              } catch (e) {
                AndroidSession.reply(id, false, 'script: ' + e);
              }
            })();
        """.trimIndent()
    }
}
