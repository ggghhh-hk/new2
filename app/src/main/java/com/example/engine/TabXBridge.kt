package com.example.engine

import android.webkit.JavascriptInterface

class TabXBridge(
    private val tabId: String,
    private val onPageClickRecorded: (tabId: String, xPercent: Float, yPercent: Float) -> Unit
) {
    @JavascriptInterface
    fun recordClick(xPercent: Float, yPercent: Float) {
        onPageClickRecorded(tabId, xPercent, yPercent)
    }

    companion object {
        const val JS_BRIDGE_NAME = "TabXNative"

        // Script to inject for touch recording & listening
        val RECORDING_INJECTION_SCRIPT = """
            (function() {
                if (window.__tabx_recording_installed) return;
                window.__tabx_recording_installed = true;

                var lastRecordedAt = 0;

                function handleTouch(e) {
                    if (!window.__tabx_recording_active) return;
                    var touch = e.touches ? e.touches[0] : e;
                    if (!touch) return;

                    // Chromium fires a compatibility mousedown right after touchstart for the very
                    // same tap, which used to record every tap twice with a bogus delay.
                    var now = Date.now();
                    if (now - lastRecordedAt < 400) return;
                    lastRecordedAt = now;

                    var x = touch.clientX / (window.innerWidth || document.documentElement.clientWidth || 1);
                    var y = touch.clientY / (window.innerHeight || document.documentElement.clientHeight || 1);
                    if (window.$JS_BRIDGE_NAME && window.$JS_BRIDGE_NAME.recordClick) {
                        window.$JS_BRIDGE_NAME.recordClick(x, y);
                    }
                }

                window.addEventListener('touchstart', handleTouch, { passive: true, capture: true });
                window.addEventListener('mousedown', handleTouch, { passive: true, capture: true });
            })();
        """.trimIndent()

        /**
         * Best-effort scroll for windows that are NOT laid out (background tabs), where a
         * synthesised drag has no coordinate space to travel through.
         */
        fun createSimulatedScrollScript(deltaYPercent: Float): String {
            return """
                (function() {
                    var h = window.innerHeight || document.documentElement.clientHeight || 640;
                    try { window.scrollBy(0, $deltaYPercent * h); } catch (e) {}
                })();
            """.trimIndent()
        }

        // Script to set recording active flag
        fun setRecordingActiveScript(active: Boolean): String {
            return "window.__tabx_recording_active = $active;"
        }

        /**
         * Best-effort click for windows that are NOT laid out (background tabs).
         *
         * A window that is not on screen has no coordinate space, so native input cannot reach
         * it; this script is the only option there. Its events are untrusted, so games may
         * ignore them - [WebViewPool] prefers native MotionEvents whenever the window is visible.
         */
        fun createSimulatedClickScript(xPercent: Float, yPercent: Float, randomJitterPx: Int = 0): String {
            return """
                (function() {
                    var w = window.innerWidth || document.documentElement.clientWidth || 360;
                    var h = window.innerHeight || document.documentElement.clientHeight || 640;
                    var jitterX = (Math.random() - 0.5) * $randomJitterPx * 2;
                    var jitterY = (Math.random() - 0.5) * $randomJitterPx * 2;
                    var clientX = Math.max(0, Math.min(w, ($xPercent * w) + jitterX));
                    var clientY = Math.max(0, Math.min(h, ($yPercent * h) + jitterY));

                    var target = document.elementFromPoint(clientX, clientY) || document.body || document.documentElement;

                    try {
                        var touchObj = new Touch({
                            identifier: Date.now(),
                            target: target,
                            clientX: clientX,
                            clientY: clientY,
                            screenX: clientX,
                            screenY: clientY,
                            pageX: clientX,
                            pageY: clientY,
                            radiusX: 2.5,
                            radiusY: 2.5,
                            rotationAngle: 0,
                            force: 1.0
                        });

                        target.dispatchEvent(new TouchEvent('touchstart', {
                            cancelable: true,
                            bubbles: true,
                            touches: [touchObj],
                            targetTouches: [touchObj],
                            changedTouches: [touchObj]
                        }));

                        setTimeout(function() {
                            target.dispatchEvent(new TouchEvent('touchend', {
                                cancelable: true,
                                bubbles: true,
                                touches: [],
                                targetTouches: [],
                                changedTouches: [touchObj]
                            }));

                            var mouseOpts = { bubbles: true, cancelable: true, view: window, clientX: clientX, clientY: clientY };
                            target.dispatchEvent(new MouseEvent('mousedown', mouseOpts));
                            target.dispatchEvent(new MouseEvent('mouseup', mouseOpts));
                            target.dispatchEvent(new MouseEvent('click', mouseOpts));
                        }, 50);
                    } catch (e) {
                        var evt = document.createEvent('MouseEvents');
                        evt.initMouseEvent('click', true, true, window, 1, clientX, clientY, clientX, clientY, false, false, false, false, 0, null);
                        target.dispatchEvent(evt);
                    }
                })();
            """.trimIndent()
        }

        // Script to mute all HTML5 audio/video in game H5 canvas
        const val MUTE_SCRIPT = """
            (function() {
                try {
                    var els = document.querySelectorAll('audio, video');
                    for (var i = 0; i < els.length; i++) {
                        els[i].muted = true;
                        els[i].volume = 0;
                    }
                } catch(e) {}
            })();
        """

        const val UNMUTE_SCRIPT = """
            (function() {
                try {
                    var els = document.querySelectorAll('audio, video');
                    for (var i = 0; i < els.length; i++) {
                        els[i].muted = false;
                        els[i].volume = 1;
                    }
                } catch(e) {}
            })();
        """
    }
}
