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

    /** Macro recording state, re-applied to every page after a navigation. */
    private var isRecordingActive = false

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

        /** Hold time for a LONG_PRESS macro step. */
        const val LONG_PRESS_MS = 800L

        /** Default travel time and step count for a synthesised swipe. */
        const val SWIPE_DURATION_MS = 350L
        const val SWIPE_STEPS = 10
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
                        // Inject touch listener script for macro recording, then restore the
                        // "recording is on" flag. The flag only lives in the current document,
                        // so recording used to die silently on the first page navigation.
                        view?.evaluateJavascript(TabXBridge.RECORDING_INJECTION_SCRIPT, null)
                        if (isRecordingActive) {
                            view?.evaluateJavascript(TabXBridge.setRecordingActiveScript(true), null)
                        }
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
                            // `context` is the Application context here, so the intent must carry
                            // FLAG_ACTIVITY_NEW_TASK - without it startActivity throws and the
                            // payment / customer-service link silently did nothing.
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, request.url)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                            true
                        } catch (e: Exception) {
                            Log.w(TAG, "No activity can open $requestUrl", e)
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
        isRecordingActive = active
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

    /**
     * Sends one tap into every live window (macro playback with group control).
     */
    fun executeSimulatedClickOnAllTabs(xPercent: Float, yPercent: Float, excludeTabId: String? = null, jitterPx: Int = 0) {
        webViewMap.forEach { (id, webView) ->
            if (excludeTabId == null || id != excludeTabId) {
                webView.post { dispatchNativeTap(webView, xPercent, yPercent, jitterPx) }
            }
        }
    }

    /** Sends a long press into a single window (LONG_PRESS macro step). */
    fun executeSimulatedLongPressOnTab(
        tabId: String,
        xPercent: Float,
        yPercent: Float,
        holdMs: Long = LONG_PRESS_MS,
        jitterPx: Int = 0
    ) {
        val webView = webViewMap[tabId] ?: return
        webView.post { dispatchNativeTap(webView, xPercent, yPercent, jitterPx, holdMs) }
    }

    /** Sends a long press into every live window. */
    fun executeSimulatedLongPressOnAllTabs(
        xPercent: Float,
        yPercent: Float,
        holdMs: Long = LONG_PRESS_MS,
        jitterPx: Int = 0
    ) {
        webViewMap.values.forEach { webView ->
            webView.post { dispatchNativeTap(webView, xPercent, yPercent, jitterPx, holdMs) }
        }
    }

    /** Drags inside a single window, used by the SCROLL_DOWN / SCROLL_UP macro steps. */
    fun executeSimulatedSwipeOnTab(
        tabId: String,
        xPercent: Float,
        yPercent: Float,
        deltaXPercent: Float,
        deltaYPercent: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        val webView = webViewMap[tabId] ?: return
        webView.post {
            dispatchNativeSwipe(webView, xPercent, yPercent, deltaXPercent, deltaYPercent, durationMs)
        }
    }

    /** Drags inside every live window. */
    fun executeSimulatedSwipeOnAllTabs(
        xPercent: Float,
        yPercent: Float,
        deltaXPercent: Float,
        deltaYPercent: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        webViewMap.values.forEach { webView ->
            webView.post {
                dispatchNativeSwipe(webView, xPercent, yPercent, deltaXPercent, deltaYPercent, durationMs)
            }
        }
    }

    /** Reloads one specific window (RELOAD_PAGE macro step without group control). */
    fun reloadTab(tabId: String) {
        val webView = webViewMap[tabId] ?: return
        webView.post { webView.reload() }
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
    private fun dispatchNativeTap(
        webView: WebView,
        xPercent: Float,
        yPercent: Float,
        jitterPx: Int = 0,
        holdMs: Long = TAP_HOLD_MS
    ) {
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
        }, holdMs)
    }

    /**
     * Drags from a relative start point by a relative delta, then releases.
     * Used by the SCROLL_DOWN / SCROLL_UP macro steps. Must run on the UI thread.
     */
    private fun dispatchNativeSwipe(
        webView: WebView,
        xPercent: Float,
        yPercent: Float,
        deltaXPercent: Float,
        deltaYPercent: Float,
        durationMs: Long = SWIPE_DURATION_MS
    ) {
        val width = webView.width
        val height = webView.height
        if (width <= 0 || height <= 0) {
            // Not laid out, no coordinate space for a gesture - scroll the document instead.
            webView.evaluateJavascript(
                TabXBridge.createSimulatedScrollScript(deltaYPercent),
                null
            )
            return
        }

        val maxX = (width - 1).toFloat().coerceAtLeast(1f)
        val maxY = (height - 1).toFloat().coerceAtLeast(1f)
        val startX = (xPercent * width).coerceIn(1f, maxX)
        val startY = (yPercent * height).coerceIn(1f, maxY)
        val endX = ((xPercent + deltaXPercent) * width).coerceIn(1f, maxX)
        val endY = ((yPercent + deltaYPercent) * height).coerceIn(1f, maxY)

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY, 0)
        down.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            webView.dispatchTouchEvent(down)
        } catch (t: Throwable) {
            Log.w(TAG, "Swipe press failed", t)
        } finally {
            down.recycle()
        }

        val stepDelay = (durationMs / SWIPE_STEPS).coerceAtLeast(1L)
        for (step in 1..SWIPE_STEPS) {
            webView.postDelayed({
                val fraction = step.toFloat() / SWIPE_STEPS
                val x = startX + (endX - startX) * fraction
                val y = startY + (endY - startY) * fraction
                val eventTime = SystemClock.uptimeMillis()
                val action = if (step == SWIPE_STEPS) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                val move = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
                move.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    webView.dispatchTouchEvent(move)
                } catch (t: Throwable) {
                    Log.w(TAG, "Swipe step $step failed", t)
                } finally {
                    move.recycle()
                }
            }, stepDelay * step)
        }
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
