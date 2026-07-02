package com.xbot.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xbot.android.core.AppViewModel
import com.xbot.android.voice.AudioCapture
import com.xbot.android.voice.ConversationEntry
import com.xbot.android.voice.VoiceLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 完整设置页。对应 Flutter settings_screen.dart 的原生等价（暗色 Material3 列表）。
 *
 * 分组：显示模式 / 身份识别 / 语音助手 / Pophie 服务 / 识别功能 / 关于。
 * 改动即时持久化到 [SettingsStore] 并应用到运行中的语音助手 / 视觉管线。
 */
@Composable
fun SettingsScreen(
    controller: MainScreenController,
    appViewModel: AppViewModel,
    onClose: () -> Unit,
    onResetOwner: () -> Unit,
    onRedownload: () -> Unit = {},
) {
    val store = controller.settingsStore
    val s = store.settings
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 弹窗状态。
    var showLog by remember { mutableStateOf(false) }
    // 语音记录子页（设置页「语音记录」入口）。
    var showVoiceLog by remember { mutableStateOf(false) }
    // 模型资源重下确认弹窗（开发期测试用）。
    var confirmRedownload by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<EditTarget?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var connResult by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    // 声纹管理状态。
    var showVoiceManager by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    var voiceMsg by remember { mutableStateOf<String?>(null) }
    // 触发声纹样本数刷新（录入/清除后重新读取）。
    var voiceRefresh by remember { mutableStateOf(0) }
    // 声纹测试状态。
    var showVoiceTest by remember { mutableStateOf(false) }
    var voiceTesting by remember { mutableStateOf(false) }
    var voiceTestResult by remember { mutableStateOf<com.xbot.android.core.AppViewModel.VoiceTestResult?>(null) }

    if (showLog) {
        ConversationLogScreen(controller, onBack = { showLog = false })
        return
    }

    if (showVoiceLog) {
        VoiceLogScreen(controller, onBack = { showVoiceLog = false })
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶栏。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text("设置", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                // —— 显示模式 ——
                SettingsSection("显示模式", "关闭时显示虚拟形象；开启后切换到摄像头识别画面（调试人脸/手势）。") {
                    SwitchRow("调试模式", s.debugMode) { v ->
                        store.update { it.copy(debugMode = v) }
                    }
                }

                // —— 身份识别 ——
                SettingsSection("身份识别") {
                    InfoRow("主人", appViewModel.ownerProfileStore.profile?.nickname ?: "—")
                    InfoRow("认识我", "已录入 ${appViewModel.personRepository.people.size} 人")
                    SwitchRow("启用身份识别", s.identityEnabled) { v ->
                        store.update { it.copy(identityEnabled = v) }
                    }
                    SwitchRow("声纹识别", s.voiceIdentityEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(voiceIdentityEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                    NavRow("声纹管理", "已录入 ${appViewModel.ownerVoiceSampleCount} 句", enabled = s.voiceEnabled) {
                        showVoiceManager = true
                    }
                    NavRow("声纹测试", "说话测试匹配度并调阈值", enabled = s.voiceEnabled) {
                        showVoiceTest = true
                    }
                    NavRow("重新设置主人", "清除主人信息并重新进入引导") { confirmReset = true }
                }

                // —— 语音助手 ——
                SettingsSection(
                    "语音助手",
                    "开启后可双击触发或语音唤醒并对话。唤醒词本地离线检测，识别与对话走云端。",
                ) {
                    SwitchRow("启用语音助手", s.voiceEnabled) { v ->
                        store.update { it.copy(voiceEnabled = v) }
                        controller.onVoiceEnabledChanged()
                    }
                    SwitchRow("语音唤醒", s.wakeWordEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(wakeWordEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                    NavRow("唤醒词", s.wakeWord, enabled = s.voiceEnabled && s.wakeWordEnabled) {
                        editTarget = EditTarget.WakeWord
                    }
                    SwitchRow("语音播报", s.ttsEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(ttsEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                    SwitchRow("流式对话模式", s.streamingSttEnabled, enabled = s.voiceEnabled) { v ->
                        store.update { it.copy(streamingSttEnabled = v) }
                        controller.applyVoiceSettings()
                    }
                    NavRow("语音记录", "查看识别到的语音及说话人", enabled = s.voiceEnabled) {
                        showVoiceLog = true
                    }
                }

                // —— Pophie 服务 ——
                SettingsSection(
                    "Pophie 服务",
                    "语音对话走 Pophie 后端，一次完成识别+大模型+合成。请填写后端地址（局域网用电脑 IP）。",
                ) {
                    NavRow("后端地址", s.baseUrl) { editTarget = EditTarget.BaseUrl }
                    NavRow("TTS 音色", s.voiceId.ifBlank { "默认音色" }) { editTarget = EditTarget.VoiceId }
                    InfoRow("设备 ID", s.robotId)
                    NavRow("测试连接", if (testing) "检测中…" else "检查后端是否可达") {
                        if (!testing) {
                            testing = true
                            connResult = null
                            scope.launch {
                                val va = controller.ensureVoiceAssistant()
                                va.pophie.config = va.pophie.config.copy(baseUrl = store.settings.baseUrl)
                                val ok = va.pophie.health()
                                testing = false
                                connResult = ok
                            }
                        }
                    }
                    NavRow("交互日志", "查看语音对话各阶段记录") { showLog = true }
                }

                // —— 识别功能 ——
                SettingsSection("识别功能") {
                    SwitchRow("人脸表情识别", s.faceEnabled) { v ->
                        store.update { it.copy(faceEnabled = v) }
                    }
                    SwitchRow("手势识别", s.handEnabled) { v ->
                        store.update { it.copy(handEnabled = v) }
                    }
                    SwitchRow("物体识别", s.objectEnabled) { v ->
                        store.update { it.copy(objectEnabled = v) }
                    }
                }

                // —— 模型资源 ——
                SettingsSection("模型资源") {
                    val rm = appViewModel.resourceManager
                    val ready = rm.isReady()
                    val ok = ready && rm.verifyAll()
                    InfoRow(
                        "端侧模型状态",
                        when {
                            ok -> "已就绪"
                            ready -> "已下载（校验未通过）"
                            else -> "未下载"
                        },
                    )
                    NavRow("重新下载", "清除就绪标记并回到下载页（开发测试用）") { confirmRedownload = true }
                }

                // —— 关于 ——
                SettingsSection("关于") {
                    InfoRow("版本", "1.0.0")
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // —— 弹窗：编辑 ——
    editTarget?.let { target ->
        val (title, initial) = when (target) {
            EditTarget.WakeWord -> "设置唤醒词" to s.wakeWord
            EditTarget.BaseUrl -> "后端地址 (Base URL)" to s.baseUrl
            EditTarget.VoiceId -> "TTS 音色 ID（空=默认）" to s.voiceId
        }
        TextEditDialog(
            title = title,
            initial = initial,
            onConfirm = { text ->
                when (target) {
                    EditTarget.WakeWord -> {
                        val kw = text.trim()
                        if (kw.isNotEmpty()) {
                            store.update { it.copy(wakeWord = kw) }
                            controller.applyVoiceSettings()
                        }
                    }
                    EditTarget.BaseUrl -> {
                        val url = text.trim().trimEnd('/')
                        if (url.isNotEmpty()) {
                            store.update { it.copy(baseUrl = url) }
                            controller.applyVoiceSettings()
                        }
                    }
                    EditTarget.VoiceId -> {
                        store.update { it.copy(voiceId = text.trim()) }
                        controller.applyVoiceSettings()
                    }
                }
                editTarget = null
            },
            onDismiss = { editTarget = null },
        )
    }

    // —— 弹窗：测试连接结果 ——
    connResult?.let { ok ->
        AlertDialog(
            onDismissRequest = { connResult = null },
            confirmButton = { TextButton(onClick = { connResult = null }) { Text("好") } },
            title = { Text(if (ok) "连接成功" else "连接失败") },
            text = {
                Text(
                    if (ok) "后端可达，语音能力已就绪。"
                    else "无法连接后端，请检查地址与网络（同一局域网、防火墙、端口 8000）。",
                )
            },
        )
    }

    // —— 弹窗：重设主人确认 ——
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onResetOwner()
                }) { Text("重新设置", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
            title = { Text("重新设置主人") },
            text = { Text("将清除主人信息（含人脸）并重新进入引导。此操作不可撤销，确定继续吗？") },
        )
    }

    // —— 弹窗：重新下载模型确认 ——
    if (confirmRedownload) {
        AlertDialog(
            onDismissRequest = { confirmRedownload = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmRedownload = false
                    appViewModel.requestRedownload()
                    onRedownload()
                }) { Text("重新下载") }
            },
            dismissButton = { TextButton(onClick = { confirmRedownload = false }) { Text("取消") } },
            title = { Text("重新下载模型") },
            text = { Text("将清除就绪标记并回到下载页。已下载的文件不会被删除（走断点续传）。继续吗？") },
        )
    }

    // —— 弹窗：声纹管理 ——
    if (showVoiceManager) {
        @Suppress("UNUSED_EXPRESSION") voiceRefresh  // 建立重组依赖，录入/清除后刷新计数
        val currentCount = appViewModel.ownerVoiceSampleCount
        AlertDialog(
            onDismissRequest = {
                if (!voiceRecording) { showVoiceManager = false; voiceMsg = null }
            },
            confirmButton = { TextButton(onClick = {
                if (!voiceRecording) { showVoiceManager = false; voiceMsg = null }
            }) { Text("关闭") } },
            title = { Text("声纹管理") },
            text = {
                Column {
                    Text("已录入声纹：${currentCount} 句")
                    Spacer(Modifier.height(8.dp))
                    if (voiceMsg != null) {
                        Text(voiceMsg!!, color = Color(0xFF9A9AA0), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    // 录入一句按钮。
                    TextButton(
                        onClick = {
                            if (voiceRecording || !appViewModel.canEnrollVoice) return@TextButton
                            voiceRecording = true
                            voiceMsg = "录音中…请对着机器人说一句话"
                            scope.launch {
                                val capture = AudioCapture()
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { capture.captureUtterance(context) }.getOrNull()
                                }
                                try { capture.stop() } catch (_: Exception) {}
                                val pcm = result?.pcm16
                                val added = if (pcm != null) appViewModel.saveVoiceToOwner(listOf(pcm)) else 0
                                voiceRecording = false
                                voiceMsg = if (added > 0) "录入成功（+1 句）" else "录入失败，请重试"
                                if (added > 0) voiceRefresh++
                            }
                        },
                        enabled = !voiceRecording && appViewModel.canEnrollVoice,
                    ) { Text(if (voiceRecording) "录音中…" else "录入一句") }
                    // 清除声纹按钮。
                    if (currentCount > 0) {
                        TextButton(onClick = {
                            if (appViewModel.clearOwnerVoice()) {
                                voiceMsg = "已清除全部声纹"
                                voiceRefresh++
                            }
                        }) { Text("清除声纹", color = Color(0xFFFF453A)) }
                    }
                    if (!appViewModel.canEnrollVoice) {
                        Spacer(Modifier.height(4.dp))
                        Text("声纹模型未就绪，请稍后重试", color = Color(0xFF6A6A70), fontSize = 12.sp)
                    }
                }
            },
        )
    }

    // —— 弹窗：声纹测试 ——
    if (showVoiceTest) {
        val green = Color(0xFF30D158)
        val red = Color(0xFFFF453A)
        val accent = Color(0xFF0A84FF)
        val r = voiceTestResult
        // 测试结果的状态色与文案（阈值滑动后基于上次分数实时重判）。
        val statusColor = when {
            r == null -> accent
            r.score < 0f -> Color(0xFF9A9AA0)  // 无效输入（未就绪/过短/无人录入）
            r.matched -> green
            else -> red
        }
        AlertDialog(
            onDismissRequest = {
                if (!voiceTesting) { showVoiceTest = false; voiceTestResult = null }
            },
            confirmButton = { TextButton(onClick = {
                if (!voiceTesting) { showVoiceTest = false; voiceTestResult = null }
            }) { Text("关闭") } },
            title = { Text("声纹测试") },
            text = {
                Column {
                    // 操作按钮：测一句话。
                    TextButton(
                        onClick = {
                            if (voiceTesting || !appViewModel.canEnrollVoice) return@TextButton
                            voiceTesting = true
                            voiceTestResult = null
                            scope.launch {
                                val capture = AudioCapture()
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { capture.captureUtterance(context) }.getOrNull()
                                }
                                try { capture.stop() } catch (_: Exception) {}
                                voiceTesting = false
                                val pcm = result?.pcm16
                                voiceTestResult = if (pcm != null) appViewModel.testVoice(pcm)
                                                  else com.xbot.android.core.AppViewModel.VoiceTestResult(false, -1f, s.voiceMatchThreshold, null)
                            }
                        },
                        enabled = !voiceTesting && appViewModel.canEnrollVoice,
                    ) { Text(if (voiceTesting) "录音中…请说话" else "测试一句") }

                    Spacer(Modifier.height(10.dp))

                    // 结果区。
                    if (voiceTesting) {
                        Text("正在采音与比对…", color = accent, fontSize = 13.sp)
                    } else if (r != null) {
                        if (r.score < 0f) {
                            Text("未取到有效语音（模型未就绪/语音过短/未录入声纹）", color = statusColor, fontSize = 13.sp)
                        } else {
                            // 匹配状态行。
                            Text(
                                if (r.matched) "✓ 匹配：${r.name ?: "未知"}" else "✗ 未匹配（最相似：${r.name ?: "无"}）",
                                color = statusColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(4.dp))
                            // 分数行。
                            Text(
                                "相似度 %.2f    阈值 %.2f".format(r.score, s.voiceMatchThreshold),
                                color = Color(0xFF9A9AA0),
                                fontSize = 13.sp,
                            )
                            // 分数 vs 阈值的可视化条。
                            Spacer(Modifier.height(6.dp))
                            ScoreBar(r.score, s.voiceMatchThreshold, statusColor, green, red)
                        }
                    } else {
                        Text("点击「测试一句」，对着机器人说话", color = Color(0xFF9A9AA0), fontSize = 13.sp)
                    }

                    Spacer(Modifier.height(16.dp))

                    // 阈值调节滑块（0.01 步进，0.30~0.90）。
                    Text("匹配阈值：%.2f".format(s.voiceMatchThreshold), color = Color.White, fontSize = 13.sp)
                    Slider(
                        value = s.voiceMatchThreshold,
                        onValueChange = { v ->
                            // 步进 0.01：量化到最近档位。
                            val stepped = (v * 100).toInt() / 100f
                            appViewModel.updateVoiceThreshold(stepped)
                            // 基于上次分数实时重判「是否匹配」。
                            voiceTestResult = voiceTestResult?.let {
                                it.copy(matched = it.score >= stepped, threshold = stepped)
                            }
                        },
                        valueRange = 0.30f..0.90f,
                        steps = 0,  // 连续滑动，onValueChange 内量化步进
                    )

                    Spacer(Modifier.height(8.dp))
                    Text(
                        "建议：自己多说几句取稳定高分，换人说验证能区分；阈值越高越严格（误认少但可能认不出）。",
                        color = Color(0xFF6A6A70),
                        fontSize = 11.sp,
                    )
                    if (!appViewModel.canEnrollVoice) {
                        Spacer(Modifier.height(6.dp))
                        Text("声纹模型未就绪，请稍后重试", color = Color(0xFF6A6A70), fontSize = 12.sp)
                    }
                }
            },
        )
    }
}

/** 分数 vs 阈值的可视化条：分数条 + 阈值刻度线。 */
@Composable
private fun ScoreBar(score: Float, threshold: Float, scoreColor: Color, passColor: Color, failColor: Color) {
    val range = 0.30f..0.90f
    fun norm(v: Float) = ((v - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val sNorm = norm(score)
    val tNorm = norm(threshold)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        // 轨道。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .align(Alignment.CenterStart)
                .background(Color(0xFF2C2C2E), RoundedCornerShape(4.dp)),
        )
        // 分数条（从左到分数位置）。
        Box(
            modifier = Modifier
                .fillMaxWidth(sNorm)
                .height(8.dp)
                .align(Alignment.CenterStart)
                .background(scoreColor, RoundedCornerShape(4.dp)),
        )
        // 阈值刻度线：宽度 tNorm 的容器，内部 2dp 线靠右对齐 → 线落在阈值位置。
        Box(
            modifier = Modifier
                .fillMaxWidth(tNorm)
                .fillMaxHeight()
                .align(Alignment.CenterStart),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        if (score >= threshold) passColor else failColor,
                        RoundedCornerShape(1.dp),
                    ),
            )
        }
    }
}

private enum class EditTarget { WakeWord, BaseUrl, VoiceId }

@Composable
private fun SettingsSection(
    title: String,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Text(
        title,
        color = Color(0xFF9A9AA0),
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 6.dp),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15151A), RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
    ) {
        content()
    }
    if (footer != null) {
        Text(
            footer,
            color = Color(0xFF6A6A70),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

@Composable
private fun SwitchRow(
    label: String,
    value: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color(0xFF6A6A70),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = value, onCheckedChange = if (enabled) onChange else null, enabled = enabled)
    }
}

@Composable
private fun NavRow(label: String, value: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable { onClick() } else it }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (enabled) Color.White else Color(0xFF6A6A70),
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = Color(0xFF9A9AA0),
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (enabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF6A6A70),
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White, modifier = Modifier.width(96.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, color = Color(0xFF9A9AA0), fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
    }
}

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
            )
        },
    )
}

/** 交互日志页：倒序展示对话各阶段记录，错误红色标记，可清空。 */
@Composable
private fun ConversationLogScreen(controller: MainScreenController, onBack: () -> Unit) {
    val entries = controller.conversationLog.entries
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text("交互日志", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.conversationLog.clear() }) { Text("清空") }
            }
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无记录\n双击屏幕或唤醒「你好小白」开始一轮对话",
                        color = Color(0xFF9A9AA0),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries.reversed()) { e ->
                        LogRow(e, fmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(e: ConversationEntry, fmt: SimpleDateFormat) {
    val color = stageColor(e.stage, e.error)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15151A), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.stage, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(fmt.format(Date(e.timestamp)), color = Color(0xFF6A6A70), fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                e.message,
                color = if (e.error) Color(0xFFFF9D8A) else Color.White,
                fontSize = 13.sp,
            )
        }
    }
}

private fun stageColor(stage: String, error: Boolean): Color {
    if (error) return Color(0xFFFF453A)
    return when (stage) {
        "wake" -> Color(0xFFFFD60A)
        "listen" -> Color(0xFF64D2FF)
        "think" -> Color(0xFFBF5AF2)
        "speak" -> Color(0xFF30D158)
        "end" -> Color(0xFF8E8E93)
        else -> Color(0xFF9A9AA0)
    }
}

/** 语音记录页：倒序展示识别到的语音文本及声纹判定的说话人，可清空。 */
@Composable
private fun VoiceLogScreen(controller: MainScreenController, onBack: () -> Unit) {
    val entries = controller.voiceLog.entries
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF15151A))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Color.White,
                    )
                }
                Text("语音记录", color = Color.White, fontSize = 18.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { controller.voiceLog.clear() }) { Text("清空") }
            }
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "暂无记录\n唤醒或双击对话后，识别到的语音会记录在此",
                        color = Color(0xFF9A9AA0),
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(entries.reversed()) { e ->
                        VoiceLogRow(e, fmt)
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceLogRow(e: VoiceLogEntry, fmt: SimpleDateFormat) {
    // 说话人徽标颜色：主人=绿、其他人=橙、未知=灰。
    val (dot, label) = when {
        e.isOwner -> Color(0xFF30D158) to "主人"
        e.speakerName != null -> Color(0xFFFFD60A) to e.speakerName
        else -> Color(0xFF6A6A70) to "未知"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF15151A), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp, end = 8.dp)
                .size(8.dp)
                .background(dot, RoundedCornerShape(4.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = dot, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(fmt.format(Date(e.timestamp)), color = Color(0xFF6A6A70), fontSize = 11.sp)
            }
            Spacer(Modifier.height(2.dp))
            Text(e.text, color = Color.White, fontSize = 13.sp)
        }
    }
}
