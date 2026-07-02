/** 注视时整脸平移强度（0=不动，1=贴屏幕边缘），可在调试面板配置。 */

export type GazeMotionConfig = {
  /** 左右整体移动 */
  panHorizontal: number;
  /** 上下整体移动 */
  panVertical: number;
};

export const DEFAULT_GAZE_MOTION: GazeMotionConfig = {
  panHorizontal: 0.75,
  panVertical: 0.3,
};

const STORAGE_KEY = 'face.gazeMotion';

function clamp01(v: number): number {
  return Math.max(0, Math.min(1, v));
}

function readInitial(): GazeMotionConfig {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const j = JSON.parse(raw) as Partial<GazeMotionConfig>;
      return {
        panHorizontal: clamp01(j.panHorizontal ?? DEFAULT_GAZE_MOTION.panHorizontal),
        panVertical: clamp01(j.panVertical ?? DEFAULT_GAZE_MOTION.panVertical),
      };
    }
  } catch { /* ignore */ }
  return { ...DEFAULT_GAZE_MOTION };
}

let current: GazeMotionConfig = readInitial();
const subs = new Set<(c: GazeMotionConfig) => void>();

function persist() {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(current)); } catch { /* ignore */ }
}

function notify() {
  subs.forEach((f) => f(current));
}

export function getGazeMotionConfig(): GazeMotionConfig {
  return current;
}

export function setGazeMotionConfig(patch: Partial<GazeMotionConfig>) {
  current = {
    panHorizontal: patch.panHorizontal !== undefined
      ? clamp01(patch.panHorizontal) : current.panHorizontal,
    panVertical: patch.panVertical !== undefined
      ? clamp01(patch.panVertical) : current.panVertical,
  };
  persist();
  notify();
}

export function resetGazeMotionConfig() {
  current = { ...DEFAULT_GAZE_MOTION };
  persist();
  notify();
}

export function subscribeGazeMotionConfig(f: (c: GazeMotionConfig) => void) {
  subs.add(f);
  return () => { subs.delete(f); };
}
