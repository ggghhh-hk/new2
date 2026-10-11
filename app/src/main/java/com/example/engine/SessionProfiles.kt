package com.example.engine

import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Per-account session isolation.
 *
 * Before this class existed every tab shared one process-global [CookieManager] and one
 * DOM-storage partition, so logging into a second account overwrote the first one's session
 * (the two windows would fight over the same cookies and the same localStorage keys).
 *
 * WebView multi-profile support fixes that properly: each [Profile] owns its own cookie jar,
 * localStorage, IndexedDB, HTTP cache and service workers, and several profiles can be alive
 * in the same process at the same time - which is exactly what a 25-up grid needs.
 *
 * The feature depends on the WebView APK installed on the device, so every entry point here is
 * guarded: callers must treat a `false` return as "this device cannot isolate sessions" and fall
 * back to the shared default profile rather than pretending isolation happened.
 */
object SessionProfiles {

    private const val TAG = "SessionProfiles"
    private const val PREFIX = "tabx"

    /** Name of the shared, non-isolated profile. */
    val DEFAULT: String = Profile.DEFAULT_PROFILE_NAME

    private val supported: Boolean by lazy {
        val result = try {
            WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not query MULTI_PROFILE support", t)
            false
        }
        Log.i(TAG, "WebView multi-profile session isolation supported = $result")
        result
    }

    /** True when this device's WebView can give every account its own session jar. */
    fun isSupported(): Boolean = supported

    /** Stable profile for a grid slot, so a window keeps its login across app restarts. */
    fun slotProfileName(slot: Int): String = "${PREFIX}_slot_$slot"

    /** Stable profile for an account saved in the 斗罗号库, so it keeps its login. */
    fun accountProfileName(accountId: Long): String = "${PREFIX}_acct_$accountId"

    /** Blank / null profile names mean the shared default profile. */
    fun normalize(name: String?): String = if (name.isNullOrBlank()) DEFAULT else name

    /**
     * Binds [webView] to the profile called [name].
     *
     * Must be called immediately after constructing the WebView and before anything else is done
     * with it (settings, JavaScript interfaces, URL loads) - androidx.webkit throws
     * [IllegalStateException] for every later call. The profile is created on first use.
     *
     * @return true only when the WebView really is isolated in its own profile.
     */
    fun applyTo(webView: WebView, name: String?): Boolean {
        val profileName = normalize(name)
        if (!supported || profileName == DEFAULT) return false
        return try {
            ProfileStore.getInstance().getOrCreateProfile(profileName)
            WebViewCompat.setProfile(webView, profileName)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Could not attach session profile '$profileName'; using shared session", t)
            false
        }
    }

    /**
     * Cookie jar that belongs to [profileName].
     *
     * Resolved from the profile rather than from a WebView so it is safe to call before the
     * WebView has navigated anywhere. Returns null when the profile cannot be isolated.
     */
    fun cookieManagerFor(profileName: String?): CookieManager? {
        val name = normalize(profileName)
        if (!supported || name == DEFAULT) return null
        return try {
            ProfileStore.getInstance().getOrCreateProfile(name).cookieManager
        } catch (t: Throwable) {
            Log.w(TAG, "Could not resolve cookie jar for '$name'", t)
            null
        }
    }

    /**
     * Logs one account out by wiping only that profile's session data.
     *
     * This deliberately does nothing for the shared default profile: wiping that one would log
     * out every account at once, which is the behaviour this class exists to remove.
     *
     * @return true when the profile's session data was cleared.
     */
    fun clearSession(
        profileName: String?,
        clearCookies: Boolean = true,
        clearLocalStorage: Boolean = true
    ): Boolean {
        val name = normalize(profileName)
        if (!supported || name == DEFAULT) return false
        return try {
            val profile = ProfileStore.getInstance().getProfile(name) ?: return false
            if (clearCookies) {
                profile.cookieManager.removeAllCookies(null)
                profile.cookieManager.flush()
            }
            if (clearLocalStorage) {
                profile.webStorage.deleteAllData()
            }
            clearCookies || clearLocalStorage
        } catch (t: Throwable) {
            Log.w(TAG, "Could not clear session for '$name'", t)
            false
        }
    }
}
