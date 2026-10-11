package com.example.data.model

import java.util.UUID

data class TabItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "新标签页",
    val url: String = "https://m.gm99.com/dldl",
    val originalUrl: String = "https://m.gm99.com/dldl",
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val customUserAgent: String? = null,
    val isSyncMaster: Boolean = false,
    val isSyncEnabled: Boolean = true,
    val isMuted: Boolean = false,
    val accountTag: String? = null,
    /**
     * Name of the isolated WebView session profile this tab runs in.
     * Each profile has its own cookie jar, localStorage and IndexedDB, which is what keeps
     * multiple accounts logged in at the same time. Blank or "Default" means the shared profile.
     */
    val profileName: String = "",
    val zoomLevel: Float = 1.0f,
    val lastActiveTime: Long = System.currentTimeMillis()
)

enum class ViewLayoutMode(val label: String, val maxVisible: Int, val columns: Int) {
    SINGLE("单页全屏 (1开)", 1, 1),
    SPLIT_VERTICAL("双屏上下 (2开)", 2, 1),
    SPLIT_HORIZONTAL("双屏左右 (2开)", 2, 2),
    GRID_4("四屏矩阵 (4开 2x2)", 4, 2),
    GRID_9("九屏矩阵 (9开 3x3)", 9, 3),
    GRID_16("十六屏矩阵 (16开 4x4)", 16, 4),
    GRID_25("二十五屏千军矩阵 (25开 5x5)", 25, 5),
    GRID_36("三十六屏阵列 (36开 6x6)", 36, 6),
    MATRIX_ALL("百开矩阵监视墙 (全部同屏)", 100, 5)
}

enum class UserAgentPreset(val displayName: String, val uaString: String) {
    DEFAULT("Android Chrome (移动端默认)", ""),
    DESKTOP_CHROME(
        "PC 桌面 Chrome (大屏网页)",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    ),
    IPHONE_SAFARI(
        "iPhone Safari (iOS体验)",
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    ),
    IPAD_SAFARI(
        "iPad Pro (平板端视图)",
        "Mozilla/5.0 (iPad; CPU OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
    ),
    GM99_SPECIAL(
        "GM99 专用游戏优化UA",
        "Mozilla/5.0 (Linux; Android 14; Mobile; GM99GameClient/3.8.0) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    ),
    CUSTOM("自定义 UA", "")
}
