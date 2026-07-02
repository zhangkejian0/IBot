/**
 * 唤醒光效配置（小爱电视风格：底部光带 + 上方文字）。
 * 颜色跟随氛围皮肤（ambientSkin）；运行时显隐由 wakeOverlayRuntime 控制。
 */

export type WakeOverlayConfig = {
  /** 总开关 */
  enabled: boolean;
  /** 无显式文案时使用的默认文字 */
  defaultText: string;
  /** 底部光效带高度（占容器高比例） */
  bottomBand: number;
  /** 光效带内距底边额外偏移（占容器高比例，微调） */
  bottomOffset: number;
  /** 光晕基础强度 0..1 */
  glowIntensity: number;
  /** 呼吸动画速度（越大越快） */
  breatheSpeed: number;
  /** 文字字号 px */
  textSize: number;
  /** 字间距 px */
  letterSpacing: number;
  /** setState('waking') 时自动显示 */
  showOnWaking: boolean;
  /** 离开 waking 后自动隐藏（进入 listening 等） */
  hideOnWakeEnd: boolean;
  /** 唤醒态默认展示时长 ms；0 = 不自动隐藏（靠 hideOnWakeEnd） */
  autoHideMs: number;
  /** 配置版本，用于迁移 localStorage 中的旧字段 */
  configVersion: number;
};

/** 递增后会对旧版持久化配置做字段迁移 */
const CONFIG_VERSION = 4;

export const DEFAULT_WAKE_OVERLAY_CONFIG: WakeOverlayConfig = {
  enabled: true,
  defaultText: '',
  bottomBand: 0.14,
  bottomOffset: 0,
  glowIntensity: 0.82,
  breatheSpeed: 1.25,
  textSize: 12,
  letterSpacing: 1,
  showOnWaking: true,
  hideOnWakeEnd: true,
  autoHideMs: 0,
  configVersion: CONFIG_VERSION,
};

const STORAGE_KEY = 'face.wakeOverlay';

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v));
}

function clampTextSize(v: number): number {
  return Math.max(8, Math.min(24, v));
}

function migrateConfig(j: Partial<WakeOverlayConfig> & { glowHeight?: number; configVersion?: number; themeId?: string }): WakeOverlayConfig {
  const version = j.configVersion ?? 1;

  let textSize = j.textSize ?? DEFAULT_WAKE_OVERLAY_CONFIG.textSize;
  if (version < CONFIG_VERSION && textSize >= 14) {
    textSize = DEFAULT_WAKE_OVERLAY_CONFIG.textSize;
  }
  textSize = clampTextSize(textSize);

  return {
    enabled: j.enabled ?? DEFAULT_WAKE_OVERLAY_CONFIG.enabled,
    defaultText: j.defaultText ?? DEFAULT_WAKE_OVERLAY_CONFIG.defaultText,
    bottomBand: clamp01(j.bottomBand ?? DEFAULT_WAKE_OVERLAY_CONFIG.bottomBand),
    bottomOffset: clamp01(j.bottomOffset ?? j.glowHeight ?? DEFAULT_WAKE_OVERLAY_CONFIG.bottomOffset),
    glowIntensity: clamp01(j.glowIntensity ?? DEFAULT_WAKE_OVERLAY_CONFIG.glowIntensity),
    breatheSpeed: Math.max(0.2, j.breatheSpeed ?? DEFAULT_WAKE_OVERLAY_CONFIG.breatheSpeed),
    textSize: clampTextSize(textSize),
    letterSpacing: Math.max(0, j.letterSpacing ?? DEFAULT_WAKE_OVERLAY_CONFIG.letterSpacing),
    showOnWaking: j.showOnWaking ?? DEFAULT_WAKE_OVERLAY_CONFIG.showOnWaking,
    hideOnWakeEnd: j.hideOnWakeEnd ?? DEFAULT_WAKE_OVERLAY_CONFIG.hideOnWakeEnd,
    autoHideMs: Math.max(0, j.autoHideMs ?? DEFAULT_WAKE_OVERLAY_CONFIG.autoHideMs),
    configVersion: CONFIG_VERSION,
  };
}

function readInitial(): WakeOverlayConfig {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const j = JSON.parse(raw) as Partial<WakeOverlayConfig> & { glowHeight?: number; configVersion?: number };
      const migrated = migrateConfig(j);
      if ((j.configVersion ?? 1) < CONFIG_VERSION) {
        try { localStorage.setItem(STORAGE_KEY, JSON.stringify(migrated)); } catch { /* ignore */ }
      }
      return migrated;
    }
  } catch { /* ignore */ }
  return { ...DEFAULT_WAKE_OVERLAY_CONFIG };
}

let current = readInitial();
const configSubs = new Set<(c: WakeOverlayConfig) => void>();

function persist() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(current)); } catch { /* ignore */ }
}

function notifyConfig() {
  configSubs.forEach((f) => f(current));
}

export function getWakeOverlayConfig(): WakeOverlayConfig {
  return current;
}

export function setWakeOverlayConfig(patch: Partial<WakeOverlayConfig>) {
  const next = { ...current };
  if (patch.enabled !== undefined) next.enabled = patch.enabled;
  if (patch.defaultText !== undefined) {
    next.defaultText = patch.defaultText;
    if (runtime.visible) {
      runtime = { ...runtime, text: patch.defaultText };
      notifyRuntime();
    }
  }
  if (patch.bottomBand !== undefined) next.bottomBand = clamp01(patch.bottomBand);
  if (patch.bottomOffset !== undefined) next.bottomOffset = clamp01(patch.bottomOffset);
  if (patch.glowIntensity !== undefined) next.glowIntensity = clamp01(patch.glowIntensity);
  if (patch.breatheSpeed !== undefined) next.breatheSpeed = Math.max(0.2, patch.breatheSpeed);
  if (patch.textSize !== undefined) next.textSize = clampTextSize(patch.textSize);
  if (patch.letterSpacing !== undefined) next.letterSpacing = Math.max(0, patch.letterSpacing);
  if (patch.showOnWaking !== undefined) next.showOnWaking = patch.showOnWaking;
  if (patch.hideOnWakeEnd !== undefined) next.hideOnWakeEnd = patch.hideOnWakeEnd;
  if (patch.autoHideMs !== undefined) next.autoHideMs = Math.max(0, patch.autoHideMs);
  next.configVersion = CONFIG_VERSION;
  current = next;
  persist();
  notifyConfig();
}

export function resetWakeOverlayConfig() {
  current = { ...DEFAULT_WAKE_OVERLAY_CONFIG };
  persist();
  notifyConfig();
}

export function subscribeWakeOverlayConfig(f: (c: WakeOverlayConfig) => void) {
  configSubs.add(f);
  return () => { configSubs.delete(f); };
}

/** 运行时显隐与文案（不持久化） */
export type WakeOverlayRuntime = {
  visible: boolean;
  text: string | null;
  /** 额外强度叠加（如麦克风音量）0..1 */
  level: number;
  /** 淡入进度 0..1，由组件动画驱动 */
  fade: number;
};

const DEFAULT_RUNTIME: WakeOverlayRuntime = {
  visible: false,
  text: null,
  level: 0,
  fade: 0,
};

let runtime: WakeOverlayRuntime = { ...DEFAULT_RUNTIME };
const runtimeSubs = new Set<(r: WakeOverlayRuntime) => void>();
let autoHideTimer: ReturnType<typeof setTimeout> | null = null;

function notifyRuntime() {
  runtimeSubs.forEach((f) => f(runtime));
}

function clearAutoHide() {
  if (autoHideTimer) {
    clearTimeout(autoHideTimer);
    autoHideTimer = null;
  }
}

export function getWakeOverlayRuntime(): WakeOverlayRuntime {
  return runtime;
}

export function setWakeOverlayRuntime(patch: Partial<WakeOverlayRuntime>) {
  runtime = { ...runtime, ...patch };
  notifyRuntime();
}

export function showWakeOverlay(text?: string | null, opts?: { autoHideMs?: number }) {
  if (!current.enabled) return;
  clearAutoHide();
  runtime = { ...runtime, visible: true, text: text ?? null };
  notifyRuntime();
  const hideMs = opts?.autoHideMs ?? current.autoHideMs;
  if (hideMs > 0) {
    autoHideTimer = setTimeout(() => hideWakeOverlay(), hideMs);
  }
}

export function hideWakeOverlay() {
  clearAutoHide();
  runtime = { ...runtime, visible: false, level: 0 };
  notifyRuntime();
}

export function setWakeOverlayLevel(level: number) {
  const v = Math.max(0, Math.min(1, level));
  if (runtime.level === v) return;
  runtime = { ...runtime, level: v };
  notifyRuntime();
}

/** 实时更新播报文案（用户说话流式更新），不改变显隐状态 */
export function setWakeOverlayText(text: string) {
  if (!runtime.visible) return;
  if (runtime.text === text) return;
  runtime = { ...runtime, text };
  notifyRuntime();
}

export function subscribeWakeOverlayRuntime(f: (r: WakeOverlayRuntime) => void) {
  runtimeSubs.add(f);
  return () => { runtimeSubs.delete(f); };
}
