package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.ConnectionState
import com.example.ui.components.AdbGuideSheet
import com.example.ui.components.SettingsDialog
import com.example.viewmodel.MainViewModel

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

    val connectionState by viewModel.connectionState.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    val audioVolume by viewModel.audioVolume.collectAsState()
    val availableDevices by viewModel.availableDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()

    val serverUrl by viewModel.serverUrl.collectAsState()
    val targetLanguage by viewModel.targetLanguage.collectAsState()
    val vadSilenceMs by viewModel.vadSilenceMs.collectAsState()
    val fontSize by viewModel.fontSize.collectAsState()
    val keepScreenOn by viewModel.keepScreenOn.collectAsState()
    val micGain by viewModel.micGain.collectAsState()

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

    val originalBase by viewModel.originalBase.collectAsState()
    val originalLive by viewModel.originalLive.collectAsState()
    val translatedBase by viewModel.translatedBase.collectAsState()
    val translatedLive by viewModel.translatedLive.collectAsState()
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
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val cursorAlphaOrig by rememberInfiniteTransition(label = "cursor_orig")
        .animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursorAlphaOrig"
        )

    val cursorAlphaTrans by rememberInfiniteTransition(label = "cursor_trans")
        .animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "cursorAlphaTrans"
        )

    val scrollStateTop = rememberScrollState()
    val scrollStateBottom = rememberScrollState()

    LaunchedEffect(originalBase, originalLive) {
        if (scrollStateTop.maxValue > 0) {
            scrollStateTop.animateScrollTo(scrollStateTop.maxValue)
        }
    }

    LaunchedEffect(translatedBase, translatedLive) {
        if (scrollStateBottom.maxValue > 0) {
            scrollStateBottom.animateScrollTo(scrollStateBottom.maxValue)
        }
    }

    var showLanguageDropdown by remember { mutableStateOf(false) }
    val targetLanguages = listOf("Chinese (Simplified)", "English", "Spanish", "French", "Japanese", "Korean", "German")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Title
                        Text(
                            text = "Gemini 实时同传",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFF8FAFC)
                        )

                        Spacer(modifier = Modifier.width(4.dp))

                        // Target Language Selector Dropdown
                        Box {
                            Surface(
                                color = Color(0xFF1E293B).copy(alpha = 0.8f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier.clickable { showLanguageDropdown = true }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(targetLanguage, fontSize = 12.sp, color = Color(0xFFE2E8F0), fontWeight = FontWeight.Medium)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
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
                            ConnectionState.CONNECTED -> Triple("已连接", Color(0xFF3B82F6), Icons.Default.Link)
                            ConnectionState.CONNECTING -> Triple("连接中", Color(0xFFF59E0B), Icons.Default.Link)
                            ConnectionState.ERROR -> Triple("错误", Color(0xFFEF4444), Icons.Default.LinkOff)
                            else -> Triple("未连接", Color(0xFF64748B), Icons.Default.LinkOff)
                        }

                        Surface(
                            color = Color.Transparent,
                            shape = RoundedCornerShape(8.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.clickable { viewModel.toggleConnect() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            ) {
                                Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(statusText, fontSize = 11.sp, color = statusColor)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.setShowSettingsDialog(true) }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF0F172A)
                )
            )
        },
        containerColor = Color(0xFF020617)
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Main Split Transcription & Translation Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Top Half: Original Spoken Text (原文转写)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF0F172A).copy(alpha = 0.9f))
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Header Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF64748B))
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (connectionState == ConnectionState.CONNECTING) {
                                "正在连接…"
                            } else if (isRecording) {
                                "正在识别语音 (实时转写)"
                            } else {
                                "原文转写"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Text Content Area
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollStateTop)
                    ) {
                        val hasOrig = originalBase.isNotEmpty() || originalLive.isNotEmpty()
                        if (!hasOrig) {
                            if (isRecording) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .scale(pulseScale)
                                            .clip(CircleShape)
                                            .background(Color(0xFFEF4444))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "正在聆听，请开始说话…",
                                        fontSize = 18.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            } else {
                                Text(
                                    text = "点击下方麦克风按钮开始实时同传。",
                                    fontSize = 18.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        } else {
                            val inlineContentOrig = mapOf(
                                "cursor_orig" to InlineTextContent(
                                    Placeholder(
                                        width = 6.sp,
                                        height = (fontSize * 0.75).sp,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .height((fontSize * 0.7).dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(Color(0xFF3B82F6).copy(alpha = cursorAlphaOrig))
                                    )
                                }
                            )

                            val annotatedOrig = buildAnnotatedString {
                                if (originalBase.isNotEmpty()) {
                                    withStyle(SpanStyle(color = Color(0xFF94A3B8), fontWeight = FontWeight.Light)) {
                                        append(originalBase)
                                    }
                                }
                                if (originalBase.isNotEmpty() && originalLive.isNotEmpty()) {
                                    append("\n\n")
                                }
                                if (originalLive.isNotEmpty()) {
                                    withStyle(SpanStyle(color = Color(0xFFF8FAFC), fontWeight = FontWeight.Normal)) {
                                        append(originalLive)
                                    }
                                    if (isRecording) {
                                        append(" ")
                                        appendInlineContent("cursor_orig", "[cursor]")
                                    }
                                }
                            }

                            Text(
                                text = annotatedOrig,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.4).sp,
                                inlineContent = inlineContentOrig
                            )
                        }
                    }
                }

                HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)

                // Bottom Half: Translated Text Stream (实时翻译流)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color(0xFF020617))
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    // Header Row
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(bottom = 12.dp)
                    ) {
                        if (isRecording) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .scale(pulseScale)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF64748B))
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "实时翻译流 ($targetLanguage)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Text Content Area
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollStateBottom)
                    ) {
                        val hasTrans = translatedBase.isNotEmpty() || translatedLive.isNotEmpty()
                        if (!hasTrans) {
                            if (isRecording) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .scale(pulseScale)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "翻译将实时显示在这里…",
                                        fontSize = 18.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            } else {
                                Text(
                                    text = "点击下方麦克风按钮开始实时同传。",
                                    fontSize = 18.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = Color(0xFF64748B),
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        } else {
                            val inlineContentTrans = mapOf(
                                "cursor_trans" to InlineTextContent(
                                    Placeholder(
                                        width = 6.sp,
                                        height = (fontSize * 0.75).sp,
                                        placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(2.5.dp)
                                            .height((fontSize * 0.7).dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(Color(0xFF818CF8).copy(alpha = cursorAlphaTrans))
                                    )
                                }
                            )

                            val liveGradientBrush = Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF60A5FA), // blue-400
                                    Color(0xFFA5B4FC), // indigo-300
                                    Color(0xFFC084FC)  // purple-400
                                )
                            )

                            val annotatedTrans = buildAnnotatedString {
                                if (translatedBase.isNotEmpty()) {
                                    withStyle(SpanStyle(color = Color(0xFF94A3B8), fontWeight = FontWeight.SemiBold)) {
                                        append(translatedBase)
                                    }
                                }
                                if (translatedBase.isNotEmpty() && translatedLive.isNotEmpty()) {
                                    append("\n\n")
                                }
                                if (translatedLive.isNotEmpty()) {
                                    withStyle(
                                        SpanStyle(
                                            brush = liveGradientBrush,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    ) {
                                        append(translatedLive)
                                    }
                                    if (isRecording) {
                                        append(" ")
                                        appendInlineContent("cursor_trans", "[cursor]")
                                    }
                                }
                            }

                            Text(
                                text = annotatedTrans,
                                fontSize = fontSize.sp,
                                lineHeight = (fontSize * 1.4).sp,
                                inlineContent = inlineContentTrans
                            )
                        }
                    }
                }
            }

            // Bottom Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(Color(0xFF0F172A))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Large Mic Button
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
                                .background(Color(0xFFDC2626).copy(alpha = 0.25f))
                        )
                    }

                    val micBgColor by animateColorAsState(
                        targetValue = if (isRecording) Color(0xFFDC2626) else Color(0xFF2563EB),
                        label = "micBgColor"
                    )

                    Surface(
                        shape = CircleShape,
                        color = micBgColor,
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                if (hasMicPermission) {
                                    viewModel.toggleRecording()
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            if (connectionState == ConnectionState.CONNECTING) {
                                CircularProgressIndicator(
                                    color = Color.White,
                                    strokeWidth = 2.5.dp,
                                    modifier = Modifier.size(24.dp)
                                )
                            } else {
                                Icon(
                                    imageVector = if (isRecording) Icons.Default.Mic else Icons.Default.MicOff,
                                    contentDescription = "Microphone",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.width(14.dp))

                // Status, Mic Gain Slider, Clear Button, and Token Usage
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    // Top Row: Mic Status Pill + Mic Gain Slider + Clear Button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Mic Status Badge with Animated Equalizer Bars
                        val pillBorder = if (isRecording) Color(0xFF3B82F6).copy(alpha = 0.6f) else Color(0xFF7F1D1D).copy(alpha = 0.6f)
                        val pillBg = if (isRecording) Color(0xFF0F172A) else Color(0xFF450A0A).copy(alpha = 0.4f)
                        val pillText = if (isRecording) "麦克风已激活" else "麦克风已关闭"
                        val pillTextColor = if (isRecording) Color(0xFF60A5FA) else Color(0xFFF87171)

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = pillBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, pillBorder)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = pillText,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = pillTextColor
                                )
                                if (isRecording) {
                                    Spacer(modifier = Modifier.width(5.dp))

                                    val animatedVolume by animateFloatAsState(
                                        targetValue = audioVolume.coerceIn(0f, 1f),
                                        animationSpec = tween(durationMillis = 80),
                                        label = "volumeAnim"
                                    )

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                                        modifier = Modifier.height(14.dp)
                                    ) {
                                        val multipliers = listOf(0.5f, 0.8f, 1.0f, 0.7f, 0.6f)
                                        multipliers.forEach { multiplier ->
                                            val barHeightMultiplier = (animatedVolume * multiplier * 2.5f).coerceIn(0.2f, 1.0f)
                                            Box(
                                                modifier = Modifier
                                                    .width(2.dp)
                                                    .height(14.dp * barHeightMultiplier)
                                                    .clip(RoundedCornerShape(1.dp))
                                                    .background(Color(0xFF3B82F6))
                                            )
                                        }
                                    }
                                } else {
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(14.dp)
                                            .height(2.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(Color(0xFFEF4444))
                                    )
                                }
                            }
                        }

                        // Mic Gain / Volume Slider Capsule
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.8f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "音量",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8),
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                androidx.compose.material3.Slider(
                                    value = micGain,
                                    onValueChange = { viewModel.updateMicGain(it) },
                                    valueRange = 0.0f..1.0f,
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(24.dp),
                                    colors = androidx.compose.material3.SliderDefaults.colors(
                                        thumbColor = Color(0xFF3B82F6),
                                        activeTrackColor = Color(0xFF3B82F6),
                                        inactiveTrackColor = Color(0xFF334155)
                                    )
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "${(micGain * 100).toInt()}%",
                                    fontSize = 10.sp,
                                    color = Color(0xFF93C5FD),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(36.dp)
                                )
                            }
                        }

                        // Clear Button ("清空")
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1E293B).copy(alpha = 0.8f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            modifier = Modifier.clickable { viewModel.clearAllText() }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "清空",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "清空",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Bottom Row: Token Usage Badge
                    val showTokens = isRecording ||
                            connectionState == ConnectionState.CONNECTING ||
                            connectionState == ConnectionState.CONNECTED ||
                            tokenUsage.inputTokens > 0L ||
                            tokenUsage.outputTokens > 0L
                    if (showTokens) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Tokens  ", fontSize = 10.sp, color = Color(0xFF64748B))
                            Text("入 ", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = formatTokens(tokenUsage.inputTokens),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF93C5FD)
                            )
                            Text(" | ", fontSize = 10.sp, color = Color(0xFF475569))
                            Text("出 ", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = formatTokens(tokenUsage.outputTokens),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6EE7B7)
                            )
                            Text(" | ", fontSize = 10.sp, color = Color(0xFF475569))
                            Text("合 ", fontSize = 10.sp, color = Color(0xFF94A3B8))
                            Text(
                                text = formatTokens(tokenUsage.inputTokens + tokenUsage.outputTokens),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF8FAFC)
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
