package com.xbot.xbot.voice;

import android.util.Base64;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * HTTP/WebSocket client for the Pophie backend.
 */
public class PophieClient {
    private static final String TAG = "PophieClient";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient http;
    private PophieConfig config;

    public PophieClient() {
        this(new PophieConfig());
    }

    public PophieClient(PophieConfig config) {
        this.config = config;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public PophieConfig getConfig() {
        return config;
    }

    public void setConfig(PophieConfig config) {
        this.config = config;
    }

    @Nullable
    public String getSessionId() {
        return config.sessionId;
    }

    public void setSessionId(@Nullable String sessionId) {
        config.sessionId = sessionId;
    }

    /** Multimodal perception context (no object-detection fields). */
    public static class PophiePerception {
        @Nullable public String facialExpression;
        @Nullable public String identity;
        @Nullable public String gestureType;
        @Nullable public String touch;
        @Nullable public String scene;

        public boolean isEmpty() {
            return isBlank(facialExpression)
                    && isBlank(identity)
                    && isBlank(gestureType)
                    && isBlank(touch)
                    && isBlank(scene);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new HashMap<>();
            if (!isBlank(facialExpression)) {
                m.put("facial_expression", facialExpression);
            }
            if (!isBlank(identity)) {
                m.put("identity", identity);
            }
            if (!isBlank(gestureType)) {
                Map<String, Object> gesture = new HashMap<>();
                gesture.put("type", gestureType);
                m.put("gesture", gesture);
            }
            if (!isBlank(touch)) {
                m.put("touch", touch);
            }
            if (!isBlank(scene)) {
                m.put("scene", scene);
            }
            return m;
        }

        private static boolean isBlank(@Nullable String s) {
            return s == null || s.trim().isEmpty();
        }
    }

    public static class PophieChatResult {
        public final String text;
        public final String facialExpression;
        public final String robotState;
        public final String sttText;
        @Nullable public final byte[] audioBytes;
        @Nullable public final String audioFormat;
        @Nullable public final Map<String, Object> voice;
        @Nullable public final String sessionId;

        public PophieChatResult(
                String text,
                String facialExpression,
                String robotState,
                String sttText,
                @Nullable byte[] audioBytes,
                @Nullable String audioFormat,
                @Nullable Map<String, Object> voice,
                @Nullable String sessionId) {
            this.text = text != null ? text : "";
            this.facialExpression = facialExpression != null ? facialExpression : "neutral";
            this.robotState = robotState != null ? robotState : "idle";
            this.sttText = sttText != null ? sttText : "";
            this.audioBytes = audioBytes;
            this.audioFormat = audioFormat;
            this.voice = voice;
            this.sessionId = sessionId;
        }

        public boolean isSilent() {
            return text.trim().isEmpty();
        }
    }

    public enum SttEventType {
        META, READY, PARTIAL, FINAL_RESULT, SESSION_END, ERROR, CLOSED
    }

    public static class SttEvent {
        public final SttEventType type;
        @Nullable public final String text;
        @Nullable public final Map<String, Object> voice;
        @Nullable public final String message;
        @Nullable public final Integer silenceCommitMs;
        @Nullable public final Integer conversationIdleSec;

        public SttEvent(SttEventType type) {
            this(type, null, null, null, null, null);
        }

        public SttEvent(
                SttEventType type,
                @Nullable String text,
                @Nullable Map<String, Object> voice,
                @Nullable String message,
                @Nullable Integer silenceCommitMs,
                @Nullable Integer conversationIdleSec) {
            this.type = type;
            this.text = text;
            this.voice = voice;
            this.message = message;
            this.silenceCommitMs = silenceCommitMs;
            this.conversationIdleSec = conversationIdleSec;
        }
    }

    public interface SttEventListener {
        void onEvent(SttEvent event);
    }

    public interface TtsStreamCallbacks {
        void onMeta(String format, int sampleRate);

        void onChunk(byte[] pcm);

        void onDone(@Nullable Integer firstPacketMs);

        void onError(String message);
    }

    /** POST /api/chat */
    public PophieChatResult chat(
            @Nullable String text,
            @Nullable byte[] wavBytes,
            @Nullable PophiePerception perception,
            @Nullable String userId,
            @Nullable Boolean skipTts) throws IOException {
        Map<String, Object> input = buildInput(text, wavBytes, perception, skipTts);
        Map<String, Object> body = buildChatBody(input, userId);

        Request request = new Request.Builder()
                .url(trimBaseUrl(config.baseUrl) + "/api/chat")
                .post(RequestBody.create(GSON.toJson(body), JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST /api/chat failed: HTTP " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("POST /api/chat empty body");
            }
            Map<String, Object> data = parseMap(responseBody.string());
            if (data == null) {
                throw new IOException("POST /api/chat invalid JSON");
            }
            Object sid = data.get("session_id");
            if (sid instanceof String) {
                config.sessionId = (String) sid;
            }
            Map<String, Object> output = asMap(data.get("output"));
            Map<String, Object> stt = asMap(data.get("stt"));
            byte[] audioBytes = null;
            String audioFormat = null;
            Map<String, Object> audio = asMap(output.get("audio"));
            if (audio != null && audio.get("data") instanceof String) {
                audioBytes = Base64.decode((String) audio.get("data"), Base64.DEFAULT);
                Object fmt = audio.get("format");
                audioFormat = fmt instanceof String ? (String) fmt : "wav";
            }
            Map<String, Object> voice = asMap(output.get("voice"));
            return new PophieChatResult(
                    stringVal(output.get("text")),
                    stringVal(output.get("facial_expression")),
                    stringVal(output.get("robot_state")),
                    stt != null ? stringVal(stt.get("text")) : "",
                    audioBytes,
                    audioFormat,
                    voice,
                    config.sessionId);
        }
    }

    /** Streaming TTS: POST /api/tts/stream (NDJSON). */
    public void ttsStream(
            String text,
            @Nullable Map<String, Object> voice,
            @Nullable String voiceId,
            TtsStreamCallbacks callbacks) throws IOException {
        Map<String, Object> body = new HashMap<>();
        body.put("text", text);
        String vid = voiceId != null ? voiceId : config.voiceId;
        if (vid != null && !vid.isEmpty()) {
            body.put("voice_id", vid);
        }
        if (voice != null && !voice.isEmpty()) {
            body.put("voice", voice);
        }

        Request request = new Request.Builder()
                .url(trimBaseUrl(config.baseUrl) + "/api/tts/stream")
                .post(RequestBody.create(GSON.toJson(body), JSON))
                .header("Accept", "application/x-ndjson")
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST /api/tts/stream failed: HTTP " + response.code());
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("POST /api/tts/stream empty body");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                handleTtsNdjsonLine(line, callbacks);
            }
        }
    }

    private void handleTtsNdjsonLine(String line, TtsStreamCallbacks callbacks) throws IOException {
        Map<String, Object> obj = parseMap(line);
        if (obj == null) {
            return;
        }
        String type = stringVal(obj.get("type"));
        switch (type) {
            case "meta":
                callbacks.onMeta(
                        stringVal(obj.get("format"), "pcm"),
                        intVal(obj.get("sample_rate"), 22050));
                break;
            case "chunk":
                Object data = obj.get("data");
                if (data instanceof String && !((String) data).isEmpty()) {
                    callbacks.onChunk(Base64.decode((String) data, Base64.DEFAULT));
                }
                break;
            case "done":
                callbacks.onDone(intVal(obj.get("first_packet_ms"), null));
                break;
            case "error":
                String msg = stringVal(obj.get("message"), "未知错误");
                callbacks.onError(msg);
                throw new IOException("/api/tts/stream 合成失败: " + msg);
            default:
                Log.w(TAG, "ttsStream unknown type: " + type);
                break;
        }
    }

    /** Open WebSocket STT session at /api/stt/stream. */
    public SttStreamSession openSttStream(SttEventListener listener) {
        String url = toWsUrl(config.baseUrl);
        SttStreamSession session = new SttStreamSession(listener);
        Request request = new Request.Builder().url(url).build();
        WebSocket ws = http.newWebSocket(request, session);
        session.attach(ws);
        return session;
    }

    public static String toWsUrl(String baseUrl) {
        String b = trimBaseUrl(baseUrl);
        if (b.startsWith("https://")) {
            return "wss://" + b.substring("https://".length()) + "/api/stt/stream";
        }
        if (b.startsWith("http://")) {
            return "ws://" + b.substring("http://".length()) + "/api/stt/stream";
        }
        return "ws://" + b + "/api/stt/stream";
    }

    public static byte[] pcm16ToWav(byte[] pcm) {
        return pcm16ToWav(pcm, 16000, 1, 16);
    }

    public static byte[] pcm16ToWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataLen = pcm.length;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataLen);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataLen);
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataLen);
        out.write(header.array(), 0, 44);
        out.write(pcm, 0, dataLen);
        return out.toByteArray();
    }

    public void shutdown() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    private Map<String, Object> buildInput(
            @Nullable String text,
            @Nullable byte[] wavBytes,
            @Nullable PophiePerception perception,
            @Nullable Boolean skipTts) {
        Map<String, Object> input = new HashMap<>();
        input.put("text", text != null ? text : "");
        if (wavBytes != null && wavBytes.length > 0) {
            Map<String, Object> audio = new HashMap<>();
            audio.put("format", "wav");
            audio.put("encoding", "base64");
            audio.put("sample_rate", 16000);
            audio.put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP));
            input.put("audio", audio);
        }
        if (perception != null && !perception.isEmpty()) {
            input.put("perception", perception.toJson());
        }
        if (config.voiceId != null && !config.voiceId.isEmpty()) {
            input.put("voice_id", config.voiceId);
        }
        if (skipTts != null) {
            input.put("skip_tts", skipTts);
        }
        return input;
    }

    private Map<String, Object> buildChatBody(Map<String, Object> input, @Nullable String userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("robot_id", config.robotId);
        if (config.sessionId != null) {
            body.put("session_id", config.sessionId);
        }
        if (userId != null && !userId.isEmpty()) {
            body.put("user_id", userId);
        }
        body.put("input", input);
        return body;
    }

    private static String trimBaseUrl(String baseUrl) {
        String b = baseUrl != null ? baseUrl.trim() : "";
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b;
    }

    @Nullable
    private static Map<String, Object> parseMap(String json) {
        try {
            return GSON.fromJson(json, MAP_TYPE);
        } catch (JsonSyntaxException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static Map<String, Object> asMap(@Nullable Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        return null;
    }

    private static String stringVal(@Nullable Object v) {
        return stringVal(v, "");
    }

    private static String stringVal(@Nullable Object v, String fallback) {
        return v instanceof String ? (String) v : fallback;
    }

    @Nullable
    private static Integer intVal(@Nullable Object v, @Nullable Integer fallback) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return fallback;
    }

    /** WebSocket client for streaming STT. */
    public class SttStreamSession extends WebSocketListener {
        private final SttEventListener listener;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        @Nullable private WebSocket webSocket;

        SttStreamSession(SttEventListener listener) {
            this.listener = listener;
        }

        void attach(WebSocket webSocket) {
            this.webSocket = webSocket;
        }

        public void start(int sampleRate, String language, @Nullable Boolean turnDetection) {
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "start");
            msg.put("sample_rate", sampleRate);
            msg.put("language", language);
            if (turnDetection != null) {
                msg.put("turn_detection", turnDetection);
            }
            send(msg);
        }

        public void sendChunk(byte[] pcm16) {
            if (closed.get() || pcm16.length == 0) {
                return;
            }
            Map<String, Object> msg = new HashMap<>();
            msg.put("type", "chunk");
            msg.put("data", Base64.encodeToString(pcm16, Base64.NO_WRAP));
            send(msg);
        }

        public void commit() {
            send(Map.of("type", "commit"));
        }

        public void sendEnd() {
            send(Map.of("type", "end"));
        }

        public void close(boolean sendEndFrame) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (sendEndFrame) {
                sendEnd();
            }
            if (webSocket != null) {
                webSocket.close(1000, "client close");
            }
            emit(new SttEvent(SttEventType.CLOSED));
        }

        private void send(Map<String, Object> msg) {
            if (closed.get() || webSocket == null) {
                return;
            }
            webSocket.send(GSON.toJson(msg));
        }

        private void emit(SttEvent event) {
            if (!closed.get() || event.type == SttEventType.CLOSED) {
                listener.onEvent(event);
            }
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            Log.d(TAG, "STT WebSocket connected");
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            Map<String, Object> obj = parseMap(text);
            if (obj == null) {
                return;
            }
            String type = stringVal(obj.get("type"));
            switch (type) {
                case "meta":
                    emit(new SttEvent(
                            SttEventType.META,
                            null,
                            null,
                            null,
                            intVal(obj.get("silence_commit_ms"), null),
                            intVal(obj.get("conversation_idle_sec"), null)));
                    break;
                case "ready":
                    emit(new SttEvent(SttEventType.READY));
                    break;
                case "partial":
                    emit(new SttEvent(
                            SttEventType.PARTIAL,
                            stringVal(obj.get("text")),
                            null,
                            null,
                            null,
                            null));
                    break;
                case "final":
                    emit(new SttEvent(
                            SttEventType.FINAL_RESULT,
                            stringVal(obj.get("text")),
                            asMap(obj.get("voice")),
                            null,
                            null,
                            null));
                    break;
                case "session_end":
                    emit(new SttEvent(
                            SttEventType.SESSION_END,
                            null,
                            null,
                            stringVal(obj.get("message")),
                            null,
                            intVal(obj.get("conversation_idle_sec"), null)));
                    break;
                case "error":
                    emit(new SttEvent(
                            SttEventType.ERROR,
                            null,
                            null,
                            stringVal(obj.get("message")),
                            null,
                            null));
                    break;
                default:
                    Log.w(TAG, "STT unknown frame: " + type);
                    break;
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            // protocol uses text frames only
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, @Nullable Response response) {
            Log.e(TAG, "STT WebSocket failure", t);
            emit(new SttEvent(
                    SttEventType.ERROR,
                    null,
                    null,
                    t.getMessage(),
                    null,
                    null));
            closed.set(true);
            emit(new SttEvent(SttEventType.CLOSED));
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            closed.set(true);
            emit(new SttEvent(SttEventType.CLOSED));
        }
    }
}
