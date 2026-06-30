package com.xbot.xbot.logging;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Daily JSONL persona log files (ported from {@code lib/services/persona_logger.dart}).
 */
public class PersonaLogger {
    private static final String TAG = "PersonaLogger";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final Context appContext;
    private final int memoryCapacity;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final MutableLiveData<List<PersonaLogEntry>> recentLive =
            new MutableLiveData<>(Collections.emptyList());
    private final List<PersonaLogEntry> recent = new ArrayList<>();

    @Nullable private File dir;
    private volatile boolean initialized;
    public volatile boolean enabled = true;

    public PersonaLogger(Context context) {
        this(context, 300);
    }

    public PersonaLogger(Context context, int memoryCapacity) {
        this.appContext = context.getApplicationContext();
        this.memoryCapacity = memoryCapacity;
    }

    public LiveData<List<PersonaLogEntry>> getRecent() {
        return recentLive;
    }

    @Nullable
    public String getDirectoryPath() {
        return dir != null ? dir.getAbsolutePath() : null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void initialize(Runnable onComplete) {
        io.execute(() -> {
            if (initialized) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            try {
                File root = new File(appContext.getFilesDir(), "persona_logs");
                if (!root.exists() && !root.mkdirs()) {
                    Log.e(TAG, "failed to create log dir");
                }
                dir = root;
                initialized = true;
                String today = dateKey(new Date());
                List<PersonaLogEntry> todayEntries = readDateSync(today);
                synchronized (recent) {
                    recent.clear();
                    int from = Math.max(0, todayEntries.size() - memoryCapacity);
                    recent.addAll(todayEntries.subList(from, todayEntries.size()));
                    recentLive.postValue(Collections.unmodifiableList(new ArrayList<>(recent)));
                }
                Log.d(TAG, "dir=" + root.getAbsolutePath());
            } catch (Exception e) {
                Log.e(TAG, "init failed", e);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public void log(PersonaLogEntry entry) {
        if (!enabled || !initialized || dir == null) {
            return;
        }
        io.execute(() -> {
            synchronized (recent) {
                recent.add(entry);
                while (recent.size() > memoryCapacity) {
                    recent.remove(0);
                }
                recentLive.postValue(Collections.unmodifiableList(new ArrayList<>(recent)));
            }
            String line = GSON.toJson(entry.toJson()) + '\n';
            String key = dateKey(entry.timestamp);
            File file = new File(dir, key + ".jsonl");
            try (FileWriter writer = new FileWriter(file, true)) {
                writer.write(line);
            } catch (IOException e) {
                Log.e(TAG, "write failed", e);
            }
        });
    }

    public void availableDates(Callback<List<String>> callback) {
        io.execute(() -> {
            List<String> dates = listDatesSync();
            if (callback != null) {
                callback.onResult(dates);
            }
        });
    }

    public void readDate(String dateKey, Callback<List<PersonaLogEntry>> callback) {
        io.execute(() -> {
            List<PersonaLogEntry> entries = readDateSync(dateKey);
            if (callback != null) {
                callback.onResult(entries);
            }
        });
    }

    public void deleteDate(String dateKey, Runnable onComplete) {
        io.execute(() -> {
            if (dir == null) {
                if (onComplete != null) {
                    onComplete.run();
                }
                return;
            }
            File file = new File(dir, dateKey + ".jsonl");
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "delete failed: " + file);
            }
            String today = dateKey(new Date());
            if (dateKey.equals(today)) {
                synchronized (recent) {
                    recent.clear();
                    recentLive.postValue(Collections.emptyList());
                }
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    private List<String> listDatesSync() {
        if (dir == null || !dir.exists()) {
            return Collections.emptyList();
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jsonl"));
        if (files == null) {
            return Collections.emptyList();
        }
        List<String> dates = new ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            dates.add(name.substring(0, name.length() - ".jsonl".length()));
        }
        Collections.sort(dates, Collections.reverseOrder());
        return dates;
    }

    private List<PersonaLogEntry> readDateSync(String dateKey) {
        if (dir == null) {
            return Collections.emptyList();
        }
        File file = new File(dir, dateKey + ".jsonl");
        if (!file.exists()) {
            return Collections.emptyList();
        }
        List<PersonaLogEntry> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                try {
                    Map<String, Object> json = GSON.fromJson(line, MAP_TYPE);
                    if (json != null) {
                        out.add(PersonaLogEntry.fromJson(json));
                    }
                } catch (JsonSyntaxException ignored) {
                    // skip corrupt line
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "read " + dateKey + " failed", e);
        }
        return out;
    }

    public static String dateKey(Date dt) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(dt);
    }

    public void release() {
        io.shutdownNow();
    }

    public interface Callback<T> {
        void onResult(T value);
    }

    /** One persona log record. */
    public static class PersonaLogEntry {
        public final Date timestamp;
        public final String type;
        @Nullable public final String person;
        @Nullable public final String expression;
        @Nullable public final String gesture;
        @Nullable public final String scene;
        @Nullable public final String userText;
        @Nullable public final String replyText;
        @Nullable public final String robotState;
        @Nullable public final String note;
        public final Map<String, Object> extra;

        public PersonaLogEntry(
                Date timestamp,
                String type,
                @Nullable String person,
                @Nullable String expression,
                @Nullable String gesture,
                @Nullable String scene,
                @Nullable String userText,
                @Nullable String replyText,
                @Nullable String robotState,
                @Nullable String note,
                Map<String, Object> extra) {
            this.timestamp = timestamp;
            this.type = type;
            this.person = person;
            this.expression = expression;
            this.gesture = gesture;
            this.scene = scene;
            this.userText = userText;
            this.replyText = replyText;
            this.robotState = robotState;
            this.note = note;
            this.extra = extra != null ? extra : Collections.emptyMap();
        }

        public Map<String, Object> toJson() {
            Map<String, Object> map = new HashMap<>();
            map.put("ts", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(timestamp));
            map.put("type", type);
            if (person != null) map.put("person", person);
            if (expression != null) map.put("expression", expression);
            if (gesture != null) map.put("gesture", gesture);
            if (scene != null) map.put("scene", scene);
            if (userText != null) map.put("userText", userText);
            if (replyText != null) map.put("replyText", replyText);
            if (robotState != null) map.put("robotState", robotState);
            if (note != null) map.put("note", note);
            if (!extra.isEmpty()) map.put("extra", extra);
            return map;
        }

        public static PersonaLogEntry fromJson(Map<String, Object> json) {
            Date ts = new Date();
            Object tsObj = json.get("ts");
            if (tsObj instanceof String) {
                try {
                    ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).parse((String) tsObj);
                } catch (Exception ignored) {
                }
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> extra = json.get("extra") instanceof Map
                    ? (Map<String, Object>) json.get("extra")
                    : Collections.emptyMap();
            return new PersonaLogEntry(
                    ts,
                    stringVal(json.get("type"), "event"),
                    stringOrNull(json.get("person")),
                    stringOrNull(json.get("expression")),
                    stringOrNull(json.get("gesture")),
                    stringOrNull(json.get("scene")),
                    stringOrNull(json.get("userText")),
                    stringOrNull(json.get("replyText")),
                    stringOrNull(json.get("robotState")),
                    stringOrNull(json.get("note")),
                    extra);
        }

        private static String stringVal(@Nullable Object v, String fallback) {
            return v instanceof String ? (String) v : fallback;
        }

        @Nullable
        private static String stringOrNull(@Nullable Object v) {
            return v instanceof String ? (String) v : null;
        }
    }
}
