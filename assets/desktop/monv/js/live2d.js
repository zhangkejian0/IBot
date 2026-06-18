class Live2DRenderer {
    constructor(canvasId) {
        this.canvas = document.getElementById(canvasId);
        this.app = null;
        this.modelRoot = null;
        this.model = null;
        this.isLoaded = false;
        this.scale = 1;
        this.currentConfig = null;
        this.lastPinchDistance = 0;
        this.saveScaleTimer = null;
        this.pendingZoomDisplay = null;
        this.zoomAnimId = null;
        this.expressionAnimType = null;
        this.expressionAnimStart = 0;
        this.expressionAnimHandler = null;
        // 跟随相关
        this.followTarget = { x: 0, y: 0 };   // 目标归一化坐标 (-1 ~ 1)
        this.followCurrent = { x: 0, y: 0 };  // 当前平滑后的坐标
        this.followHandler = null;
    }

    init() {
        this.app = new PIXI.Application({
            view: this.canvas,
            autoStart: true,
            resizeTo: this.canvas.parentElement,
            backgroundAlpha: 0,
            antialias: true,
            resolution: window.devicePixelRatio || 1,
            autoDensity: true
        });

        this.modelRoot = new PIXI.Container();
        this.app.stage.addChild(this.modelRoot);

        if (PIXI.live2d?.Live2DModel?.registerTicker && PIXI.Ticker) {
            PIXI.live2d.Live2DModel.registerTicker(PIXI.Ticker);
        }

        window.addEventListener("resize", () => this.onResize());
        this.setupInteraction();
    }

    setupInteraction() {
        this.canvas.addEventListener("wheel", (e) => {
            e.preventDefault();
            const factor = Math.exp(-this.normalizeWheelDelta(e) * 0.001);
            this.zoomAt(e.clientX, e.clientY, factor);
        }, { passive: false });

        // 鼠标跟随
        document.addEventListener("mousemove", (e) => {
            this.updateFollowTarget(e.clientX, e.clientY);
        });

        // 触摸跟随（单指）
        this.canvas.addEventListener("touchmove", (e) => {
            if (e.touches.length === 1) {
                this.updateFollowTarget(e.touches[0].clientX, e.touches[0].clientY);
            }
        }, { passive: true });

        this.canvas.addEventListener("touchstart", (e) => {
            if (e.touches.length === 1) {
                this.updateFollowTarget(e.touches[0].clientX, e.touches[0].clientY);
            } else if (e.touches.length === 2) {
                this.lastPinchDistance = this.getPinchDistance(e.touches);
            }
        }, { passive: true });

        this.canvas.addEventListener("touchmove", (e) => {
            if (e.touches.length === 2) {
                e.preventDefault();
                const distance = this.getPinchDistance(e.touches);
                if (this.lastPinchDistance > 0) {
                    const center = this.getPinchCenter(e.touches);
                    this.zoomAt(center.x, center.y, distance / this.lastPinchDistance);
                }
                this.lastPinchDistance = distance;
            }
        }, { passive: false });

        this.canvas.addEventListener("touchend", (e) => {
            if (e.touches.length < 2) {
                this.lastPinchDistance = 0;
            }
        });
    }

    updateFollowTarget(clientX, clientY) {
        const rect = this.canvas.getBoundingClientRect();
        // 归一化到 -1 ~ 1，中心为 (0, 0)
        this.followTarget.x = ((clientX - rect.left) / rect.width) * 2 - 1;
        this.followTarget.y = ((clientY - rect.top) / rect.height) * 2 - 1;
    }

    /** Flutter 桥接：直接设置注视目标（归一化 -1..1）。 */
    setGazeTarget(x, y) {
        this.followTarget.x = x;
        this.followTarget.y = y;
    }

    /** Flutter 桥接：语音活跃时驱动嘴部张合。 */
    setMouthOpen(value) {
        const loud = Math.max(0, Math.min(1, value));
        this.setCoreParameter("ParamMouthOpenY", 0.05 + loud * 0.85);
    }

    startFollowAnimation() {
        this.stopFollowAnimation();
        this.followHandler = () => this.tickFollowAnimation();
        const priority = PIXI.UPDATE_PRIORITY?.LOW ?? -25;
        PIXI.Ticker.shared.add(this.followHandler, undefined, priority);
    }

    stopFollowAnimation() {
        if (this.followHandler) {
            const priority = PIXI.UPDATE_PRIORITY?.LOW ?? -25;
            PIXI.Ticker.shared.remove(this.followHandler, undefined, priority);
            this.followHandler = null;
        }
    }

    tickFollowAnimation() {
        const core = this.getCoreModel();
        if (!core) return;

        // 平滑插值
        const lerp = 0.08;
        this.followCurrent.x += (this.followTarget.x - this.followCurrent.x) * lerp;
        this.followCurrent.y += (this.followTarget.y - this.followCurrent.y) * lerp;

        const x = this.followCurrent.x; // -1 ~ 1
        const y = this.followCurrent.y;

        // thinking 表情动画会控制眼球和头部，跳过这些参数
        // singing 时头部固定朝前，嘴巴始终对着屏幕
        const skipEyeAndHead = this.expressionAnimType === "thinking";
        const lockHeadForward = this.expressionAnimType === "singing";

        if (!skipEyeAndHead) {
            // 眼球跟随
            this.setCoreParameter("ParamEyeBallX", x * 1.0);
            this.setCoreParameter("ParamEyeBallY", -y * 1.0);

            // 头部跟随（唱歌时锁定朝前）
            if (!lockHeadForward) {
                this.setCoreParameter("ParamAngleX", x * 30);
                this.setCoreParameter("ParamAngleY", -y * 20);
                this.setCoreParameter("ParamAngleZ", x * -10);
            } else {
                this.setCoreParameter("ParamAngleX", 0);
                this.setCoreParameter("ParamAngleY", 0);
                this.setCoreParameter("ParamAngleZ", 0);
            }
        }

        // 身体跟随（唱歌时也锁定）
        if (!lockHeadForward) {
            this.setCoreParameter("ParamBodyAngleX", x * 10);
        } else {
            this.setCoreParameter("ParamBodyAngleX", 0);
        }
    }

    normalizeWheelDelta(event) {
        let delta = event.deltaY;
        if (event.deltaMode === 1) {
            delta *= 16;
        } else if (event.deltaMode === 2) {
            delta *= this.canvas.clientHeight;
        }
        return delta;
    }

    clientToGlobal(clientX, clientY) {
        const rect = this.canvas.getBoundingClientRect();
        return new PIXI.Point(
            (clientX - rect.left) * (this.app.renderer.width / rect.width),
            (clientY - rect.top) * (this.app.renderer.height / rect.height)
        );
    }

    getPinchCenter(touches) {
        return {
            x: (touches[0].clientX + touches[1].clientX) / 2,
            y: (touches[0].clientY + touches[1].clientY) / 2
        };
    }

    getPinchDistance(touches) {
        const dx = touches[0].clientX - touches[1].clientX;
        const dy = touches[0].clientY - touches[1].clientY;
        return Math.hypot(dx, dy);
    }

    isEmbedded() {
        return new URLSearchParams(window.location.search).get("embedded") === "1";
    }

    /** 内嵌模式优先使用 embeddedLayout 覆盖缩放与位移。 */
    getLayoutConfig() {
        const cfg = this.currentConfig;
        if (!cfg) return {};
        if (this.isEmbedded() && cfg.embeddedLayout) {
            return { ...cfg, ...cfg.embeddedLayout };
        }
        return cfg;
    }

    getFitScale() {
        const parent = this.canvas.parentElement;
        return Math.min(
            parent.clientWidth / this.model.width,
            parent.clientHeight / this.model.height
        );
    }

    getRenderScale() {
        return this.getFitScale() * this.scale;
    }

    getDefaultRootPosition() {
        const layout = this.getLayoutConfig();
        const parent = this.canvas.parentElement;
        let x = parent.clientWidth / 2 + (layout.initialXshift || 0);
        let y;
        if (layout.anchorYRatio != null) {
            y = parent.clientHeight * layout.anchorYRatio;
        } else {
            y = parent.clientHeight / 2 + (layout.initialYshift || 0);
        }
        // 放大后模型几何中心在脸下方，按模型高度比例下移锚点以露出脸部。
        if (this.model && layout.faceFocusOffset) {
            y += this.model.height * this.getRenderScale() * layout.faceFocusOffset;
        }
        return { x, y };
    }

    centerModelLocally() {
        this.model.scale.set(1);
        this.model.x = -this.model.width / 2;
        this.model.y = -this.model.height / 2;
    }

    applyLayout() {
        if (!this.model || !this.modelRoot) return;
        this.modelRoot.scale.set(this.getRenderScale());
    }

    startDrag() {}
    moveDrag() {}
    endDrag() {}

    getStorageKey(modelId) {
        return `live2d-demo-scale-${modelId}`;
    }

    loadSavedScale(modelId, defaultScale) {
        const saved = localStorage.getItem(this.getStorageKey(modelId));
        if (saved === null) return defaultScale;
        const value = parseFloat(saved);
        return Number.isFinite(value) ? value : defaultScale;
    }

    saveScale(modelId) {
        clearTimeout(this.saveScaleTimer);
        this.saveScaleTimer = setTimeout(() => {
            localStorage.setItem(this.getStorageKey(modelId), String(this.scale));
        }, 300);
    }

    notifyZoomDisplay() {
        if (this.pendingZoomDisplay !== null) return;
        this.pendingZoomDisplay = requestAnimationFrame(() => {
            this.pendingZoomDisplay = null;
            if (typeof updateZoomDisplay === "function") {
                updateZoomDisplay(this.scale);
            }
        });
    }

    loadModelWithTimeout(config, timeoutMs = 30000) {
        const loadPromise = PIXI.live2d.Live2DModel.from(config.url, {
            autoInteract: false,
            crossOrigin: "anonymous"
        });

        const timeoutPromise = new Promise((_, reject) => {
            setTimeout(() => reject(new Error("模型加载超时，请检查网络或刷新重试")), timeoutMs);
        });

        return Promise.race([loadPromise, timeoutPromise]);
    }

    async loadModel(config) {
        if (!PIXI.live2d?.Live2DModel) {
            throw new Error("Live2D 库未正确加载，请确认已启动本地服务器");
        }

        this.stopFollowAnimation();
        this.stopExpressionAnimation();
        this.cancelZoomAnimation();
        this.isLoaded = false;

        if (this.model) {
            this.modelRoot.removeChild(this.model);
            this.model.destroy();
            this.model = null;
        }

        this.currentConfig = config;
        this.model = await this.loadModelWithTimeout(config);
        this.modelRoot.addChild(this.model);

        this.centerModelLocally();
        const layout = this.getLayoutConfig();
        const defaultScale = layout.kScale ?? 0.45;
        // App 内嵌不使用 localStorage 里 Demo 调试留下的缩放，保证每次一致。
        this.scale = this.isEmbedded()
            ? defaultScale
            : this.loadSavedScale(config.id, defaultScale);

        const home = this.getDefaultRootPosition();
        this.modelRoot.x = home.x;
        this.modelRoot.y = home.y;
        this.applyLayout();

        const defaultExpression = config.defaultExpression ?? 0;
        if (defaultExpression === null) {
            this.resetExpression();
        } else {
            this.setExpression(defaultExpression);
        }

        if (config.idleMotionGroupName) {
            this.model.motion(config.idleMotionGroupName);
        }

        this.setupClickInteraction(config);
        this.startFollowAnimation();
        this.isLoaded = true;
        this.notifyZoomDisplay();
        return this.model;
    }

    zoomAt(clientX, clientY, factor) {
        if (!this.model || !this.modelRoot || factor === 1) return;

        this.cancelZoomAnimation();

        const global = this.clientToGlobal(clientX, clientY);
        const local = this.modelRoot.toLocal(global);
        const nextScale = Math.max(0.15, Math.min(2.5, this.scale * factor));
        if (Math.abs(nextScale - this.scale) < 0.0001) return;

        this.scale = nextScale;
        this.modelRoot.scale.set(this.getRenderScale());

        const after = this.modelRoot.toGlobal(local);
        this.modelRoot.x += global.x - after.x;
        this.modelRoot.y += global.y - after.y;

        if (this.currentConfig) {
            this.saveScale(this.currentConfig.id);
        }
        this.notifyZoomDisplay();
    }

    zoomBy(factor) {
        const rect = this.canvas.getBoundingClientRect();
        this.zoomAt(
            rect.left + rect.width / 2,
            rect.top + rect.height / 2,
            factor
        );
    }

    cancelZoomAnimation() {
        if (this.zoomAnimId !== null) {
            cancelAnimationFrame(this.zoomAnimId);
            this.zoomAnimId = null;
        }
    }

    animateScaleTo(targetScale, duration = 160) {
        if (!this.model) return;

        this.cancelZoomAnimation();

        const rect = this.canvas.getBoundingClientRect();
        const anchorX = rect.left + rect.width / 2;
        const anchorY = rect.top + rect.height / 2;
        const startScale = this.scale;
        const endScale = Math.max(0.15, Math.min(2.5, targetScale));
        const startTime = performance.now();

        const step = (now) => {
            const t = Math.min((now - startTime) / duration, 1);
            const eased = 1 - Math.pow(1 - t, 3);
            const current = startScale + (endScale - startScale) * eased;
            const factor = current / this.scale;

            if (Math.abs(factor - 1) > 0.0001) {
                this.zoomAt(anchorX, anchorY, factor);
            }

            if (t < 1) {
                this.zoomAnimId = requestAnimationFrame(step);
            } else {
                this.zoomAnimId = null;
            }
        };

        this.zoomAnimId = requestAnimationFrame(step);
    }

    resetTransform() {
        if (!this.currentConfig) return;

        this.cancelZoomAnimation();
        localStorage.removeItem(this.getStorageKey(this.currentConfig.id));

        const home = this.getDefaultRootPosition();
        this.modelRoot.x = home.x;
        this.modelRoot.y = home.y;
        const layout = this.getLayoutConfig();
        this.animateScaleTo(layout.kScale ?? 0.45);
    }

    setupClickInteraction(config) {
        if (!this.model) return;

        this.model.on("hit", (hitAreas) => {
            if (hitAreas.includes("HitAreaHead") || hitAreas.includes("HitArea")) {
                this.triggerMotion("head", config);
            } else if (hitAreas.includes("HitAreaBody") || hitAreas.includes("HitArea2")) {
                this.triggerMotion("body", config);
            }
        });

        this.model.interactive = true;
        this.model.buttonMode = true;
    }

    triggerMotion(area, config) {
        if (!this.model || !config.tapMotions?.[area]) return;

        const motions = config.tapMotions[area];
        const entries = Object.entries(motions);
        if (!entries.length) return;

        const totalWeight = entries.reduce((sum, [, weight]) => sum + weight, 0);
        let random = Math.random() * totalWeight;

        for (const [motionName, weight] of entries) {
            random -= weight;
            if (random <= 0) {
                this.model.motion(motionName);
                break;
            }
        }
    }

    getCoreModel() {
        return this.model?.internalModel?.coreModel || null;
    }

    setCoreParameter(id, value) {
        const core = this.getCoreModel();
        if (!core || core.getParameterIndex(id) < 0) return;
        core.setParameterValueById(id, value);
    }

    stopExpressionAnimation() {
        if (this.expressionAnimHandler) {
            const priority = PIXI.UPDATE_PRIORITY?.LOW ?? -25;
            PIXI.Ticker.shared.remove(this.expressionAnimHandler, undefined, priority);
            this.expressionAnimHandler = null;
        }
        this.expressionAnimType = null;
    }

    startExpressionAnimation(type) {
        this.stopExpressionAnimation();
        if (!type || !this.getCoreModel()) return;

        this.expressionAnimType = type;
        this.expressionAnimStart = performance.now();
        this.expressionAnimHandler = () => this.tickExpressionAnimation();
        const priority = PIXI.UPDATE_PRIORITY?.LOW ?? -25;
        PIXI.Ticker.shared.add(this.expressionAnimHandler, undefined, priority);
    }

    tickExpressionAnimation() {
        const core = this.getCoreModel();
        if (!core || !this.expressionAnimType) return;

        const t = (performance.now() - this.expressionAnimStart) / 1000;

        switch (this.expressionAnimType) {
            case "thinking": {
                this.setCoreParameter("ParamEyeBallX", 0.2 + Math.sin(t * 1.4) * 0.18);
                this.setCoreParameter("ParamEyeBallY", -0.4 + Math.sin(t * 0.9) * 0.1);
                this.setCoreParameter("ParamAngleY", Math.sin(t * 1.2) * 4);
                this.setCoreParameter("ParamAngleX", Math.sin(t * 0.8) * 2);
                break;
            }
            case "speaking": {
                const mouth = 0.15 + Math.abs(Math.sin(t * 11)) * 0.7;
                this.setCoreParameter("ParamMouthOpenY", mouth);
                break;
            }
            case "singing": {
                const mouth = 0.3 + Math.abs(Math.sin(t * 7)) * 1;
                this.setCoreParameter("ParamMouthOpenY", mouth);
                this.setCoreParameter("Param34", Math.sin(t * 4) * 18);
                this.setCoreParameter("Param35", Math.sin(t * 4 + 1.2) * 18);
                this.setCoreParameter("Param36", Math.sin(t * 4 + 2.4) * 18);
                break;
            }
            default:
                break;
        }
    }

    setExpression(expression) {
        if (!this.model) return;
        if (expression === null || expression === undefined) {
            this.resetExpression();
            return;
        }
        this.model.expression(expression);
    }

    resetExpression() {
        this.stopExpressionAnimation();
        if (!this.model) return;
        const manager = this.model.internalModel?.motionManager?.expressionManager;
        if (manager?.resetExpression) {
            manager.resetExpression();
            return;
        }
        this.model.expression("");
    }

    setExpressionByKey(expressionKey, expressions) {
        if (!this.model || !expressions) return;
        const item = expressions.find((entry) => entry.key === expressionKey);
        if (!item) return;

        this.stopExpressionAnimation();

        if (item.reset || item.value === null || item.value === undefined) {
            this.resetExpression();
            return;
        }

        this.setExpression(item.value);
        if (item.animate) {
            this.startExpressionAnimation(item.animate);
        }
    }

    onResize() {
        if (!this.model || !this.modelRoot) return;
        const home = this.getDefaultRootPosition();
        this.modelRoot.x = home.x;
        this.modelRoot.y = home.y;
        this.applyLayout();
    }

    destroy() {
        this.stopFollowAnimation();
        this.stopExpressionAnimation();
        this.cancelZoomAnimation();
        if (this.model) {
            this.model.destroy();
            this.model = null;
        }
        if (this.modelRoot) {
            this.modelRoot.destroy({ children: true });
            this.modelRoot = null;
        }
        if (this.app) {
            this.app.destroy(true);
            this.app = null;
        }
    }
}
