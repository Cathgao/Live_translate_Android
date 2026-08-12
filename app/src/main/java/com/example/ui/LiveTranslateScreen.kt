package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.ConnectionState
import com.example.ui.components.AdbGuideSheet
import com.example.ui.components.LogConsoleSheet
import com.example.ui.components.SettingsDialog
import com.example.viewmodel.MainViewModel

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

private fun formatTokens(n: Long): String {
    if (n < 0L) return "0"
    if (n < 1000L) return n.toString()
    val thousands = n / 1000.0
    val oneDecimal = String.format(java.util.Locale.US, "%.1f", thousands)
    return "${oneDecimal}k"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveTranslateScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current

    val connectionState by viewModel.connectionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val audioVolume by viewModel.audioVolume.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()

    val serverUrl by viewModel.serverUrl.collectAsState()
    val targetLanguage by viewModel.targetLanguage.collectAsState()
    val vadSilenceMs by viewModel.vadSilenceMs.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()

    DisposableEffect(keepScreenOn, isRecording) {
        val window = (context as? android.app.Activity)?.window
        if (keepScreenOn && isRecording) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val liveOriginalText by viewModel.liveOriginalText.collectAsState()
    val liveTranslatedText by viewModel.liveTranslatedText.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val tokenUsage by viewModel.tokenUsage.collectAsState()

    val showUsbSheet by viewModel.showUsbDiagnosticSheet.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasMicPermission = isGranted
        if (isGranted) {
            viewModel.toggleRecording()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    var showLanguageDropdown by remember { mutableStateOf(false) }
    val targetLanguages = listOf("English", "Chinese (Simplified)", "Spanish", "French", "Japanese", "Korean", "German")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Language Dropdown Lookalike
                        Box {
                            Surface(
                                color = Color.Transparent,
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                modifier = Modifier.clickable { showLanguageDropdown = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(targetLanguage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                            DropdownMenu(
                                expanded = showLanguageDropdown,
                                onDismissRequest = { showLanguageDropdown = false }
                            ) {
                                targetLanguages.forEach { lang ->
                                    DropdownMenuItem(
                                        text = { Text(lang) },
                                        onClick = {
                                            viewModel.updateTargetLanguage(lang)
                                            showLanguageDropdown = false
                                        }
                                    )
                                }
                            }
                        }

                        // Connection Status Pill
                        val (statusText, statusColor, statusIcon) = when (connectionState) {
                            ConnectionState.CONNECTED -> Triple("WebSocket 已连接", MaterialTheme.colorScheme.primary, Icons.Default.Link)
                            ConnectionState.CONNECTING -> Triple("正在连接...", Color(0xFFF57F17), Icons.Default.Link)
                            ConnectionState.ERROR -> Triple("连接错误", MaterialTheme.colorScheme.error, Icons.Default.LinkOff)
                            else -> Triple("WebSocket 未连接", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Default.LinkOff)
                        }
                        
                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            modifier = Modifier.clickable { viewModel.toggleConnect() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(statusText, fontSize = 12.sp, color = statusColor)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setShowSettingsDialog(true) }) {
                        Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Split Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Top Half: Original Text
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "原文转写",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = liveOriginalText.ifEmpty { "点击下方麦克风按钮开始实时同传。" },
                        fontSize = fontSize.sp,
                        fontStyle = if (liveOriginalText.isEmpty()) FontStyle.Italic else FontStyle.Normal,
                        color = if (liveOriginalText.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        lineHeight = (fontSize + 10).sp
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline, thickness = 1.dp)

                // Bottom Half: Translated Text
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(20.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "实时翻译 ($targetLanguage)",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Mute button icon
                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(4.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Default.VolumeOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("关", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = liveTranslatedText.ifEmpty { "点击下方麦克风按钮开始实时同传。" },
                        fontSize = fontSize.sp,
                        fontStyle = if (liveTranslatedText.isEmpty()) FontStyle.Italic else FontStyle.Normal,
                        color = if (liveTranslatedText.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                        lineHeight = (fontSize + 10).sp
                    )
                }
            }

            // Bottom Floating Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(MaterialTheme.colorScheme.surface),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(64.dp)
                ) {
                    if (isRecording) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        )
                    }

                    val micBgColor by animateColorAsState(
                        targetValue = if (isRecording) MaterialTheme.colorScheme.primary else Color(0xFF1E3A8A), // Darker blue if off
                        label = "micBgColor"
                    )

                    Surface(
                        shape = CircleShape,
                        color = micBgColor,
                        modifier = Modifier
                            .size(52.dp)
                            .clickable {
                                if (hasMicPermission) {
                                    viewModel.toggleRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                    ) {
                        Icon(
                            imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = "Microphone",
                            tint = Color.White,
                            modifier = Modifier
                                .padding(14.dp)
                                .fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                // Status Indicator Area
                Column(verticalArrangement = Arrangement.Center) {
                    // Pill
                    val pillBorder = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    val pillBg = if (isRecording) MaterialTheme.colorScheme.primary.copy(alpha=0.1f) else MaterialTheme.colorScheme.error.copy(alpha=0.1f)
                    val pillText = if (isRecording) "麦克风已开启" else "麦克风已关闭"
                    val pillTextColor = if (isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = pillBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, pillBorder)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = pillText,
                                fontSize = 11.sp,
                                color = pillTextColor
                            )
                            if (isRecording) {
                                Spacer(modifier = Modifier.width(4.dp))
                                
                                val animatedVolume by androidx.compose.animation.core.animateFloatAsState(
                                    targetValue = audioVolume.coerceIn(0f, 1f),
                                    animationSpec = androidx.compose.animation.core.tween(durationMillis = 100),
                                    label = "volumeAnim"
                                )
                                
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    modifier = Modifier.height(14.dp)
                                ) {
                                    val multipliers = listOf(0.4f, 0.8f, 1.0f, 0.7f, 0.5f)
                                    multipliers.forEach { multiplier ->
                                        val barHeightMultiplier = (animatedVolume * multiplier * 2.5f).coerceIn(0.15f, 1.0f)
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(14.dp * barHeightMultiplier)
                                                .clip(RoundedCornerShape(1.5.dp))
                                                .background(MaterialTheme.colorScheme.primary)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Token usage row. Mirrors desktop-client App.tsx: shown
                    // only while we're mid-session or already have a non-zero
                    // count, so an idle, fresh-launched app doesn't show 0/0/0.
                    val showTokens = isRecording ||
                        connectionState == ConnectionState.CONNECTING ||
                        connectionState == ConnectionState.CONNECTED ||
                        tokenUsage.inputTokens > 0L ||
                        tokenUsage.outputTokens > 0L
                    if (showTokens) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tokens  ", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                            Text("入 ", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatTokens(tokenUsage.inputTokens),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(" | ", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                            Text("出 ", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatTokens(tokenUsage.outputTokens),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(" | ", fontSize = 9.sp, color = MaterialTheme.colorScheme.outline)
                            Text("合 ", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                text = formatTokens(tokenUsage.inputTokens + tokenUsage.outputTokens),
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    if (showUsbSheet) {
        AdbGuideSheet(
            availableDevices = availableDevices,
            onRefreshDevices = { viewModel.refreshAudioDevices() },
            onDismiss = { viewModel.setShowUsbDiagnosticSheet(false) }
        )
    }

    if (showSettingsDialog) {
        val selectedDevice by viewModel.selectedDevice.collectAsState()
        SettingsDialog(
            serverUrl = serverUrl,
            vadSilenceMs = vadSilenceMs,
            fontSize = fontSize,
            keepScreenOn = keepScreenOn,
            availableDevices = availableDevices,
            selectedDevice = selectedDevice,
            onSave = { newUrl, vad, font, keepScreen, deviceId ->
                viewModel.updateServerUrl(newUrl)
                viewModel.updateVadSilenceMs(vad)
                viewModel.updateFontSize(font)
                viewModel.updateKeepScreenOn(keepScreen)
                if (deviceId != null) {
                    viewModel.selectAudioDevice(deviceId)
                }
            },
            onResetTokens = {
                viewModel.resetTokens()
            },
            onDismiss = { viewModel.setShowSettingsDialog(false) }
        )
    }
}
