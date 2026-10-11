package com.example.data.model

import org.json.JSONArray
import org.json.JSONObject

object MacroHelper {
    fun parseActions(json: String): List<MacroAction> {
        val list = mutableListOf<MacroAction>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val typeStr = obj.optString("type", ActionType.CLICK.name)
                val type = try {
                    ActionType.valueOf(typeStr)
                } catch (e: Exception) {
                    ActionType.CLICK
                }
                list.add(
                    MacroAction(
                        type = type,
                        xPercent = obj.optDouble("xPercent", 0.5).toFloat(),
                        yPercent = obj.optDouble("yPercent", 0.5).toFloat(),
                        delayAfterMs = obj.optLong("delayAfterMs", 1000L),
                        textParam = obj.optString("textParam", ""),
                        label = obj.optString("label", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun toJson(actions: List<MacroAction>): String {
        val array = JSONArray()
        for (action in actions) {
            val obj = JSONObject()
            obj.put("type", action.type.name)
            obj.put("xPercent", action.xPercent)
            obj.put("yPercent", action.yPercent)
            obj.put("delayAfterMs", action.delayAfterMs)
            obj.put("textParam", action.textParam)
            obj.put("label", action.label)
            array.put(obj)
        }
        return array.toString()
    }

    fun getPresetScripts(): List<MacroScript> {
        return listOf(
            MacroScript(
                id = -1,
                name = "斗罗大陆H5: 每日日常&主线自动循环",
                description = "针对GM99斗罗大陆H5界面，自动点击右下角主线任务、挑战关卡、领取挂机经验与完成每日历练",
                category = "GM99斗罗H5",
                actionsJson = toJson(
                    listOf(
                        MacroAction(ActionType.CLICK, 0.85f, 0.78f, 1500L, label = "点击主线挑战/副本"),
                        MacroAction(ActionType.CLICK, 0.50f, 0.88f, 2000L, label = "确认进入战斗"),
                        MacroAction(ActionType.DELAY, 0.50f, 0.50f, 4000L, label = "等待战斗结算"),
                        MacroAction(ActionType.CLICK, 0.50f, 0.75f, 1200L, label = "领取胜利通关奖励"),
                        MacroAction(ActionType.CLICK, 0.92f, 0.22f, 1500L, label = "点击日常历练红点")
                    )
                ),
                repeatCount = 0, // Infinite
                speedMultiplier = 1.0f,
                randomJitterPx = 8,
                intervalBetweenLoopsMs = 2500L
            ),
            MacroScript(
                id = -2,
                name = "斗罗大陆H5: 魂兽森林/猎杀魂环极速刷怪",
                description = "自动快速连点魂兽刷新点与猎杀魂环按钮，包含随机微小坐标防检测偏移",
                category = "GM99斗罗H5",
                actionsJson = toJson(
                    listOf(
                        MacroAction(ActionType.CLICK, 0.50f, 0.55f, 600L, label = "点击目标魂兽"),
                        MacroAction(ActionType.CLICK, 0.75f, 0.82f, 800L, label = "点击立即猎杀"),
                        MacroAction(ActionType.CLICK, 0.50f, 0.70f, 1200L, label = "拾取掉落魂环/宝箱"),
                        MacroAction(ActionType.CLICK, 0.20f, 0.92f, 700L, label = "重置搜索下一只魂兽")
                    )
                ),
                repeatCount = 50,
                speedMultiplier = 1.2f,
                randomJitterPx = 6,
                intervalBetweenLoopsMs = 1500L
            ),
            MacroScript(
                id = -3,
                name = "斗罗大陆H5: 宗门任务 & 每日祈福签到",
                description = "自动进入宗门大厅，完成宗门建设、神树祈福与每日俸禄领取",
                category = "GM99斗罗H5",
                actionsJson = toJson(
                    listOf(
                        MacroAction(ActionType.CLICK, 0.78f, 0.92f, 1500L, label = "打开宗门界面"),
                        MacroAction(ActionType.CLICK, 0.35f, 0.65f, 1800L, label = "点击宗门建设"),
                        MacroAction(ActionType.CLICK, 0.50f, 0.80f, 1200L, label = "一键高级建设"),
                        MacroAction(ActionType.CLICK, 0.68f, 0.65f, 1800L, label = "宗门神树祈福"),
                        MacroAction(ActionType.CLICK, 0.50f, 0.78f, 1500L, label = "领取每日俸禄"),
                        MacroAction(ActionType.CLICK, 0.93f, 0.08f, 1000L, label = "返回主城")
                    )
                ),
                repeatCount = 1,
                speedMultiplier = 1.0f,
                randomJitterPx = 5,
                intervalBetweenLoopsMs = 1000L
            ),
            MacroScript(
                id = -5,
                name = "通用网页: 滚动 + 长按动作演示",
                description = "演示 SCROLL_DOWN / SCROLL_UP 滑动与 LONG_PRESS 长按步骤，可作为自定义脚本的模板",
                category = "通用挂机",
                actionsJson = toJson(
                    listOf(
                        MacroAction(ActionType.SCROLL_DOWN, 0.5f, 0.7f, 1200L, label = "向下滚动一屏"),
                        MacroAction(ActionType.SCROLL_DOWN, 0.5f, 0.7f, 1200L, label = "继续向下滚动"),
                        MacroAction(ActionType.SCROLL_UP, 0.5f, 0.3f, 1200L, label = "向上滚回"),
                        MacroAction(ActionType.LONG_PRESS, 0.5f, 0.5f, 1200L, label = "长按屏幕中央")
                    )
                ),
                repeatCount = 1,
                speedMultiplier = 1.0f,
                randomJitterPx = 0,
                intervalBetweenLoopsMs = 1000L
            ),
            MacroScript(
                id = -4,
                name = "通用网页: 定时全屏连点/防掉线心跳",
                description = "在屏幕中心定期触发轻触，保持H5游戏WebSocket连接不断开，防止挂机被判定离线",
                category = "通用挂机",
                actionsJson = toJson(
                    listOf(
                        MacroAction(ActionType.CLICK, 0.50f, 0.50f, 3000L, label = "心跳防掉线点击"),
                        MacroAction(ActionType.CLICK, 0.52f, 0.48f, 3000L, label = "微调心跳点击")
                    )
                ),
                repeatCount = 0,
                speedMultiplier = 1.0f,
                randomJitterPx = 10,
                intervalBetweenLoopsMs = 5000L
            )
        )
    }
}
