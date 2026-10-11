package com.example.engine

import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView

/**
 * A WebView that reports every touch it receives.
 *
 * 同步群控 needs the raw touch stream so it can replay the gesture into the other accounts as
 * *native* input. That is the only approach that reliably works on H5/canvas games: events
 * synthesised in JavaScript are untrusted (`isTrusted === false`), do not cross iframe
 * boundaries, and land on whatever element happens to be on top at that coordinate, so games
 * routinely ignore them. A replayed [MotionEvent] enters the normal input pipeline and is
 * indistinguishable from a real finger.
 */
class SyncableWebView(context: Context) : WebView(context) {

    /** Called for every touch event, on the UI thread, before the WebView handles it. */
    var touchObserver: ((MotionEvent) -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchObserver?.invoke(ev)
        return super.dispatchTouchEvent(ev)
    }
}
