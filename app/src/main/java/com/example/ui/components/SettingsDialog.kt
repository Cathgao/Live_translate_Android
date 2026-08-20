package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AudioDeviceItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    serverUrl: String,
    vadSilenceMs: Int,
    fontSize: Int,
    keepScreenOn: Boolean,
    availableDevices: List<AudioDeviceItem>,
    selectedDevice: AudioDeviceItem?,
    onSave: (url: String, vad: Int, font: Int, screen: Boolean, deviceId: Int?) -> Unit,
    onResetTokens: () -> Unit,
    onDismiss: () -> Unit
) {
    var urlText by remember { mutableStateOf(serverUrl) }
    var vadValue by remember { mutableStateOf(vadSilenceMs.toFloat()) }
    var fontValue by remember { mutableStateOf(fontSize.toFloat()) }
    var keepScreen by remember { mutableStateOf(keepScreenOn) }
    var selectedDeviceId by remember { mutableStateOf(selectedDevice?.id) }

    val vadLabel = when {
        vadValue <= 700f -> "极灵敏"
        vadValue <= 1000f -> "默认"
        vadValue <= 1500f -> "较平缓"
        else -> "迟钝"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "设置", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = onDismiss
                ) {
                    Text(text = "✕", fontSize = 18.sp, color = MaterialTheme.colorScheme.outline)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // VAD Silence Interval
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "VAD 静默间隔", fontWeight = FontWeight.Bold)
                        Text(text = "${vadValue.toInt()} ms", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = vadValue,
                        onValueChange = { vadValue = it },
                        valueRange = 500f..3000f,
                        steps = 24
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "灵敏", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = vadLabel, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(text = "迟钝", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = "服务端识别静音多久后判定一句话结束。在下次新建连接时生效。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Font Size
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "转写字号", fontWeight = FontWeight.Bold)
                        Text(text = "${fontValue.toInt()} px", color = MaterialTheme.colorScheme.primary)
                    }
                    Slider(
                        value = fontValue,
                        onValueChange = { fontValue = it },
                        valueRange = 14f..48f,
                        steps = 33
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = "小", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "大", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        text = "立即生效,适用于原文与译文两栏。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                // Keep Screen On
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "☀️ 屏幕常亮", fontWeight = FontWeight.Bold)
                        Text(
                            text = "开启麦克风时阻止手机屏幕自动熄屏锁屏",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = keepScreen,
                        onCheckedChange = { keepScreen = it }
                    )
                }

                // Microphone Selection
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "麦克风设备",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    availableDevices.forEach { device ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedDeviceId == device.id) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { selectedDeviceId = device.id }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(12.dp)
                            ) {
                                RadioButton(
                                    selected = selectedDeviceId == device.id,
                                    onClick = { selectedDeviceId = device.id }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(text = device.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "${device.typeName}${if (device.isUsb) " (USB)" else ""}",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
                
                // WebSocket URL input
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("WebSocket 服务地址") },
                    placeholder = { Text("wss://example.com/live") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )

                // Bottom Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            vadValue = 1000f
                            fontValue = 25f
                            keepScreen = true
                        }
                    ) {
                        Text("恢复默认")
                    }
                    TextButton(
                        onClick = onResetTokens,
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = Color(0xFFFFB300)
                        )
                    ) {
                        Text("清零 Token")
                    }
                }
                Text(
                    text = "自动保存到本地",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(urlText, vadValue.toInt(), fontValue.toInt(), keepScreen, selectedDeviceId)
                    onDismiss()
                }
            ) {
                Text("保存", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {}
    )
}
