package com.xbot.android.core

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.xbot.android.model.EnrollCapture
import com.xbot.android.model.OwnerProfile
import com.xbot.android.model.Person
import com.xbot.android.store.OwnerProfileStore
import com.xbot.android.store.PersonRepository
import com.xbot.android.store.SettingsStore
import com.xbot.android.voice.VoiceRecognizer
import com.xbot.android.voice.OfflineAsrRecognizer
import com.xbot.android.vision.FaceLandmarkEngine
import com.xbot.android.vision.FaceRecognizer
import com.xbot.android.vision.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * 应用核心 ViewModel：管理 AppPhase + 主人档案/人物库 + 录入采样。
 *
 * 对应 Flutter AppController 的「阶段分流 + 主人档案 + 录入」部分（不含相机取流，
 * 相机由 MainScreenController 在 ready 阶段持有）。
 *
 * - 加载阶段（loading）：初始化 PersonRepository / OwnerProfileStore，决定进 onboarding 还是 ready。
 * - 录入采样：向导的人脸步骤调 [requestEnrollCapture]，下一帧检测到主脸即裁剪 + embed。
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "AppViewModel"
        private const val FACE_MODEL_PATH = "face_landmarker.task"
        private const val MOBILEFACENET_PATH = "mobilefacenet.tflite"
        private const val VOICE_MODEL_PATH = "voice/sherpa-onnx-3dspeaker-campplus-zh-cn-16k-common"
        private const val ASR_MODEL_PATH = "voice/sherpa-onnx-streaming-paraformer-bilingual-zh-en"
    }

    val personRepository = PersonRepository(app)
    val ownerProfileStore = OwnerProfileStore(app)
    val settingsStore = SettingsStore(app)

    /** 录入用的人脸引擎 + 身份识别（与主界面引擎独立，避免抢占）。 */
    private val enrollFaceEngine = FaceLandmarkEngine(app, FACE_MODEL_PATH)
    private val faceRecognizer = FaceRecognizer(app, MOBILEFACENET_PATH)
    /** 声纹识别（3D-Speaker CAM++）。录入采样 + 对话时识别共用同一实例。 */
    internal val voiceRecognizer = VoiceRecognizer(app, VOICE_MODEL_PATH)
    /** 整句语音识别（流式 paraformer 同步用法）。向导声纹录入「念一句话→识别文字」用。 */
    internal val asrRecognizer = OfflineAsrRecognizer(app, ASR_MODEL_PATH)

    /** 当前阶段。 */
    val phase = AtomicReference(AppPhase.LOADING)

    /** 是否可录入人脸（人脸引擎 + MobileFaceNet 均就绪）。 */
    val canEnroll: Boolean get() = enrollFaceEngine.isReady && faceRecognizer.isAvailable

    /** 是否可录入声纹（声纹模型加载成功）。 */
    val canEnrollVoice: Boolean get() = voiceRecognizer.isReady

    /** 是否可识别语音文字（ASR 模型加载成功）。 */
    val canRecognizeSpeech: Boolean get() = asrRecognizer.isReady

    /**
     * 提取一段 PCM 的声纹向量（向导录入时校验有效性用）。
     * @return 声纹向量；模型未就绪/语音过短返回 null
     */
    fun enrollVoice(pcm16: ShortArray): List<Float>? = voiceRecognizer.embed(pcm16)

    /**
     * 同步识别一段 PCM 的文字（向导声纹录入「念一句话→识别文字」用）。
     * @return 识别文字（可能为空串）；模型未就绪/异常返回 null
     */
    fun recognizeSpeech(pcm16: ShortArray): String? = asrRecognizer.recognize(pcm16)

    /**
     * 测试一段语音与已录入声纹的匹配情况（设置页「声纹测试」用）。
     *
     * @return 匹配结果；模型未就绪/语音过短/无人录入返回 matched=false、score=-1
     */
    fun testVoice(pcm16: ShortArray): VoiceTestResult {
        val recognizer = voiceRecognizer
        if (!recognizer.isReady) return VoiceTestResult(false, -1f, recognizer.matchThreshold, null)
        val emb = recognizer.embed(pcm16) ?: return VoiceTestResult(false, -1f, recognizer.matchThreshold, null)
        val people = personRepository.people
        if (people.none { it.voiceEmbeddings.isNotEmpty() }) {
            return VoiceTestResult(false, -1f, recognizer.matchThreshold, null)
        }
        val match = recognizer.identifyWithScore(emb, people)
        val matched = match.score >= recognizer.matchThreshold
        return VoiceTestResult(matched, match.score, recognizer.matchThreshold, match.person?.name)
    }

    /**
     * 更新声纹匹配阈值（设置页滑块用）：持久化 + 同步到 voiceRecognizer。
     * VA 用同一 voiceRecognizer 实例，故自动覆盖对话识别路径。
     */
    fun updateVoiceThreshold(threshold: Float) {
        settingsStore.update { it.copy(voiceMatchThreshold = threshold) }
        voiceRecognizer.matchThreshold = threshold
    }

    /** 从持久化设置同步阈值到 voiceRecognizer（初始化时调用）。 */
    private fun applyVoiceThreshold() {
        voiceRecognizer.matchThreshold = settingsStore.settings.voiceMatchThreshold
    }

    /** 声纹测试结果。 */
    data class VoiceTestResult(
        /** 是否匹配（score ≥ 阈值）。 */
        val matched: Boolean,
        /** 与最相似者的余弦相似度（-1..1）；无效输入为 -1。 */
        val score: Float,
        /** 当前阈值。 */
        val threshold: Float,
        /** 最相似者昵称；无人录入或 score<阈值 时可能为 null。 */
        val name: String?,
    )

    /** 录入采样请求：非 null 表示向导在等待一张主脸采样。 */
    private val enrollRequest = AtomicReference<EnrollRequest?>(null)

    private data class EnrollRequest(val onCapture: (EnrollCapture?) -> Unit)

    /** 初始化：加载人物库 + 主人档案，分流阶段。 */
    fun initialize() {
        try {
            personRepository.load()
            ownerProfileStore.load()
            settingsStore.load()
            phase.set(if (ownerProfileStore.isRegistered) AppPhase.READY else AppPhase.ONBOARDING)
            // 声纹模型异步加载（native 加载耗时），完成后可录入/识别。失败降级（canEnrollVoice=false）。
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    voiceRecognizer.initialize()
                    applyVoiceThreshold()  // 加载成功后同步持久化阈值
                } catch (e: Exception) {
                    Log.w(TAG, "声纹模型加载失败: ${e.message}")
                }
            }
            // 整句 ASR 异步加载（向导声纹录入识别文字用）。失败降级（canRecognizeSpeech=false）。
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    asrRecognizer.initialize()
                } catch (e: Exception) {
                    Log.w(TAG, "ASR 模型加载失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化失败: ${e.message}")
            phase.set(AppPhase.ERROR)
        }
    }

    /**
     * 请求一次人脸采样（用于录入）。下一帧检测到主脸即裁剪 + embed 回调。
     * 调用方应在向导的人脸步骤持续把当前相机帧喂给 [tryEnrollFrame]，
     * 直到回调被触发或超时。
     */
    fun requestEnrollCapture(onCapture: (EnrollCapture?) -> Unit) {
        enrollRequest.set(EnrollRequest(onCapture))
    }

    /** 取消当前等待中的录入采样请求（超时/重置时调用）。 */
    fun clearEnrollRequest() {
        enrollRequest.set(null)
    }

    /**
     * 喂入一帧 bitmap 供录入采样（向导人脸步骤调用）。
     * 检测到主脸 → 裁剪 → embed → 触发回调并清除请求。
     */
    fun tryEnrollFrame(bitmap: Bitmap) {
        val req = enrollRequest.get() ?: return
        // 诊断：记录每帧是否检测到主脸（便于定位「采不到样本」问题）。
        // 节流：仅当有 pending 请求时才检测，避免无谓推理。
        val face = enrollFaceEngine.detect(bitmap)
        if (face == null) {
            Log.d(TAG, "录入：未检测到人脸（bitmap ${bitmap.width}x${bitmap.height}）")
            return
        }
        try {
            val crop = ImageUtils.cropNormalized(bitmap, face.boundingBox)
            val embedding = faceRecognizer.embed(crop)
            Log.i(TAG, "录入：检测到人脸，embed=${if (embedding != null) "成功(${embedding.size}维)" else "失败"}")
            // 编码 jpg（质量 90）。
            val baos = java.io.ByteArrayOutputStream()
            crop.compress(Bitmap.CompressFormat.JPEG, 90, baos)
            val jpgBytes = baos.toByteArray()
            enrollRequest.set(null)
            req.onCapture(EnrollCapture(jpgBytes, embedding))
        } catch (e: Exception) {
            Log.e(TAG, "录入采样失败: ${e.message}")
        }
    }

    /**
     * 完成首次激活向导。对应 Flutter completeOnboarding。
     *
     * 若带人脸样本：构造主脸 Person（relation=owner），填 embeddings，存头像 + 存人物，
     * 回填 profile.personId / faceRegistered。本地立即生效进 ready（不阻塞于网络）。
     * 若带声纹样本（[voiceSamples]）：提取声纹向量填入同一 Person 的 voiceEmbeddings。
     * 后台 best-effort 同步 owner 到 Pophie 后端。
     *
     * @param voiceSamples 声纹录入 PCM16 样本列表（每句一段）；为空或不就绪则跳过声纹。
     */
    fun completeOnboarding(
        profile: OwnerProfile,
        faceSamples: List<EnrollCapture>?,
        voiceSamples: List<ShortArray>? = null,
    ) {
        var p = profile.copy(syncedToServer = false)
        // 先建主人 Person（人脸优先；无人脸但有声纹时也建，仅用声纹）。
        val validFaces = faceSamples?.filter { it.hasEmbedding } ?: emptyList()
        val ownerId = System.currentTimeMillis().toString()
        val person = if (validFaces.isNotEmpty()) {
            Person(
                id = ownerId,
                name = profile.nickname,
                relation = com.xbot.android.model.FamilyRelation.OWNER,
            ).also { it ->
                it.embeddings.addAll(validFaces.mapNotNull { it.embedding })
                it.avatarPath = saveAvatar(it.id, validFaces.first().jpgBytes)
            }
        } else {
            Person(
                id = ownerId,
                name = profile.nickname,
                relation = com.xbot.android.model.FamilyRelation.OWNER,
            )
        }
        // 声纹：对每段 PCM 提嵌入，非空的收集。模型未就绪时全部跳过（无声纹）。
        if (!voiceSamples.isNullOrEmpty()) {
            val voiceEmb = voiceSamples.mapNotNull { voiceRecognizer.embed(it) }
            if (voiceEmb.isNotEmpty()) person.voiceEmbeddings.addAll(voiceEmb)
        }
        // 有人脸或声纹任一才落库（避免空 Person）。
        if (validFaces.isNotEmpty() || person.voiceEmbeddings.isNotEmpty()) {
            personRepository.upsert(person)
            p = if (validFaces.isNotEmpty()) p.copy(personId = person.id, faceRegistered = true)
                 else p.copy(personId = person.id)
        }
        ownerProfileStore.save(p)
        phase.set(AppPhase.READY)
        // 后台 best-effort 同步 owner 到 Pophie（失败静默，不影响本地已生效）。
        syncOwnerToServer(p)
    }

    /**
     * 给现有主人追加声纹样本（设置页「声纹管理」入口用）。
     *
     * @param pcmSamples 新录的声纹 PCM16 样本
     * @return 实际入库的样本数；主人不存在或声纹模型未就绪返回 0
     */
    fun saveVoiceToOwner(pcmSamples: List<ShortArray>): Int {
        if (!voiceRecognizer.isReady) return 0
        val ownerId = ownerProfileStore.profile?.personId ?: return 0
        val person = personRepository.get(ownerId) ?: return 0
        val added = pcmSamples.mapNotNull { voiceRecognizer.embed(it) }
        if (added.isEmpty()) return 0
        person.voiceEmbeddings.addAll(added)
        personRepository.upsert(person)
        return added.size
    }

    /** 清除主人已录入的声纹（设置页「清除声纹」用）。返回是否清除成功。 */
    fun clearOwnerVoice(): Boolean {
        val ownerId = ownerProfileStore.profile?.personId ?: return false
        val person = personRepository.get(ownerId) ?: return false
        if (person.voiceEmbeddings.isEmpty()) return false
        person.voiceEmbeddings.clear()
        personRepository.upsert(person)
        return true
    }

    /** 主人已录入的声纹样本数（设置页展示用）。 */
    val ownerVoiceSampleCount: Int
        get() = ownerProfileStore.profile?.personId?.let { personRepository.get(it)?.voiceSampleCount } ?: 0

    /** 后台同步 owner 档案到 Pophie 后端。成功置 syncedToServer=true 并落盘。 */
    private fun syncOwnerToServer(profile: OwnerProfile) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = settingsStore.toPophieConfig()
                val client = com.xbot.android.voice.PophieClient(config)
                val ok = client.registerOwner(profile.toPophieJson())
                if (ok) {
                    val updated = profile.copy(syncedToServer = true)
                    ownerProfileStore.save(updated)
                    Log.i(TAG, "owner 档案已同步到 Pophie")
                } else {
                    Log.w(TAG, "owner 档案同步失败（后端返回非 2xx）")
                }
            } catch (e: Exception) {
                Log.w(TAG, "owner 档案同步异常: ${e.message}")
            }
        }
    }

    /** 重置主人：重新进入向导。对应 Flutter resetOwner。 */
    fun resetOwner() {
        val ownerPersonId = ownerProfileStore.profile?.personId
        ownerProfileStore.clear()
        if (ownerPersonId != null) personRepository.delete(ownerPersonId)
        phase.set(AppPhase.ONBOARDING)
    }

    private fun saveAvatar(personId: String, jpg: ByteArray): String {
        val dir = personRepository.avatarsDir()
        val file = File(dir, "$personId.jpg")
        FileOutputStream(file).use { it.write(jpg) }
        return file.absolutePath
    }

    override fun onCleared() {
        enrollFaceEngine.close()
        faceRecognizer.close()
        voiceRecognizer.release()
        super.onCleared()
    }
}

/** 工厂（AndroidViewModel 需要 Application 构造）。 */
class AppViewModelFactory(private val app: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app) as T
}
