package com.xbot.android.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.xbot.android.core.AppViewModel
import com.xbot.android.model.EnrollCapture
import com.xbot.android.model.Gender
import com.xbot.android.model.OwnerProfile
import com.xbot.android.voice.AudioCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * 首次激活向导（6 步）。对应 Flutter OnboardingScreen。
 *
 * 0. 欢迎
 * 1. 关于你：昵称（必填）/性别/生日
 * 2. 机器人昵称（默认「狗蛋」）
 * 3. 人脸录入：采若干样本（captureFaceSample）
 * 4. 声纹录入：采若干句语音（captureUtterance + 声纹嵌入）
 * 5. 总结 → completeOnboarding → 进 ready
 */
@Composable
fun OnboardingScreen(
    appViewModel: AppViewModel,
    onComplete: () -> Unit,
) {
    val totalSteps = 6
    val requiredSamples = 5
    val requiredVoiceSamples = 3
    var step by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 表单暂存。
    var nickname by remember { mutableStateOf("") }
    var robotName by remember { mutableStateOf("狗蛋") }
    var gender by remember { mutableStateOf<Gender?>(null) }
    var birthday by remember { mutableStateOf("") } // yyyy-MM-dd
    // 人脸样本 + 录入状态。
    val faceSamples = remember { mutableStateListOf<EnrollCapture>() }
    var faceState by remember { mutableStateOf(FaceScanState.IDLE) }
    var faceScanning by remember { mutableStateOf(false) }
    var faceMessage by remember { mutableStateOf<String?>(null) }
    // 声纹样本（PCM16）+ 录入状态。
    val voiceSamples = remember { mutableStateListOf<ShortArray>() }
    var voiceState by remember { mutableStateOf(FaceScanState.IDLE) }
    var voiceScanning by remember { mutableStateOf(false) }
    var voiceMessage by remember { mutableStateOf<String?>(null) }

    // 启动人脸录入：循环采样直到达到 requiredSamples 或超时。
    fun startFaceScan() {
        if (!appViewModel.canEnroll) {
            faceState = FaceScanState.FAILED
            faceMessage = "未加载身份识别模型，可稍后在设置中补录"
            return
        }
        faceScanning = true
        faceState = FaceScanState.COLLECTING
        faceSamples.clear()
        faceMessage = null
        scope.launch {
            val deadline = System.currentTimeMillis() + 20_000
            while (faceSamples.size < requiredSamples && System.currentTimeMillis() < deadline) {
                val capture = withTimeoutOrNull(6_000L) {
                    suspendCancellableCoroutine<EnrollCapture?> { cont ->
                        cont.invokeOnCancellation { appViewModel.clearEnrollRequest() }
                        appViewModel.requestEnrollCapture { c -> if (cont.isActive) cont.resume(c) }
                    }
                }
                if (capture != null && capture.hasEmbedding) {
                    faceSamples.add(capture)
                    if (faceSamples.size < requiredSamples) delay(350)
                }
            }
            if (faceSamples.isEmpty()) {
                faceState = FaceScanState.FAILED
                faceMessage = "未捕获到清晰人脸，可重试或稍后补录"
            } else {
                faceState = FaceScanState.SUCCESS
                faceMessage = "记住你啦（${faceSamples.size}/$requiredSamples）"
            }
            faceScanning = false
        }
    }

    fun resetFaceScan() {
        appViewModel.clearEnrollRequest()
        faceSamples.clear()
        faceState = FaceScanState.IDLE
        faceMessage = null
    }

    // 启动声纹录入：循环采集若干句 PCM（带 VAD 端点检测），声纹模型就绪时立即提嵌入校验。
    fun startVoiceScan() {
        if (!appViewModel.canEnrollVoice) {
            voiceState = FaceScanState.FAILED
            voiceMessage = "未加载声纹模型，可稍后在设置中补录"
            return
        }
        voiceScanning = true
        voiceState = FaceScanState.COLLECTING
        voiceSamples.clear()
        voiceMessage = null
        scope.launch {
            val capture = AudioCapture()
            val deadline = System.currentTimeMillis() + 30_000
            while (voiceSamples.size < requiredVoiceSamples && System.currentTimeMillis() < deadline) {
                // captureUtterance 阻塞直到录完一句（VAD 端点检测），必须在 IO 线程。
                val result = withContext(Dispatchers.IO) {
                    withTimeoutOrNull(10_000L) { capture.captureUtterance(context) }
                }
                val pcm = result?.pcm16
                // 校验：能提嵌入才算有效样本（过短/噪声会被 embed 拒绝）。
                if (pcm != null && appViewModel.enrollVoice(pcm) != null) {
                    voiceSamples.add(pcm)
                    if (voiceSamples.size < requiredVoiceSamples) delay(400)
                }
            }
            try { capture.stop() } catch (_: Exception) {}
            if (voiceSamples.isEmpty()) {
                voiceState = FaceScanState.FAILED
                voiceMessage = "未录到清晰语音，可重试或稍后补录"
            } else {
                voiceState = FaceScanState.SUCCESS
                voiceMessage = "记住你的声音啦（${voiceSamples.size}/$requiredVoiceSamples）"
            }
            voiceScanning = false
        }
    }

    fun resetVoiceScan() {
        voiceSamples.clear()
        voiceState = FaceScanState.IDLE
        voiceMessage = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 处理键盘弹出时内容上推，避免输入框被遮挡。
            .windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 20.dp)) {
            // 进度指示点。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                for (i in 0 until totalSteps) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (i == step) 10.dp else 8.dp)
                            .background(
                                color = if (i <= step) MaterialTheme.colorScheme.primary else Color.Gray,
                                shape = CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // 步骤内容（占据剩余空间，底部按钮固定在下方）。
            // clipToBounds 确保内容不会绘制到按钮区域之上（表单较长时不再与按钮重叠）。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds(),
                contentAlignment = Alignment.TopStart,
            ) {
                when (step) {
                    0 -> WelcomeStep()
                    1 -> AboutYouStep(
                        nickname = nickname, onNickname = { nickname = it },
                        gender = gender, onGender = { gender = it },
                        birthday = birthday, onBirthday = { birthday = it },
                    )
                    2 -> RobotNameStep(robotName = robotName, onRobotName = { robotName = it })
                    3 -> FaceScanStep(
                        appViewModel = appViewModel,
                        samples = faceSamples,
                        faceState = faceState,
                        scanning = faceScanning,
                        message = faceMessage,
                        requiredSamples = requiredSamples,
                    )
                    4 -> VoiceScanStep(
                        voiceState = voiceState,
                        scanning = voiceScanning,
                        message = voiceMessage,
                        collected = voiceSamples.size,
                        required = requiredVoiceSamples,
                    )
                    5 -> SummaryStep(
                        nickname = nickname, robotName = robotName,
                        gender = gender, birthday = birthday,
                        faceCount = faceSamples.size,
                        voiceCount = voiceSamples.size,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            // 导航按钮（固定在底部，独立 Row，不与上方内容重叠）。
            val isLast = step == totalSteps - 1
            val isFaceStep = step == 3
            val isVoiceStep = step == 4
            val anyScanning = faceScanning || voiceScanning
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = { step-- }, enabled = !anyScanning) { Text("上一步") }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 人脸/声纹步骤已有样本时提供「重新录入」次级入口。
                    if (isFaceStep && faceSamples.isNotEmpty() && !faceScanning) {
                        TextButton(onClick = { resetFaceScan() }) { Text("重新录入") }
                        Spacer(Modifier.width(8.dp))
                    }
                    if (isVoiceStep && voiceSamples.isNotEmpty() && !voiceScanning) {
                        TextButton(onClick = { resetVoiceScan() }) { Text("重新录入") }
                        Spacer(Modifier.width(8.dp))
                    }
                    // 主按钮：人脸/声纹步骤复用为「开始录入/采集中/继续」。
                    val primaryLabel: String
                    val primaryAction: (() -> Unit)?
                    if (isFaceStep) {
                        when {
                            faceScanning -> {
                                primaryLabel = "采集中…（${faceSamples.size}/$requiredSamples）"
                                primaryAction = null
                            }
                            faceSamples.isEmpty() -> {
                                primaryLabel = "开始录入"
                                primaryAction = { startFaceScan() }
                            }
                            else -> {
                                primaryLabel = "继续"
                                primaryAction = { step++ }
                            }
                        }
                    } else if (isVoiceStep) {
                        when {
                            voiceScanning -> {
                                primaryLabel = "录音中…（${voiceSamples.size}/$requiredVoiceSamples）"
                                primaryAction = null
                            }
                            voiceSamples.isEmpty() -> {
                                primaryLabel = "开始录音"
                                primaryAction = { startVoiceScan() }
                            }
                            else -> {
                                primaryLabel = "继续"
                                primaryAction = { step++ }
                            }
                        }
                    } else {
                        primaryLabel = if (isLast) "完成" else "下一步"
                        primaryAction = if (step == 1 && nickname.isBlank()) {
                            null
                        } else if (isLast) {
                            {
                                appViewModel.completeOnboarding(
                                    profile = OwnerProfile(
                                        nickname = nickname.ifBlank { "主人" },
                                        robotName = robotName.ifBlank { "狗蛋" },
                                        gender = gender,
                                        birthday = birthday.ifBlank { null },
                                    ),
                                    faceSamples = faceSamples.toList(),
                                    voiceSamples = voiceSamples.toList(),
                                )
                                onComplete()
                            }
                        } else {
                            { step++ }
                        }
                    }
                    Button(onClick = { primaryAction?.invoke() }, enabled = primaryAction != null) {
                        Text(primaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(title: String, subtitle: String = "") {
    Column {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = Color.Gray, fontSize = 13.sp)
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("👋", fontSize = 56.sp)
        Spacer(Modifier.height(16.dp))
        StepTitle("欢迎使用 XBot", "让我们花一分钟认识彼此")
    }
}

@Composable
private fun AboutYouStep(
    nickname: String, onNickname: (String) -> Unit,
    gender: Gender?, onGender: (Gender?) -> Unit,
    birthday: String, onBirthday: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("关于你", "告诉我一些关于你的事")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = nickname, onValueChange = onNickname,
            label = { Text("你的称呼 *") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        Text("性别", color = Color.White)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Gender.entries.forEach { g ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = gender == g,
                        onClick = { onGender(if (gender == g) null else g) },
                    )
                    Text(g.label, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = birthday, onValueChange = onBirthday,
            label = { Text("生日（如 1995-06-30，可选）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
    }
}

@Composable
private fun RobotNameStep(robotName: String, onRobotName: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("给我起个名字", "你想叫我什么？")
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = robotName, onValueChange = onRobotName,
            label = { Text("机器人昵称") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = textFieldColors(),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "你好，我是 ${robotName.ifBlank { "狗蛋" }}，很高兴认识你！",
            color = Color.White,
        )
    }
}

@Composable
private fun FaceScanStep(
    appViewModel: AppViewModel,
    samples: MutableList<EnrollCapture>,
    faceState: FaceScanState,
    scanning: Boolean,
    message: String?,
    requiredSamples: Int,
) {
    val context = LocalContext.current
    var hasCamera by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        hasCamera = granted
        if (!granted) permLauncher.launch(Manifest.permission.CAMERA)
    }

    // 后台帧分析 executor：把每帧喂给 tryEnrollFrame 驱动录入采样。
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { Thread(it, "xbot-enroll").apply { isDaemon = true } }
    }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    val accent = Color(0xFF0A84FF)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val statusColor = when (faceState) {
        FaceScanState.SUCCESS -> green
        FaceScanState.FAILED, FaceScanState.DUPLICATE -> red
        else -> accent
    }
    val title = when (faceState) {
        FaceScanState.COLLECTING -> if (scanning) "采集中…请保持稳定" else "请将面部正对摄像头"
        FaceScanState.SUCCESS -> "录入成功"
        FaceScanState.FAILED -> "录入失败"
        FaceScanState.DUPLICATE -> "已认识"
        FaceScanState.IDLE -> "录入面部"
    }
    val subtitle = when (faceState) {
        FaceScanState.COLLECTING ->
            if (scanning) "已采集 ${samples.size}/$requiredSamples · 请缓慢转动头部"
            else "保持光线充足，动作放缓"
        FaceScanState.FAILED -> message ?: "未捕获到清晰人脸，可重试或稍后补录"
        FaceScanState.DUPLICATE -> message ?: "这张脸我已认识啦"
        FaceScanState.SUCCESS -> message ?: "记住你啦，下次见一定认得你"
        FaceScanState.IDLE -> "让机器人认出你是谁"
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 左：Face-ID 风格扫描环（圆形相机预览 + 放射刻度）。
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            FaceScanRing(
                state = faceState,
                progress = samples.size / requiredSamples.toFloat(),
                scanning = scanning,
                cameraActive = hasCamera,
                analysisExecutor = analysisExecutor,
                analyzer = { image ->
                    val bmp = image.toBitmapOrNull()
                    if (bmp != null) appViewModel.tryEnrollFrame(bmp)
                    image.close()
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        // 右：状态提示卡 + 说明（操作按钮复用全局底部栏）。
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            // 状态提示卡。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(title, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color(0xFF9A9AA0), fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text("说明", color = Color(0xFF9A9AA0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    "点击「开始录入」后，相机将自动采集多帧人脸样本；建议缓慢转动头部以提升识别稳定性。人脸数据仅保存在本机，不会上传。",
                    color = Color(0xFF9A9AA0),
                    fontSize = 13.sp,
                )
            }
            if (!hasCamera) {
                Spacer(Modifier.height(12.dp))
                Text("等待摄像头权限…", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}

/**
 * 声纹录入步骤：居中麦克风图标 + 进度 + 状态卡。
 * 范式与 [FaceScanStep] 对称（复用 [FaceScanState] 状态枚举），但不占相机预览。
 */
@Composable
private fun VoiceScanStep(
    voiceState: FaceScanState,
    scanning: Boolean,
    message: String?,
    collected: Int,
    required: Int,
) {
    val accent = Color(0xFF0A84FF)
    val green = Color(0xFF30D158)
    val red = Color(0xFFFF453A)
    val statusColor = when (voiceState) {
        FaceScanState.SUCCESS -> green
        FaceScanState.FAILED, FaceScanState.DUPLICATE -> red
        else -> accent
    }
    val title = when (voiceState) {
        FaceScanState.COLLECTING -> if (scanning) "录音中…请自然说话" else "请对着机器人说话"
        FaceScanState.SUCCESS -> "录入成功"
        FaceScanState.FAILED -> "录入失败"
        FaceScanState.DUPLICATE -> "已认识"
        FaceScanState.IDLE -> "录入声音"
    }
    val subtitle = when (voiceState) {
        FaceScanState.COLLECTING ->
            if (scanning) "已采集 $collected/$required · 每句说完停顿一下"
            else "在安静环境下清晰说话"
        FaceScanState.FAILED -> message ?: "未录到清晰语音，可重试或稍后补录"
        FaceScanState.DUPLICATE -> message ?: "这段声音我已认识啦"
        FaceScanState.SUCCESS -> message ?: "记住你的声音啦，下次开口就能认出你"
        FaceScanState.IDLE -> "让机器人通过声音认出你"
    }
    val progress = (collected / required.toFloat()).coerceIn(0f, 1f)

    Row(modifier = Modifier.fillMaxSize()) {
        // 左：麦克风图标 + 进度环。
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight().padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // 麦克风图标外圈（颜色随状态变化）。
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .background(statusColor.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (scanning) "🎙️" else "🎤",
                        fontSize = 64.sp,
                    )
                }
                Spacer(Modifier.height(24.dp))
                // 进度条。
                LinearProgressIndicator(
                    progress = { progress },
                    color = statusColor,
                    trackColor = Color(0xFF2C2C2E),
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(6.dp)
                        .clip(CircleShape),
                )
                Spacer(Modifier.height(8.dp))
                Text("$collected / $required", color = Color.Gray, fontSize = 13.sp)
            }
        }
        // 右：状态卡 + 说明（与 FaceScanStep 右侧布局对称）。
        Column(
            modifier = Modifier
                .width(300.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(title, color = statusColor, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = Color(0xFF9A9AA0), fontSize = 12.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text("说明", color = Color(0xFF9A9AA0), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1C1C1E), RoundedCornerShape(12.dp))
                    .padding(14.dp),
            ) {
                Text(
                    "点击「开始录音」后，请对着机器人自然说几句话，比如『你好，我是XX』。每说完一句停顿一下，机器人会自动采音。声纹数据仅保存在本机，不会上传。",
                    color = Color(0xFF9A9AA0),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** ImageProxy → Bitmap（nullable；失败返回 null）。 */
private fun ImageProxy.toBitmapOrNull(): android.graphics.Bitmap? = try {
    toBitmap()
} catch (_: Exception) {
    null
}

@Composable
private fun SummaryStep(
    nickname: String, robotName: String,
    gender: Gender?, birthday: String, faceCount: Int, voiceCount: Int = 0,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StepTitle("就绪", "确认信息并完成")
        Spacer(Modifier.height(24.dp))
        SummaryRow("你的称呼", nickname.ifBlank { "主人" })
        SummaryRow("机器人昵称", robotName.ifBlank { "狗蛋" })
        SummaryRow("性别", gender?.label ?: "未填写")
        SummaryRow("生日", birthday.ifBlank { "未填写" })
        SummaryRow("人脸", if (faceCount > 0) "已录入（$faceCount 张）" else "未录入")
        SummaryRow("声纹", if (voiceCount > 0) "已录入（$voiceCount 句）" else "未录入")
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = Color.Gray)
        Text(value, color = Color.White)
    }
}

@Composable
private fun textFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.Gray,
    cursorColor = MaterialTheme.colorScheme.primary,
)
