const MODEL_LIST = [
    {
        id: "monv",
        name: "魔女",
        description: "本地模型 · 12 种表情",
        url: "./models/monv/魔女.model3.json",
        // 独立 Demo（带控制面板）默认布局
        kScale: 0.45,
        initialXshift: 0,
        initialYshift: 250,
        // Flutter App 全屏内嵌布局：放大并以脸部为视觉中心
        embeddedLayout: {
            kScale: 3.2,
            initialXshift: 0,
            faceFocusOffset: 0.06,
        },
        idleMotionGroupName: "Idle",
        defaultExpression: "calm",
        expressions: [
            { key: "calm", value: "calm", label: "平静", icon: "😌" },
            { key: "anger", value: "anger", label: "愤怒", icon: "😠" },
            { key: "disgust", value: "disgust", label: "厌恶", icon: "🤢" },
            { key: "fear", value: "fear", label: "恐惧", icon: "😨" },
            { key: "joy", value: "joy", label: "快乐", icon: "😊" },
            { key: "sadness", value: "sadness", label: "悲伤", icon: "😢" },
            { key: "surprise", value: "surprise", label: "惊讶", icon: "😲" },
            { key: "contempt", value: "contempt", label: "轻蔑", icon: "😏" },
            { key: "close_eyes", value: "close_eyes", label: "隐私闭眼", icon: "😑" },
            { key: "thinking", value: "thinking", label: "思考中", icon: "🤔", animate: "thinking" },
            { key: "speaking", value: "speaking", label: "说话中", icon: "💬", animate: "speaking" },
            { key: "singing", value: "singing", label: "唱歌中", icon: "🎤", animate: "singing" }
        ],
        tapMotions: {}
    }
];

let CURRENT_MODEL = MODEL_LIST[0]; // 魔女模型
