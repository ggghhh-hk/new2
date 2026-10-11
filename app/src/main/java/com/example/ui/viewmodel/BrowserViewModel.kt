package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.AccountProfile
import com.example.data.model.ActionType
import com.example.data.model.BrowserBookmark
import com.example.data.model.MacroAction
import com.example.data.model.MacroHelper
import com.example.data.model.MacroScript
import com.example.data.model.TabItem
import com.example.data.model.UserAgentPreset
import com.example.data.model.ViewLayoutMode
import com.example.engine.CleanOptions
import com.example.engine.CleanResult
import com.example.engine.MacroExecutionEngine
import com.example.engine.MacroState
import com.example.engine.SessionCleaner
import com.example.engine.SessionProfiles
import com.example.engine.WebViewPool
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val macroDao = db.macroDao()
    private val accountDao = db.accountDao()
    private val bookmarkDao = db.bookmarkDao()

    val savedScripts: StateFlow<List<MacroScript>> = macroDao.getAllScripts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val savedAccounts: StateFlow<List<AccountProfile>> = accountDao.getAllAccounts()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val savedBookmarks: StateFlow<List<BrowserBookmark>> = bookmarkDao.getAllBookmarks()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    // Tabs Management
    /**
     * Every window runs in its own persistent WebView session profile, which is what lets
     * several accounts stay logged in at the same time instead of kicking each other out.
     * Slot 1 belongs to the tab the app opens with; later windows take the next slot, so two
     * windows can never end up sharing one cookie jar.
     */
    private val sessionSlotCounter = AtomicInteger(2)

    private fun nextSessionProfileName(): String =
        SessionProfiles.slotProfileName(sessionSlotCounter.getAndIncrement())

    private val defaultTab = TabItem(
        id = UUID.randomUUID().toString(),
        title = "斗罗大陆H5 (GM99)",
        url = "https://m.gm99.com/dldl",
        originalUrl = "https://m.gm99.com/dldl",
        isSyncMaster = true,
        accountTag = "主号",
        profileName = SessionProfiles.slotProfileName(1)
    )

    private val _tabs = MutableStateFlow<List<TabItem>>(listOf(defaultTab))
    val tabs: StateFlow<List<TabItem>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow(defaultTab.id)
    val activeTabId: StateFlow<String> = _activeTabId.asStateFlow()

    // Secondary tabs for Multi-window / Grid view
    private val _gridTabIds = MutableStateFlow<List<String>>(listOf(defaultTab.id))
    val gridTabIds: StateFlow<List<String>> = _gridTabIds.asStateFlow()

    // Layout mode
    private val _layoutMode = MutableStateFlow(ViewLayoutMode.SINGLE)
    val layoutMode: StateFlow<ViewLayoutMode> = _layoutMode.asStateFlow()

    // Global Sync Mode
    private val _isGlobalSync = MutableStateFlow(false)
    val isGlobalSync: StateFlow<Boolean> = _isGlobalSync.asStateFlow()

    // Global Audio Mute (essential for 25+ accounts)
    private val _isAllMuted = MutableStateFlow(false)
    val isAllMuted: StateFlow<Boolean> = _isAllMuted.asStateFlow()

    // Keep Screen On
    private val _keepScreenOn = MutableStateFlow(true)
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    // Recording State
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedActions = MutableStateFlow<List<MacroAction>>(emptyList())
    val recordedActions: StateFlow<List<MacroAction>> = _recordedActions.asStateFlow()

    private var lastRecordedClickTime = 0L

    // UI Dialogs
    private val _showScriptDialog = MutableStateFlow(false)
    val showScriptDialog: StateFlow<Boolean> = _showScriptDialog.asStateFlow()

    private val _showAccountDialog = MutableStateFlow(false)
    val showAccountDialog: StateFlow<Boolean> = _showAccountDialog.asStateFlow()

    private val _showUaDialog = MutableStateFlow(false)
    val showUaDialog: StateFlow<Boolean> = _showUaDialog.asStateFlow()

    private val _showCleanDialog = MutableStateFlow(false)
    val showCleanDialog: StateFlow<Boolean> = _showCleanDialog.asStateFlow()

    private val _showSessionSwitcher = MutableStateFlow(false)
    val showSessionSwitcher: StateFlow<Boolean> = _showSessionSwitcher.asStateFlow()

    private val _cleanNotification = MutableStateFlow<String?>(null)
    val cleanNotification: StateFlow<String?> = _cleanNotification.asStateFlow()

    // WebView Engine & Macro Engine
    lateinit var webViewPool: WebViewPool
    lateinit var macroEngine: MacroExecutionEngine

    val macroState: StateFlow<MacroState> get() = macroEngine.macroState

    init {
        initWebViewEngine(application)
        initPresetData()
    }

    private fun initWebViewEngine(context: Context) {
        webViewPool = WebViewPool(
            context = context,
            onPageTitleChanged = { tabId, title ->
                updateTab(tabId) { it.copy(title = title) }
            },
            onPageUrlChanged = { tabId, url, canGoBack, canGoForward ->
                updateTab(tabId) { it.copy(url = url, canGoBack = canGoBack, canGoForward = canGoForward) }
            },
            onLoadingProgressChanged = { tabId, progress, isLoading ->
                updateTab(tabId) { it.copy(progress = progress, isLoading = isLoading) }
            },
            onRecordClickReceived = { tabId, xPercent, yPercent ->
                handleRecordedClick(tabId, xPercent, yPercent)
            }
        )

        macroEngine = MacroExecutionEngine(webViewPool, viewModelScope)
    }

    private fun initPresetData() {
        viewModelScope.launch {
            // Presets are seeded exactly once, on a genuinely empty database. This used to run
            // unconditionally on every process start, so every launch appended another copy of
            // each preset bookmark/account/script.
            // Seed preset bookmarks if empty
            val bookmarks = listOf(
                BrowserBookmark(title = "GM99 斗罗大陆H5 官方入口", url = "https://m.gm99.com/dldl", isGamePreset = true),
                BrowserBookmark(title = "GM99 游戏中心大厅", url = "https://www.gm99.com", isGamePreset = true),
                BrowserBookmark(title = "斗罗大陆H5 论坛与攻略", url = "https://m.gm99.com", isGamePreset = true),
                BrowserBookmark(title = "百度搜索", url = "https://www.baidu.com", isGamePreset = false)
            )
            if (bookmarkDao.countBookmarks() == 0) {
                bookmarks.forEach { bookmarkDao.insertBookmark(it) }
            }

            // Seed preset accounts if empty
            val accounts = listOf(
                AccountProfile(
                    accountName = "大号-海神唐三",
                    serverName = "斗罗1服",
                    username = "soul_master_01",
                    notes = "主力输出号，已满级神环",
                    colorTagHex = "#3B82F6"
                ),
                AccountProfile(
                    accountName = "小号1-昊天锤挂机",
                    serverName = "斗罗1服",
                    username = "soul_sub_02",
                    notes = "打材料/魂兽日常小号",
                    colorTagHex = "#10B981"
                ),
                AccountProfile(
                    accountName = "小号2-九宝琉璃塔",
                    serverName = "斗罗2服",
                    username = "soul_sub_03",
                    notes = "宗门任务辅助号",
                    colorTagHex = "#F59E0B"
                )
            )
            if (accountDao.countAccounts() == 0) {
                accounts.forEach { accountDao.insertAccount(it) }
            }

            // Seed preset scripts
            if (macroDao.countScripts() == 0) {
                MacroHelper.getPresetScripts().forEach {
                    macroDao.insertScript(it.copy(id = 0))
                }
            }
        }
    }

    private fun updateTab(tabId: String, transform: (TabItem) -> TabItem) {
        _tabs.value = _tabs.value.map {
            if (it.id == tabId) transform(it) else it
        }
    }

    fun getActiveTab(): TabItem? {
        return _tabs.value.find { it.id == _activeTabId.value } ?: _tabs.value.firstOrNull()
    }

    fun selectTab(tabId: String) {
        _activeTabId.value = tabId
        updateGridTabs()
    }

    fun addNewTab(
        url: String = "https://m.gm99.com/dldl",
        accountTag: String? = null,
        title: String = "GM99 斗罗H5",
        profileName: String? = null
    ) {
        val count = _tabs.value.size + 1
        val newTab = TabItem(
            id = UUID.randomUUID().toString(),
            title = if (accountTag != null) "$title ($accountTag)" else "标签页 $count",
            url = url,
            originalUrl = url,
            accountTag = accountTag ?: "小号$count",
            profileName = profileName ?: nextSessionProfileName()
        )
        _tabs.value = _tabs.value + newTab
        _activeTabId.value = newTab.id
        updateGridTabs()
    }

    fun closeTab(tabId: String) {
        if (_tabs.value.size <= 1) return // Keep at least 1 tab
        webViewPool.releaseTab(tabId)
        val currentList = _tabs.value.filter { it.id != tabId }
        _tabs.value = currentList
        if (_activeTabId.value == tabId) {
            _activeTabId.value = currentList.last().id
        }
        updateGridTabs()
    }

    fun setLayoutMode(mode: ViewLayoutMode) {
        _layoutMode.value = mode
        updateGridTabs()
    }

    private fun updateGridTabs() {
        val allTabs = _tabs.value
        val active = getActiveTab() ?: return
        val others = allTabs.filter { it.id != active.id }
        val needed = when (_layoutMode.value) {
            ViewLayoutMode.SINGLE -> 1
            ViewLayoutMode.SPLIT_VERTICAL, ViewLayoutMode.SPLIT_HORIZONTAL -> 2
            ViewLayoutMode.GRID_4 -> 4
            ViewLayoutMode.GRID_9 -> 9
            ViewLayoutMode.GRID_16 -> 16
            ViewLayoutMode.GRID_25 -> 25
            ViewLayoutMode.GRID_36 -> 36
            ViewLayoutMode.MATRIX_ALL -> allTabs.size.coerceAtLeast(1)
        }
        val selected = mutableListOf(active.id)
        for (other in others) {
            if (selected.size < needed) {
                selected.add(other.id)
            }
        }
        _gridTabIds.value = selected
    }

    // Launch Batch Accounts (25, 36, or custom number)
    fun launchBatchAccounts(
        targetCount: Int = 25,
        url: String = "https://m.gm99.com/dldl",
        baseName: String = "斗罗号"
    ) {
        val currentTabs = _tabs.value.toMutableList()
        val neededNew = targetCount - currentTabs.size

        if (neededNew > 0) {
            val generated = (1..neededNew).map { idx ->
                val totalNum = currentTabs.size + idx
                val padNum = String.format("%02d", totalNum)
                TabItem(
                    id = UUID.randomUUID().toString(),
                    title = "斗罗H5-$padNum",
                    url = url,
                    originalUrl = url,
                    accountTag = "小号-$padNum",
                    profileName = nextSessionProfileName()
                )
            }
            currentTabs.addAll(generated)
        }

        _tabs.value = currentTabs.take(targetCount.coerceAtLeast(currentTabs.size))
        _activeTabId.value = _tabs.value.first().id

        // Auto-switch to matching grid layout
        val targetMode = when {
            targetCount <= 4 -> ViewLayoutMode.GRID_4
            targetCount <= 9 -> ViewLayoutMode.GRID_9
            targetCount <= 16 -> ViewLayoutMode.GRID_16
            targetCount <= 25 -> ViewLayoutMode.GRID_25
            targetCount <= 36 -> ViewLayoutMode.GRID_36
            else -> ViewLayoutMode.MATRIX_ALL
        }
        _layoutMode.value = targetMode
        updateGridTabs()
    }

    fun launch25AccountsMatrix() {
        launchBatchAccounts(targetCount = 25)
    }

    fun launch36AccountsMatrix() {
        launchBatchAccounts(targetCount = 36)
    }

    fun toggleAllMute() {
        val newMuted = !_isAllMuted.value
        _isAllMuted.value = newMuted
        webViewPool.setAllMuted(newMuted)
    }

    fun batchReloadAll() {
        webViewPool.syncReloadAllTabs()
    }

    /**
     * Turns 同步群控 on/off. The pool then replays every touch from the window the user touches
     * into all the other windows as native input events.
     */
    fun toggleGlobalSync() {
        val enabled = !_isGlobalSync.value
        _isGlobalSync.value = enabled
        webViewPool.globalSyncEnabled = enabled
    }

    fun toggleKeepScreenOn() {
        _keepScreenOn.value = !_keepScreenOn.value
    }

    // Navigation Commands
    fun loadUrl(url: String) {
        var validUrl = url.trim()
        if (!validUrl.startsWith("http://") && !validUrl.startsWith("https://")) {
            validUrl = if (validUrl.contains(".")) "https://$validUrl" else "https://www.baidu.com/s?wd=$validUrl"
        }
        val active = getActiveTab() ?: return
        updateTab(active.id) { it.copy(url = validUrl) }
        val webView = webViewPool.getOrCreateWebView(active)
        webView.loadUrl(validUrl)

        if (_isGlobalSync.value) {
            webViewPool.syncNavigateAllTabs(validUrl)
        }
    }

    fun goBack() {
        val active = getActiveTab() ?: return
        val webView = webViewPool.getOrCreateWebView(active)
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    fun goForward() {
        val active = getActiveTab() ?: return
        val webView = webViewPool.getOrCreateWebView(active)
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    fun reload() {
        if (_isGlobalSync.value) {
            webViewPool.syncReloadAllTabs()
        } else {
            val active = getActiveTab() ?: return
            val webView = webViewPool.getOrCreateWebView(active)
            webView.reload()
        }
    }

    // User-Agent Management
    fun setTabUserAgent(tabId: String, preset: UserAgentPreset, customUa: String = "") {
        val uaString = if (preset == UserAgentPreset.CUSTOM) customUa else preset.uaString
        updateTab(tabId) { it.copy(customUserAgent = uaString) }
        webViewPool.updateTabUserAgent(tabId, uaString)
    }

    // Cache & Data Cleaning
    fun performClean(options: CleanOptions) {
        val activeTab = getActiveTab()
        val webView = activeTab?.let { webViewPool.getOrCreateWebView(it) }
        SessionCleaner.performClean(
            context = getApplication(),
            options = options,
            activeWebView = webView,
            // Sign out only the account the user is looking at, unless they asked for all of them.
            activeProfileName = activeTab?.let {
                webViewPool.profileNameForTab(it.id) ?: it.profileName
            },
            allProfileNames = _tabs.value.map { it.profileName }
        ) { result ->
            val sizeKb = result.bytesCleanedEstimate / 1024
            val msg = buildString {
                append("清理完成: ")
                if (result.cacheCleared) append("缓存数据 ")
                if (result.cookiesCleared) {
                    append(if (options.allAccounts) "全部账号会话 " else "当前账号会话 ")
                }
                if (result.storageCleared) append("LocalStorage储存 ")
                if (sizeKb > 0) append("($sizeKb KB)")
            }
            _cleanNotification.value = msg
        }
    }

    fun dismissCleanNotification() {
        _cleanNotification.value = null
    }

    // Macro Recording
    fun startRecording() {
        _recordedActions.value = emptyList()
        _isRecording.value = true
        lastRecordedClickTime = System.currentTimeMillis()
        webViewPool.applyRecordingState(true)
    }

    fun stopRecording() {
        _isRecording.value = false
        webViewPool.applyRecordingState(false)
    }

    fun addManualStep(action: MacroAction) {
        _recordedActions.value = _recordedActions.value + action
    }

    private fun handleRecordedClick(tabId: String, xPercent: Float, yPercent: Float) {
        if (!_isRecording.value) return
        val now = System.currentTimeMillis()
        val delay = if (lastRecordedClickTime > 0) {
            // Keep the recorded rhythm: the old 400ms floor stretched every quick tap into a
            // 400ms wait, so a fast recorded combo played back far slower than it was recorded.
            (now - lastRecordedClickTime).coerceIn(80L, 30000L)
        } else {
            1000L
        }
        lastRecordedClickTime = now

        val stepNumber = _recordedActions.value.size + 1
        val newAction = MacroAction(
            type = ActionType.CLICK,
            xPercent = xPercent,
            yPercent = yPercent,
            delayAfterMs = delay,
            label = "点击位置 ($stepNumber)"
        )
        _recordedActions.value = _recordedActions.value + newAction
    }

    fun saveRecordedScript(name: String, description: String, repeatCount: Int, speedMultiplier: Float) {
        val actions = _recordedActions.value
        if (actions.isEmpty()) return
        val script = MacroScript(
            name = name,
            description = description,
            category = "自定义脚本",
            actionsJson = MacroHelper.toJson(actions),
            repeatCount = repeatCount,
            speedMultiplier = speedMultiplier,
            randomJitterPx = 6
        )
        viewModelScope.launch {
            macroDao.insertScript(script)
            _recordedActions.value = emptyList()
            _isRecording.value = false
        }
    }

    // Macro Playback
    fun executeScript(script: MacroScript, syncToAll: Boolean = false) {
        val actions = MacroHelper.parseActions(script.actionsJson)
        val active = getActiveTab() ?: return
        macroEngine.startScript(
            script = script,
            actions = actions,
            targetTabId = active.id,
            syncToAllTabs = syncToAll || _isGlobalSync.value
        )
    }

    fun pauseScript() = macroEngine.pauseScript()
    fun resumeScript() = macroEngine.resumeScript()
    fun stopScript() = macroEngine.stopScript()
    fun setMacroSpeed(speed: Float) = macroEngine.setSpeed(speed)

    // Script DB operations
    fun deleteScript(id: Long) {
        viewModelScope.launch {
            macroDao.deleteById(id)
        }
    }

    // Account DB operations
    fun saveAccount(account: AccountProfile) {
        viewModelScope.launch {
            if (account.id == 0L) {
                accountDao.insertAccount(account)
            } else {
                accountDao.updateAccount(account)
            }
        }
    }

    fun deleteAccount(id: Long) {
        viewModelScope.launch {
            accountDao.deleteById(id)
        }
    }

    fun launchAccountInNewTab(account: AccountProfile) {
        val url = account.customUrl.ifBlank { "https://m.gm99.com/dldl" }
        addNewTab(
            url = url,
            accountTag = account.accountName,
            title = "斗罗H5-${account.serverName}",
            // One stable profile per saved account, so this account keeps its own login.
            profileName = SessionProfiles.accountProfileName(account.id)
        )
    }

    fun toggleTabMute(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val newMuted = !tab.isMuted
        updateTab(tabId) { it.copy(isMuted = newMuted) }
        webViewPool.setTabMuted(tabId, newMuted)
    }

    /**
     * False on devices whose WebView cannot hold separate session profiles. In that case every
     * account falls back to one shared cookie jar and will kick the others out, so the UI should
     * warn the user instead of promising isolated multi-account.
     */
    val isSessionIsolationSupported: Boolean get() = webViewPool.isSessionIsolationSupported

    /** Session profile the focused window is using, for display/debugging. */
    fun activeProfileName(): String? = webViewPool.profileNameForTab(_activeTabId.value)

    /** Logs out only the focused account; the other windows stay signed in. */
    fun clearActiveTabSession(): Boolean = webViewPool.clearSessionForTab(_activeTabId.value)

    /** Logs out every account that owns an isolated session. */
    fun clearAllTabSessions(): Int = webViewPool.clearAllIsolatedSessions()

    fun captureVisibleThumbnails() {
        _tabs.value.forEach { tab ->
            webViewPool.captureThumbnail(tab.id) { _ -> }
        }
    }

    // Dialog Toggles
    fun setShowScriptDialog(show: Boolean) { _showScriptDialog.value = show }
    fun setShowAccountDialog(show: Boolean) { _showAccountDialog.value = show }
    fun setShowUaDialog(show: Boolean) { _showUaDialog.value = show }
    fun setShowCleanDialog(show: Boolean) { _showCleanDialog.value = show }
    fun setShowSessionSwitcher(show: Boolean) {
        if (show) {
            captureVisibleThumbnails()
        }
        _showSessionSwitcher.value = show
    }
    fun toggleSessionSwitcher() {
        val next = !_showSessionSwitcher.value
        setShowSessionSwitcher(next)
    }

    override fun onCleared() {
        super.onCleared()
        macroEngine.stopScript()
        webViewPool.releaseAll()
    }
}
