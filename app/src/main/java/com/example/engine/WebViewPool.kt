package com.example.engine

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import com.example.data.model.TabItem
import java.util.concurrent.ConcurrentHashMap

class WebViewPool(
    private val context: Context,
    private val onPageTitleChanged: (tabId: String, title: String) -> Unit,
    private val onPageUrlChanged: (tabId: String, url: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    private val onLoadingProgressChanged: (tabId: String, progress: Int, isLoading: Boolean) -> Unit,
    private val onRecordClickReceived: (tabId: String, x: Float, y: Float) -> Unit
) {
    private val webViewMap = ConcurrentHashMap<String, WebView>()
    private val thumbnailCache = ConcurrentHashMap<String, Bitmap>()

    /** Session profile each tab actually runs in ("Default" when the device cannot isolate). */
    private val profileNameByTab = ConcurrentHashMap<String, String>()

    /**
     * 同步群控. When on, a touch on any window is replayed into every other live window.
     * The touched window is always the source, so group control works in the same-screen grid
     * without depending on which cell happens to be "focused".
     */
    var globalSyncEnabled: Boolean = false

    /** Timestamp of the last replayed MOVE, used to throttle the group-control touch stream. */
    private var lastSyncMoveAt = 0L

    /**
     * True while group control is dispatching into the other windows.
     *
     * The windows receiving a replayed event would otherwise broadcast it again themselves, so
     * the dispatch recursed through every window until the stack overflowed and killed the app.
     * Everything here runs synchronously on the UI thread, so a plain flag is enough.
     */
    private var isReplayingTouch = false

    private companion object {
        const val TAG = "WebViewPool"

        /** Minimum gap between replayed MOVE events, so group control cannot stutter the UI. */
        const val SYNC_MOVE_INTERVAL_MS = 33L

        /** How long a synthesised tap stays pressed - long enough for H5 games to register it. */
        const val TAP_HOLD_MS = 60L
    }

    /** True when this device's WebView can keep every account in its own cookie/storage jar. */
    val isSessionIsolationSupported: Boolean get() = SessionProfiles.isSupported()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(tab: TabItem): WebView {
        return webViewMap.getOrPut(tab.id) {
            val webView = SyncableWebView(context)

            // Hand this WebView its own account session (own cookies / localStorage / IndexedDB)
            // before it touches settings, the JS bridge or any URL - androidx.webkit throws
            // IllegalStateException if setProfile() happens any later than this.
            val requestedProfile = SessionProfiles.normalize(tab.profileName)
            val isIsolated = SessionProfiles.applyTo(webView, requestedProfile)
            profileNameByTab[tab.id] = if (isIsolated) requestedProfile else SessionProfiles.DEFAULT
            Log.i(
                TAG,
                "tab=${tab.id} -> session profile '$requestedProfile' isolated=$isIsolated"
            )

            // 同步群控: publish this window's touch stream so it can be replayed into the others.
            // The window the user actually touches is the source, which is what makes group
            // control usable in the same-screen grid.
            webView.touchObserver = { event ->
                if (globalSyncEnabled && !isReplayingTouch) replayTouch(tab.id, webView, event)
            }
            val cookieManager = if (isIsolated) {
                SessionProfiles.cookieManagerFor(requestedProfile) ?: CookieManager.getInstance()
            } else {
                CookieManager.getInstance()
            }

            webView.apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    allowFileAccess = true
                    allowContentAccess = true
                    mediaPlaybackRequiresUserGesture = false
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        safeBrowsingEnabled = false
                    }

                    // Apply custom User Agent if set
                    if (!tab.customUserAgent.isNullOrBlank()) {
                        userAgentString = tab.customUserAgent
                    }
                }

                // Accept cookies in this account's own session jar instead of one shared jar.
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)

                // Inject TabX Bridge for macro recording and automation
                addJavascriptInterface(
                    TabXBridge(
                        tabId = tab.id,
                        onPageClickRecorded = onRecordClickReceived
                    ),
                    TabXBridge.JS_BRIDGE_NAME
                )

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onLoadingProgressChanged(tab.id, 10, true)
                        url?.let {
                            onPageUrlChanged(tab.id, it, canGoBack(), canGoForward())
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onLoadingProgressChanged(tab.id, 100, false)
                        url?.let {
                            onPageUrlChanged(tab.id, it, canGoBack(), canGoForward())
                        }
                        // Inject touch listener script for macro recording
                        view?.evaluateJavascript(TabXBridge.RECORDING_INJECTION_SCRIPT, null)
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val requestUrl = request?.url?.toString() ?: return false
                        if (requestUrl.startsWith("http://") || requestUrl.startsWith("https://")) {
                            return false
                        }
                        // Allow custom schemes (e.g. GM99 pay, alipay, weixin)
                        return try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                            context.startActivity(intent)
                            true
                        } catch (e: Exception) {
                            true
                        }
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onLoadingProgressChanged(tab.id, newProgress, newProgress < 100)
                    }

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        super.onReceivedTitle(view, title)
                        if (!title.isNullOrBlank()) {
                            onPageTitleChanged(tab.id, title)
                        }
                    }
                }

                // Initial URL load
                loadUrl(tab.url)
            }
        }
    }

    fun updateTabUserAgent(tabId: String, newUserAgent: String) {
        val webView = webViewMap[tabId] ?: return
        if (newUserAgent.isBlank()) {
            webView.settings.userAgentString = null // Reset to default
        } else {
            webView.settings.userAgentString = newUserAgent
        }
        webView.reload()
    }

    fun applyRecordingState(active: Boolean) {
        val script = TabXBridge.setRecordingActiveScript(active)
        webViewMap.values.forEach { webView ->
            webView.evaluateJavascript(script, null)
        }
    }

    /**
     * Sends one tap into a single window (macro playback without group control).
     *
     * The tap is a real [MotionEvent] rather than a JavaScript-synthesised click: scripted DOM
     * events are untrusted and are ignored by most H5 games, which is why recorded macros
     * previously did nothing on the game canvas.
     */
    fun executeSimulatedClickOnTab(tabId: String, xPercent: Float, yPercent: Float, jitterPx: Int = 0) {
        val webView = webViewMap[tabId] ?: return
        webView.post { dispatchNativeTap(webView, xPercent, yPercent, jitterPx) }
    }

    /** Sends one tap into every live window (macro playback with group control). */
    fun executeSimulatedClickOnAllTabs(xPercent: Float, yPercent: Float, excludeTabId: String? = null, jitterPx: Int = 0) {
        webViewMap.forEach { (id, webView) ->
            if (excludeTabId == null || id != excludeTabId) {
                webView.post { dispatchNativeTap(webView, xPercent, yPercent, jitterPx) }
            }
        }
    }

    /**
     * Replays a touch from the window the user is touching into every other live window.
     *
     * Coordinates are relative, so a tap at 30%/70% of the touched window also lands at
     * 30%/70% of every other window even though the grid cells are a different pixel size.
     */
    private fun replayTouch(sourceTabId: String, source: WebView, event: MotionEvent) {
        val sourceWidth = source.width
        val sourceHeight = source.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        // A gesture emits far more MOVE events than the other windows can usefully consume.
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            val now = SystemClock.uptimeMillis()
            if (now - lastSyncMoveAt < SYNC_MOVE_INTERVAL_MS) return
            lastSyncMoveAt = now
        }

        webViewMap.forEach { (tabId, target) ->
            if (tabId == sourceTabId) return@forEach
            val targetWidth = target.width
            val targetHeight = target.height
            if (targetWidth <= 0 || targetHeight <= 0) return@forEach
            val clone = MotionEvent.obtain(event)
            try {
                if (targetWidth != sourceWidth || targetHeight != sourceHeight) {
                    clone.transform(
                        Matrix().apply {
                            setScale(
                                targetWidth.toFloat() / sourceWidth,
                                targetHeight.toFloat() / sourceHeight
                            )
                        }
                    )
                }
                isReplayingTouch = true
                try {
                    target.dispatchTouchEvent(clone)
                } finally {
                    isReplayingTouch = false
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Group control could not reach tab $tabId", t)
            } finally {
                clone.recycle()
            }
        }
    }

    /**
     * Presses and releases at a relative position inside [webView].
     * Must be called on the UI thread.
     */
    private fun dispatchNativeTap(webView: WebView, xPercent: Float, yPercent: Float, jitterPx: Int = 0) {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) {
            // The window is not laid out (background tab), so it has no coordinate space for
            // native input. Fall back to a scripted click - weaker, but better than nothing.
            webView.evaluateJavascript(
                TabXBridge.createSimulatedClickScript(xPercent, yPercent, jitterPx),
                null
            )
            return
        }

        val jitterX = if (jitterPx > 0) ((Math.random() - 0.5) * jitterPx * 2).toFloat() else 0f
        val jitterY = if (jitterPx > 0) ((Math.random() - 0.5) * jitterPx * 2).toFloat() else 0f
        val maxX = (width - 1).toFloat().coerceAtLeast(1f)
        val maxY = (height - 1).toFloat().coerceAtLeast(1f)
        val x = (xPercent * width + jitterX).coerceIn(1f, maxX)
        val y = (yPercent * height + jitterY).coerceIn(1f, maxY)

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            webView.dispatchTouchEvent(down)
        } finally {
            down.recycle()
        }

        webView.postDelayed({
            val upTime = SystemClock.uptimeMillis()
            val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, x, y, 0)
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                webView.dispatchTouchEvent(up)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not release synthesised tap", t)
            } finally {
                up.recycle()
            }
        }, TAP_HOLD_MS)
    }

    fun syncNavigateAllTabs(url: String) {
        webViewMap.values.forEach { webView ->
            webView.post {
                webView.loadUrl(url)
            }
        }
    }

    fun syncReloadAllTabs() {
        webViewMap.values.forEach { webView ->
            webView.post {
                webView.reload()
            }
        }
    }

    fun setAllMuted(muted: Boolean) {
        val script = if (muted) TabXBridge.MUTE_SCRIPT else TabXBridge.UNMUTE_SCRIPT
        webViewMap.values.forEach { webView ->
            webView.post {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    fun setTabMuted(tabId: String, muted: Boolean) {
        val webView = webViewMap[tabId] ?: return
        val script = if (muted) TabXBridge.MUTE_SCRIPT else TabXBridge.UNMUTE_SCRIPT
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    fun getCachedThumbnail(tabId: String): Bitmap? {
        return thumbnailCache[tabId]
    }

    fun captureThumbnail(tabId: String, width: Int = 300, height: Int = 180, onCaptured: (Bitmap?) -> Unit) {
        val webView = webViewMap[tabId]
        if (webView == null || webView.width <= 0 || webView.height <= 0) {
            onCaptured(thumbnailCache[tabId])
            return
        }
        webView.post {
            try {
                val w = webView.width
                val h = webView.height
                if (w > 0 && h > 0) {
                    val fullBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(fullBitmap)
                    webView.draw(canvas)
                    val targetHeight = (height.coerceAtLeast((width * h) / w)).coerceAtMost(400)
                    val scaledBitmap = Bitmap.createScaledBitmap(fullBitmap, width, targetHeight, true)
                    fullBitmap.recycle()
                    thumbnailCache[tabId] = scaledBitmap
                    onCaptured(scaledBitmap)
                } else {
                    onCaptured(thumbnailCache[tabId])
                }
            } catch (e: Exception) {
                onCaptured(thumbnailCache[tabId])
            }
        }
    }

    /** Session profile a tab is running in, or null while its WebView has not been created. */
    fun profileNameForTab(tabId: String): String? = profileNameByTab[tabId]

    /**
     * Logs one account out by clearing only that account's session profile.
     * Returns false when the tab has no isolated session - global cookies are never wiped here,
     * so this can never sign the other accounts out.
     */
    fun clearSessionForTab(tabId: String): Boolean {
        val profileName = profileNameByTab[tabId] ?: return false
        return SessionProfiles.clearSession(profileName)
    }

    /** Logs out every tab that owns an isolated session. Returns how many were cleared. */
    fun clearAllIsolatedSessions(): Int =
        profileNameByTab.values.distinct().count { SessionProfiles.clearSession(it) }

    fun releaseTab(tabId: String) {
        thumbnailCache.remove(tabId)?.recycle()
        profileNameByTab.remove(tabId)
        webViewMap.remove(tabId)?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            removeAllViews()
            destroy()
        }
    }

    fun releaseAll() {
        thumbnailCache.values.forEach { it.recycle() }
        thumbnailCache.clear()
        profileNameByTab.clear()
        webViewMap.forEach { (_, webView) ->
            webView.stopLoading()
            webView.loadUrl("about:blank")
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
        }
        webViewMap.clear()
    }
}
