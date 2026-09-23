package com.cocakova.pygmalion.compile

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cocakova.pygmalion.net.Http
import com.cocakova.pygmalion.net.ServerSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.net.URI

/**
 * The last word on compiling a workflow: the server's own frontend, in an invisible WebView.
 * Whatever the server's frontend and its node packs' JavaScript do — widgets computed at queue
 * time, extension nodes, future frontend changes — happens here exactly as on the desktop.
 *
 * It is sandboxed to read-only: every request the page makes to the server is replayed through
 * our HTTP client (so auth headers apply to all of them), and anything other than a GET gets an
 * empty 200 instead of reaching the server — the page can never save settings, write userdata or
 * queue a prompt on the user's behalf.
 */
class FrontendOracle(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun compile(session: ServerSession, workflowJson: String, timeoutMs: Long = 60_000): JsonObject? {
        val result = CompletableDeferred<String?>()
        var web: WebView? = null
        withContext(Dispatchers.Main) {
            web = build(session, workflowJson, result)
        }
        val out = withTimeoutOrNull(timeoutMs) { result.await() }
        withContext(Dispatchers.Main) { web?.destroy() }
        return out?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build(session: ServerSession, workflowJson: String, result: CompletableDeferred<String?>): WebView {
        val origin = URI(session.client.endpoint.root)
        val headers = session.client.endpoint.headers
        val w = WebView(context)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.addJavascriptInterface(object {
            @JavascriptInterface fun done(s: String) { result.complete(s) }
            @JavascriptInterface fun fail(s: String) { result.complete(null) }
        }, "PygOracle")
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != origin.host || (u.port != origin.port && origin.port != -1)) return null
                if (req.method != "GET") return WebResourceResponse("application/json", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream("{}".toByteArray()))
                return runCatching {
                    val r = Http.okhttp.newCall(
                        Request.Builder().url(u.toString()).apply { headers.forEach { (k, v) -> header(k, v) } }.build(),
                    ).execute()
                    val type = r.header("Content-Type") ?: "application/octet-stream"
                    WebResourceResponse(
                        type.substringBefore(';'), "utf-8", r.code, r.message.ifBlank { "OK" },
                        r.headers.associate { (k, v) -> k to v }, r.body!!.byteStream(),
                    )
                }.getOrNull()
            }

            override fun onPageFinished(view: WebView, url: String) {
                // The workflow travels as base64 and is parsed in the page — never spliced into
                // script text.
                val b64 = android.util.Base64.encodeToString(workflowJson.toByteArray(), android.util.Base64.NO_WRAP)
                val script = """
                    (function(){
                      const wf = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob('$b64'), c => c.charCodeAt(0))));
                      let tries = 0;
                      const tick = async () => {
                        const a = window.app;
                        if (!a || !a.graph || !a.graphToPrompt) { if (++tries > 240) { PygOracle.fail('no app'); return; } setTimeout(tick, 250); return; }
                        try {
                          await a.loadGraphData(wf, true, true, 'pygmalion');
                          const r = await a.graphToPrompt();
                          PygOracle.done(JSON.stringify(r.output));
                        } catch (e) { PygOracle.fail(String(e)); }
                      };
                      tick();
                    })();
                """.trimIndent()
                view.evaluateJavascript(script, null)
            }
        }
        w.loadUrl(session.client.endpoint.root + "/", headers)
        return w
    }

    companion object {
        fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()
        val main = Handler(Looper.getMainLooper())
    }
}
