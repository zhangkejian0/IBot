package com.xbot.xbot.web;

import androidx.annotation.Nullable;

/**
 * Builds {@code evaluateJavascript} snippets for {@code window.__face} bridge.
 */
public final class FaceJsBridge {
    private FaceJsBridge() {}

    public static String setState(@Nullable String stateWireName) {
        if (stateWireName == null || stateWireName.isEmpty()) {
            return "";
        }
        return "f.setState('" + escapeJs(stateWireName) + "');";
    }

    public static String setGazeTarget(double x, double y) {
        return "f.setGazeTarget(" + format(x) + "," + format(y) + ");";
    }

    public static String setListeningLoudness(double level) {
        return "f.setListeningLoudness(" + format(level) + ");";
    }

    /** Merged push script with optional state, gaze, and loudness segments. */
    public static String buildPushScript(
            @Nullable String stateJs,
            @Nullable String gazeJs,
            @Nullable String loudnessJs) {
        String state = stateJs != null ? stateJs : "";
        String gaze = gazeJs != null ? gazeJs : "";
        String loudness = loudnessJs != null ? loudnessJs : "";
        if (state.isEmpty() && gaze.isEmpty() && loudness.isEmpty()) {
            return "";
        }
        return "var f=window.__face;if(f){" + state + gaze + loudness + "}";
    }

    public static String buildVoiceEndScript() {
        return buildPushScript(
                setState("idle"),
                null,
                setListeningLoudness(0));
    }

    private static String format(double v) {
        return String.format(java.util.Locale.US, "%.3f", v);
    }

    private static String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }
}
