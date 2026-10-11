package com.example.engine

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.io.File
import java.util.concurrent.Executors

data class CleanOptions(
    val clearCache: Boolean = true,
    val clearCookies: Boolean = true,
    val clearLocalStorage: Boolean = true,
    val clearHistory: Boolean = true,
    val clearFormData: Boolean = true,
    /**
     * When false (default) only the focused account is signed out. When true every account's
     * session is cleared - useful for "reset all 25 windows", but it logs all of them out.
     */
    val allAccounts: Boolean = false
)

data class CleanResult(
    val cacheCleared: Boolean,
    val cookiesCleared: Boolean,
    val storageCleared: Boolean,
    val bytesCleanedEstimate: Long
)

object SessionCleaner {

    /** Deleting and measuring the on-disk cache is slow I/O, so it never runs on the UI thread. */
    private val diskExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "tabx-session-cleaner").apply { isDaemon = true }
    }

    /**
     * Clears the selected web data.
     *
     * Session data (cookies / localStorage / IndexedDB) is cleared per account: each account
     * lives in its own WebView session profile, so clearing cookies no longer signs every other
     * account out. Only when the device's WebView cannot isolate sessions - or when
     * [CleanOptions.allAccounts] is set - does this touch more than the focused account.
     *
     * WebView-bound work stays on the calling (UI) thread; the slow disk work and the completion
     * callback happen on a background thread.
     */
    fun performClean(
        context: Context,
        options: CleanOptions,
        activeWebView: WebView? = null,
        activeProfileName: String? = null,
        allProfileNames: List<String> = emptyList(),
        onComplete: (CleanResult) -> Unit
    ) {
        // WebView calls must happen on the thread that owns the WebView.
        if (options.clearCache) activeWebView?.clearCache(true)
        if (options.clearFormData) activeWebView?.clearFormData()
        if (options.clearHistory) activeWebView?.clearHistory()

        val (cookiesCleared, storageCleared) =
            clearSessionData(options, activeProfileName, allProfileNames)

        diskExecutor.execute {
            var bytesCleaned = 0L
            if (options.clearCache) {
                try {
                    val cacheDir = context.cacheDir
                    bytesCleaned += getFolderSize(cacheDir)
                    deleteDir(cacheDir)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                if (options.clearLocalStorage) {
                    try {
                        val appWebDir = File(context.applicationInfo.dataDir, "app_webview")
                        if (appWebDir.exists()) bytesCleaned += getFolderSize(appWebDir)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            onComplete(
                CleanResult(
                    cacheCleared = options.clearCache,
                    cookiesCleared = cookiesCleared,
                    storageCleared = storageCleared,
                    bytesCleanedEstimate = bytesCleaned
                )
            )
        }
    }

    /**
     * @return cookiesCleared to storageCleared.
     */
    private fun clearSessionData(
        options: CleanOptions,
        activeProfileName: String?,
        allProfileNames: List<String>
    ): Pair<Boolean, Boolean> {
        if (!options.clearCookies && !options.clearLocalStorage) return false to false

        if (SessionProfiles.isSupported()) {
            // Every account owns a profile, so exactly the requested accounts are signed out and
            // the rest stay logged in. Never fall back to the shared jar from here.
            val targets = (if (options.allAccounts) allProfileNames else listOfNotNull(activeProfileName))
                .map { SessionProfiles.normalize(it) }
                .filter { it != SessionProfiles.DEFAULT }
                .distinct()
            val cleared = targets.count {
                SessionProfiles.clearSession(
                    profileName = it,
                    clearCookies = options.clearCookies,
                    clearLocalStorage = options.clearLocalStorage
                )
            }
            return (options.clearCookies && cleared > 0) to (options.clearLocalStorage && cleared > 0)
        }

        // Device WebView cannot isolate sessions, so there is only one shared jar to clear.
        var cookiesCleared = false
        var storageCleared = false
        if (options.clearCookies) {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies { success -> cookieManager.flush() }
            cookieManager.removeSessionCookies(null)
            cookiesCleared = true
        }
        if (options.clearLocalStorage) {
            WebStorage.getInstance().deleteAllData()
            storageCleared = true
        }
        return cookiesCleared to storageCleared
    }

    private fun getFolderSize(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        var size = 0L
        val children = file.listFiles() ?: return file.length()
        for (child in children) {
            size += if (child.isDirectory) getFolderSize(child) else child.length()
        }
        return size
    }

    private fun deleteDir(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list() ?: return false
            for (child in children) {
                val success = deleteDir(File(dir, child))
                if (!success) return false
            }
            return dir.delete()
        } else if (dir != null && dir.isFile) {
            return dir.delete()
        }
        return false
    }
}
