/**
 * Flutter WebView 桥接：与默认桌面 window.__face API 对齐。
 */
(function () {
    const STATE_MAP = {
        idle: { key: "calm" },
        gazing: { key: "calm" },
        listening: { key: "speaking", animate: "speaking" },
        waking: { key: "speaking", animate: "speaking" },
        speaking: { key: "speaking", animate: "speaking" },
        thinking: { key: "thinking", animate: "thinking" },
        happy: { key: "joy" },
        confused: { key: "surprise" },
        sleepy: { key: "close_eyes" },
        sleeping: { key: "close_eyes" },
    };

    const VOICE_STATES = new Set(["listening", "waking", "speaking"]);

    let currentState = "idle";
    let pendingEmotionHappy = false;

    function getRenderer() {
        return window.app?.renderer || null;
    }

    function applySpeakingVoice() {
        const renderer = getRenderer();
        if (!renderer?.isLoaded || !CURRENT_MODEL?.expressions) return;
        renderer.setExpressionByKey("speaking", CURRENT_MODEL.expressions);
        renderer.startExpressionAnimation("speaking");
    }

    function applyExpression(state) {
        const renderer = getRenderer();
        if (!renderer?.isLoaded || !CURRENT_MODEL?.expressions) return;

        const mapping = STATE_MAP[state] || STATE_MAP.idle;
        renderer.setExpressionByKey(mapping.key, CURRENT_MODEL.expressions);
        if (mapping.animate) {
            renderer.startExpressionAnimation(mapping.animate);
        }
    }

    window.__face = {
        setState(state) {
            if (!state || state === currentState) return;
            currentState = state;
            pendingEmotionHappy = false;

            if (VOICE_STATES.has(state)) {
                applySpeakingVoice();
                return;
            }

            // TTS 播报时 Flutter 发 happy，同一批 JS 会紧跟 setListeningLoudness。
            // 延迟一帧：若无音量则视为情绪高兴(joy)，有音量则走说话态。
            if (state === "happy") {
                pendingEmotionHappy = true;
                requestAnimationFrame(() => {
                    if (!pendingEmotionHappy || currentState !== "happy") return;
                    pendingEmotionHappy = false;
                    applyExpression("happy");
                });
                return;
            }

            applyExpression(state);
        },

        setGazeTarget(x, y) {
            const renderer = getRenderer();
            if (!renderer) return;
            renderer.setGazeTarget(x, y);
        },

        setListeningLoudness(value) {
            const renderer = getRenderer();
            if (!renderer) return;
            const loud = Math.max(0, Math.min(1, Number(value) || 0));
            pendingEmotionHappy = false;

            if (loud > 0) {
                applySpeakingVoice();
            }
            renderer.setMouthOpen(loud);
        },
    };

    const params = new URLSearchParams(window.location.search);
    if (params.get("embedded") === "1") {
        document.documentElement.classList.add("embedded");
        document.addEventListener("DOMContentLoaded", () => {
            ["panel-toggle", "control-panel", "panel-backdrop"].forEach((id) => {
                const el = document.getElementById(id);
                if (el) el.hidden = true;
            });
        });
    }
})();
