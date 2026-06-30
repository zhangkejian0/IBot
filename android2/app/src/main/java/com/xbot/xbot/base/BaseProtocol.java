package com.xbot.xbot.base;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base-controller JSON line protocol (ported from {@code lib/services/base/base_protocol.dart}).
 *
 * <p>Each frame is a single UTF-8 JSON line terminated by {@code '\n'} (0x0A).
 */
public class BaseProtocol {
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private final List<Byte> rxBuf = new ArrayList<>();

    /** Encode a command as {@code json + '\n'} bytes. */
    public byte[] encode(String cmd, Map<String, Object> params) {
        Map<String, Object> json = new HashMap<>();
        json.put("cmd", cmd);
        if (params != null) {
            json.putAll(params);
        }
        String text = GSON.toJson(json) + '\n';
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Feed received bytes; returns complete frames parsed from the buffer.
     * Malformed frames are skipped.
     */
    public List<Map<String, Object>> feed(byte[] bytes) {
        return feed(bytes, 0, bytes.length);
    }

    public List<Map<String, Object>> feed(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            rxBuf.add(bytes[i]);
        }
        List<Map<String, Object>> frames = new ArrayList<>();
        while (true) {
            int nl = indexOfNewline();
            if (nl < 0) {
                break;
            }
            byte[] raw = new byte[nl];
            for (int i = 0; i < nl; i++) {
                raw[i] = rxBuf.remove(0);
            }
            rxBuf.remove(0); // '\n'
            String text = new String(raw, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                continue;
            }
            try {
                Map<String, Object> obj = GSON.fromJson(text, MAP_TYPE);
                if (obj != null) {
                    frames.add(obj);
                }
            } catch (JsonSyntaxException ignored) {
                // skip bad frame
            }
        }
        return frames;
    }

    public void reset() {
        rxBuf.clear();
    }

    private int indexOfNewline() {
        for (int i = 0; i < rxBuf.size(); i++) {
            if (rxBuf.get(i) == 0x0A) {
                return i;
            }
        }
        return -1;
    }
}
