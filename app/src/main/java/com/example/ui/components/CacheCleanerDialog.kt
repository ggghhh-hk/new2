package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cookie
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.engine.CleanOptions
import com.example.ui.theme.AccentRed
import com.example.ui.theme.CyberCyanPrimary

@Composable
fun CacheCleanerDialog(
    onDismiss: () -> Unit,
    onExecuteClean: (CleanOptions) -> Unit
) {
    var clearCache by remember { mutableStateOf(true) }
    var clearCookies by remember { mutableStateOf(true) }
    var clearLocalStorage by remember { mutableStateOf(true) }
    var clearHistory by remember { mutableStateOf(true) }
    var clearFormData by remember { mutableStateOf(true) }
    var allAccounts by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = null,
                            tint = AccentRed,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "一键清理缓存与会话数据",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭")
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "选择要彻底清除的网页数据（常用于切换游戏区服、重置登录状态、解决卡顿）：",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    CleanOptionItem(
                        icon = Icons.Default.FolderZip,
                        title = "网页静态缓存 (Cache)",
                        subtitle = "清除已下载的图片、JS脚本与音效，释放存储空间",
                        checked = clearCache,
                        onCheckedChange = { clearCache = it }
                    )

                    CleanOptionItem(
                        icon = Icons.Default.Cookie,
                        title = "Cookies 与登录凭据",
                        subtitle = "默认只清除当前窗口的账号，其他账号保持登录状态",
                        checked = clearCookies,
                        onCheckedChange = { clearCookies = it }
                    )

                    CleanOptionItem(
                        icon = Icons.Default.Storage,
                        title = "本地存储 (LocalStorage / IndexedDB)",
                        subtitle = "清除H5游戏本地存档数据与Web SQL数据库",
                        checked = clearLocalStorage,
                        onCheckedChange = { clearLocalStorage = it }
                    )

                    CleanOptionItem(
                        icon = Icons.Default.History,
                        title = "浏览历史与表单自动填充",
                        subtitle = "清除标签页跳转记录与输入历史",
                        checked = clearHistory,
                        onCheckedChange = {
                            clearHistory = it
                            clearFormData = it
                        }
                    )

                    CleanOptionItem(
                        icon = Icons.Default.AccountCircle,
                        title = "同时登出全部账号",
                        subtitle = "关闭时只清理当前账号；打开会清除所有窗口的登录状态",
                        checked = allAccounts,
                        onCheckedChange = { allAccounts = it }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("取消")
                        }

                        Button(
                            onClick = {
                                onExecuteClean(
                                    CleanOptions(
                                        clearCache = clearCache,
                                        clearCookies = clearCookies,
                                        clearLocalStorage = clearLocalStorage,
                                        clearHistory = clearHistory,
                                        clearFormData = clearFormData,
                                        allAccounts = allAccounts
                                    )
                                )
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentRed),
                            modifier = Modifier.weight(1f).testTag("confirm_clean_btn")
                        ) {
                            Icon(Icons.Default.CleaningServices, null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("一键彻底清理", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanOptionItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (checked) CyberCyanPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(checkedColor = CyberCyanPrimary)
        )
    }
}
