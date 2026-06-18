class App {
    constructor() {
        this.renderer = null;
        this.currentExpression = "neutral";
        this.panelOpen = false;
        this.theme = "dark";
    }

    async init() {
        try {
            this.renderer = new Live2DRenderer("live2d-canvas");
            this.renderer.init();
            this.initZoomControls();
            this.initModelSelector();
            await this.loadModel(CURRENT_MODEL);
            this.setupEventListeners();
        } catch (error) {
            this.showError("初始化失败: " + error.message);
            this.showLoading(false);
        }
    }

    initControlPanel() {
        const panel = document.getElementById("control-panel");
        const toggle = document.getElementById("panel-toggle");
        const closeBtn = document.getElementById("panel-close");
        const backdrop = document.getElementById("panel-backdrop");
        this.panelJustOpened = false;

        this.openPanel = () => {
            if (!panel || !toggle || this.panelOpen) return;
            this.panelOpen = true;
            this.panelJustOpened = true;
            panel.classList.add("is-open");
            panel.setAttribute("aria-hidden", "false");
            toggle.setAttribute("aria-expanded", "true");
            if (backdrop) {
                backdrop.hidden = false;
                backdrop.classList.add("is-visible");
            }
            window.setTimeout(() => {
                toggle.classList.add("is-hidden");
            }, 320);
            window.setTimeout(() => {
                this.panelJustOpened = false;
            }, 400);
        };

        this.closePanel = () => {
            if (!panel || !toggle || !this.panelOpen) return;
            this.panelOpen = false;
            this.panelJustOpened = false;
            panel.classList.remove("is-open");
            panel.setAttribute("aria-hidden", "true");
            toggle.setAttribute("aria-expanded", "false");
            toggle.classList.remove("is-hidden");
            if (backdrop) {
                backdrop.classList.remove("is-visible");
                backdrop.hidden = true;
            }
        };

        const onToggle = (e) => {
            e.preventDefault();
            e.stopPropagation();
            this.openPanel();
        };

        const onClose = (e) => {
            e.preventDefault();
            e.stopPropagation();
            this.closePanel();
        };

        const onBackdropClose = (e) => {
            if (this.panelJustOpened) return;
            e.preventDefault();
            this.closePanel();
        };

        toggle?.addEventListener("click", onToggle);
        closeBtn?.addEventListener("click", onClose);
        backdrop?.addEventListener("click", onBackdropClose);

        document.addEventListener("keydown", (e) => {
            if (e.key === "Escape" && this.panelOpen) {
                this.closePanel();
            }
        });
    }

    initTheme() {
        const saved = localStorage.getItem("live2d-demo-theme");
        this.setTheme(saved === "light" ? "light" : "dark");

        document.getElementById("theme-light")?.addEventListener("click", () => {
            this.setTheme("light");
        });
        document.getElementById("theme-dark")?.addEventListener("click", () => {
            this.setTheme("dark");
        });
    }

    setTheme(theme) {
        this.theme = theme;
        document.body.classList.remove("theme-light", "theme-dark");
        document.body.classList.add(`theme-${theme}`);
        localStorage.setItem("live2d-demo-theme", theme);

        document.getElementById("theme-light")?.classList.toggle("active", theme === "light");
        document.getElementById("theme-dark")?.classList.toggle("active", theme === "dark");
    }

    async loadModel(config) {
        try {
            this.showLoading(true);
            CURRENT_MODEL = config;
            this.currentExpression = config.expressions?.[0]?.key || "neutral";
            await this.renderer.loadModel(config);
            this.createExpressionButtons();
            this.updateActiveModel(config.id);
            this.updateZoomDisplay(this.renderer.scale);
            this.showLoading(false);
        } catch (error) {
            this.showError("模型加载失败: " + error.message);
            this.showLoading(false);
        }
    }

    initModelSelector() {
        const list = document.getElementById("model-list");
        if (!list) return;

        list.innerHTML = "";

        MODEL_LIST.forEach((model) => {
            const option = document.createElement("button");
            option.type = "button";
            option.className = `model-option${model.id === CURRENT_MODEL.id ? " active" : ""}`;
            option.dataset.modelId = model.id;
            option.innerHTML = `
                <span class="model-option-name">${model.name}</span>
                <span class="model-option-desc">${model.description}</span>
            `;
            option.addEventListener("click", () => this.switchModel(model));
            list.appendChild(option);
        });
    }

    async switchModel(model) {
        if (model.id === CURRENT_MODEL.id) return;
        await this.loadModel(model);
    }

    updateActiveModel(modelId) {
        document.querySelectorAll(".model-option").forEach((option) => {
            option.classList.toggle("active", option.dataset.modelId === modelId);
        });
    }

    initZoomControls() {
        document.getElementById("zoom-in")?.addEventListener("click", () => {
            this.renderer?.zoomBy(1.08);
        });
        document.getElementById("zoom-out")?.addEventListener("click", () => {
            this.renderer?.zoomBy(1 / 1.08);
        });
        document.getElementById("zoom-reset")?.addEventListener("click", () => {
            this.renderer?.resetTransform();
        });
    }

    updateZoomDisplay(scale) {
        const zoomValue = document.getElementById("zoom-value");
        if (zoomValue) {
            zoomValue.textContent = Math.round(scale * 100) + "%";
        }
    }

    createExpressionButtons() {
        const container = document.getElementById("emotion-buttons");
        if (!container) return;

        container.innerHTML = "";
        const expressions = CURRENT_MODEL.expressions || [];

        expressions.forEach((expression, index) => {
            const button = document.createElement("button");
            button.type = "button";
            button.className = "emotion-btn";
            button.dataset.expression = expression.key;
            button.title = `快捷键 ${index + 1}`;
            button.innerHTML = `
                <span class="emotion-icon">${expression.icon || "✨"}</span>
                <span class="emotion-label">${expression.label}</span>
            `;
            button.addEventListener("click", () => this.switchExpression(expression.key));
            container.appendChild(button);
        });

        this.updateActiveButton(this.currentExpression);
    }

    switchExpression(expressionKey) {
        if (!this.renderer?.isLoaded) return;
        this.renderer.setExpressionByKey(expressionKey, CURRENT_MODEL.expressions);
        this.currentExpression = expressionKey;
        this.updateActiveButton(expressionKey);
    }

    updateActiveButton(expressionKey) {
        document.querySelectorAll(".emotion-btn").forEach((btn) => {
            btn.classList.toggle("active", btn.dataset.expression === expressionKey);
        });
    }

    setupEventListeners() {
        document.addEventListener("keydown", (e) => {
            const expressions = CURRENT_MODEL.expressions || [];
            const index = parseInt(e.key, 10) - 1;
            if (index >= 0 && index < expressions.length) {
                this.switchExpression(expressions[index].key);
            }
            if (e.key === "r" || e.key === "R") {
                this.renderer?.resetTransform();
            }
        });
    }

    showLoading(show) {
        const overlay = document.getElementById("loading-overlay");
        if (!overlay) return;
        overlay.hidden = !show;
    }

    showError(message) {
        const container = document.getElementById("error-container");
        if (!container) return;
        container.textContent = message;
        container.hidden = false;
        setTimeout(() => {
            container.hidden = true;
        }, 5000);
        console.error(message);
    }
}

function updateZoomDisplay(scale) {
    window.app?.updateZoomDisplay(scale);
}

document.addEventListener("DOMContentLoaded", () => {
    window.app = new App();
    window.app.initControlPanel();
    window.app.initTheme();
    window.app.init();
});
