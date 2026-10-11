package com.example.engine

import com.example.data.model.ActionType
import com.example.data.model.MacroAction
import com.example.data.model.MacroScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

data class MacroState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val scriptName: String = "",
    val currentLoop: Int = 0,
    val maxLoops: Int = 0, // 0 = infinite
    val currentStepIndex: Int = 0,
    val totalSteps: Int = 0,
    val currentActionLabel: String = "",
    val speedMultiplier: Float = 1.0f,
    val syncToAllTabs: Boolean = false
)

class MacroExecutionEngine(
    private val webViewPool: WebViewPool,
    private val coroutineScope: CoroutineScope
) {
    private var executionJob: Job? = null
    private val _macroState = MutableStateFlow(MacroState())
    val macroState: StateFlow<MacroState> = _macroState.asStateFlow()

    fun startScript(
        script: MacroScript,
        actions: List<MacroAction>,
        targetTabId: String,
        syncToAllTabs: Boolean = false,
        overrideSpeed: Float? = null
    ) {
        stopScript()
        if (actions.isEmpty()) return

        val startSpeed = (overrideSpeed ?: script.speedMultiplier).coerceAtLeast(0.1f)
        val maxLoops = script.repeatCount
        val jitter = script.randomJitterPx
        // A non-zero jitter means the "anti-ban" profile is on, which also randomises the timing.
        val antiBanTiming = jitter > 0

        _macroState.value = MacroState(
            isRunning = true,
            isPaused = false,
            scriptName = script.name,
            currentLoop = 1,
            maxLoops = maxLoops,
            currentStepIndex = 0,
            totalSteps = actions.size,
            currentActionLabel = "准备开始",
            speedMultiplier = startSpeed,
            syncToAllTabs = syncToAllTabs
        )

        executionJob = coroutineScope.launch(Dispatchers.Default) {
            var loopCount = 0
            while (_macroState.value.isRunning && (maxLoops == 0 || loopCount < maxLoops)) {
                loopCount++
                _macroState.value = _macroState.value.copy(currentLoop = loopCount)

                for (index in actions.indices) {
                    if (!_macroState.value.isRunning) break

                    // Handle paused state
                    while (_macroState.value.isPaused && _macroState.value.isRunning) {
                        delay(200)
                    }

                    // Read the live values on every step: the HUD speed chips and the sync toggle
                    // used to be captured once at start, so changing them mid-run did nothing.
                    val speed = _macroState.value.speedMultiplier.coerceAtLeast(0.1f)
                    val syncNow = _macroState.value.syncToAllTabs

                    val action = actions[index]
                    _macroState.value = _macroState.value.copy(
                        currentStepIndex = index + 1,
                        currentActionLabel = action.label.ifBlank { "步骤 ${index + 1}: ${action.type.name}" }
                    )

                    when (action.type) {
                        ActionType.CLICK -> {
                            if (syncNow) {
                                webViewPool.executeSimulatedClickOnAllTabs(
                                    action.xPercent, action.yPercent, jitterPx = jitter
                                )
                            } else {
                                webViewPool.executeSimulatedClickOnTab(
                                    targetTabId, action.xPercent, action.yPercent, jitterPx = jitter
                                )
                            }
                        }
                        ActionType.DOUBLE_CLICK -> {
                            if (syncNow) {
                                webViewPool.executeSimulatedClickOnAllTabs(action.xPercent, action.yPercent, jitterPx = jitter)
                                delay(120)
                                webViewPool.executeSimulatedClickOnAllTabs(action.xPercent, action.yPercent, jitterPx = jitter)
                            } else {
                                webViewPool.executeSimulatedClickOnTab(targetTabId, action.xPercent, action.yPercent, jitterPx = jitter)
                                delay(120)
                                webViewPool.executeSimulatedClickOnTab(targetTabId, action.xPercent, action.yPercent, jitterPx = jitter)
                            }
                        }
                        ActionType.LONG_PRESS -> {
                            // A real hold, not the ordinary 60ms tap this used to send.
                            if (syncNow) {
                                webViewPool.executeSimulatedLongPressOnAllTabs(
                                    action.xPercent, action.yPercent, jitterPx = jitter
                                )
                            } else {
                                webViewPool.executeSimulatedLongPressOnTab(
                                    targetTabId, action.xPercent, action.yPercent, jitterPx = jitter
                                )
                            }
                        }
                        ActionType.SCROLL_DOWN -> {
                            // Content moves down = the finger travels up.
                            if (syncNow) {
                                webViewPool.executeSimulatedSwipeOnAllTabs(action.xPercent, 0.7f, 0f, -0.4f)
                            } else {
                                webViewPool.executeSimulatedSwipeOnTab(targetTabId, action.xPercent, 0.7f, 0f, -0.4f)
                            }
                        }
                        ActionType.SCROLL_UP -> {
                            if (syncNow) {
                                webViewPool.executeSimulatedSwipeOnAllTabs(action.xPercent, 0.3f, 0f, 0.4f)
                            } else {
                                webViewPool.executeSimulatedSwipeOnTab(targetTabId, action.xPercent, 0.3f, 0f, 0.4f)
                            }
                        }
                        ActionType.DELAY -> {
                            // Delay only
                        }
                        ActionType.RELOAD_PAGE -> {
                            // Reload the page - the non-sync branch used to tap the screen centre.
                            if (syncNow) {
                                webViewPool.syncReloadAllTabs()
                            } else {
                                webViewPool.reloadTab(targetTabId)
                            }
                        }
                    }

                    // Honour the speed multiplier, and add a little timing noise in anti-ban mode so
                    // the loop does not run like a metronome (the README promised this; it was absent).
                    val timeJitter = if (antiBanTiming) Random.nextLong(0L, TIME_JITTER_MS) else 0L
                    val delayMs = ((action.delayAfterMs.coerceAtLeast(50L) + timeJitter) / speed).toLong()
                    delay(delayMs.coerceAtLeast(10L))
                }

                // Interval between loops
                if (script.intervalBetweenLoopsMs > 0 && _macroState.value.isRunning) {
                    val loopSpeed = _macroState.value.speedMultiplier.coerceAtLeast(0.1f)
                    val loopJitter = if (antiBanTiming) Random.nextLong(0L, TIME_JITTER_MS) else 0L
                    val loopDelay = ((script.intervalBetweenLoopsMs + loopJitter) / loopSpeed).toLong()
                    delay(loopDelay.coerceAtLeast(10L))
                }
            }

            _macroState.value = _macroState.value.copy(
                isRunning = false,
                isPaused = false,
                currentActionLabel = "执行完毕"
            )
        }
    }

    fun pauseScript() {
        if (_macroState.value.isRunning) {
            _macroState.value = _macroState.value.copy(isPaused = true)
        }
    }

    fun resumeScript() {
        if (_macroState.value.isRunning) {
            _macroState.value = _macroState.value.copy(isPaused = false)
        }
    }

    fun stopScript() {
        executionJob?.cancel()
        executionJob = null
        _macroState.value = _macroState.value.copy(
            isRunning = false,
            isPaused = false,
            currentActionLabel = "已停止"
        )
    }

    /** Takes effect immediately, including while a script is running. */
    fun setSpeed(multiplier: Float) {
        _macroState.value = _macroState.value.copy(speedMultiplier = multiplier.coerceAtLeast(0.1f))
    }

    /** Takes effect immediately, including while a script is running. */
    fun setSyncMode(sync: Boolean) {
        _macroState.value = _macroState.value.copy(syncToAllTabs = sync)
    }

    private companion object {
        /** Upper bound of the random extra delay added per step in anti-ban mode. */
        const val TIME_JITTER_MS = 120L
    }
}
