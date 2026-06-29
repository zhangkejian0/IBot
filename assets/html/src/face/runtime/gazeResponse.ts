import type { EyeParams, FaceParams, FaceState } from '../types';

/** 各状态注视跟随强度（睡眠时减弱） */
const GAZE_WEIGHT_BY_STATE: Record<FaceState, number> = {
  idle: 1,
  gazing: 1,
  listening: 0.85,
  thinking: 0.7,
  happy: 0.75,
  confused: 0.55,
  angry: 0.45,
  sleepy: 0.25,
  sleeping: 0.08,
  waking: 0.35,
};

export function gazeWeightForState(state: FaceState): number {
  return GAZE_WEIGHT_BY_STATE[state];
}

export type GazeBodyOffset = {
  headPanX: number;
  headBobY: number;
  headTilt: number;
  headPitch: number;
};

export type GazeEyeChannel = {
  pupilX: number;
  pupilY: number;
  openness: number;
  upperLidCurve: number;
  lidTilt: number;
};

export type GazeEyeOffset = {
  left: GazeEyeChannel;
  right: GazeEyeChannel;
};

const ZERO_EYE: GazeEyeChannel = {
  pupilX: 0, pupilY: 0, openness: 0, upperLidCurve: 0, lidTilt: 0,
};

/** 边缘略收敛，避免贴边时动作过猛 */
function softenAxis(v: number): number {
  const a = Math.abs(v);
  const s = Math.sign(v);
  return s * a * (0.55 + 0.45 * (1 - a * 0.35));
}

/**
 * 眼球注视：桌面宠物风格 —— 瞳孔在眼眶内大范围跟随，略快于头部。
 * 参考 EMO / 虚拟宠物：眼睛承担主要「看向」语义，头部只做辅助转头。
 */
export function computeGazeEyes(
  gazeX: number,
  gazeY: number,
  weight: number,
): GazeEyeOffset {
  if (weight <= 0 || (gazeX === 0 && gazeY === 0)) {
    return { left: { ...ZERO_EYE }, right: { ...ZERO_EYE } };
  }

  const w = weight;
  const gx = softenAxis(gazeX);
  const gy = softenAxis(gazeY);
  const dist = Math.min(1, Math.hypot(gx, gy));

  // 水平可略大于垂直（眼眶解剖 + 卡通习惯）
  const pupilX = 0.82 * w;
  const pupilY = 0.72 * w;
  const vergence = 0.06 * w * (0.4 + 0.6 * dist);

  const openDelta = -gy * 0.08 * w;
  const lidCurve = -gy * 0.11 * w;

  return {
    left: {
      pupilX: gx * pupilX + vergence * gx,
      pupilY: gy * pupilY,
      openness: openDelta,
      upperLidCurve: lidCurve + gx * 0.09 * w,
      lidTilt: gx * 0.14 * w,
    },
    right: {
      pupilX: gx * pupilX - vergence * gx,
      pupilY: gy * pupilY,
      openness: openDelta,
      upperLidCurve: lidCurve - gx * 0.09 * w,
      lidTilt: -gx * 0.1 * w,
    },
  };
}

export function applyGazeEyeChannel(eye: EyeParams, ch: GazeEyeChannel): void {
  eye.pupilX += ch.pupilX;
  eye.pupilY += ch.pupilY;
  eye.openness += ch.openness;
  eye.upperLidCurve += ch.upperLidCurve;
  eye.lidTilt += ch.lidTilt;
}

/**
 * 整头转向：平移 + 偏航/俯仰（幅度克制，作为眼球的辅助）。
 */
export function computeGazeBody(
  gazeX: number,
  gazeY: number,
  weight: number,
): GazeBodyOffset {
  if (weight <= 0 || (gazeX === 0 && gazeY === 0)) {
    return { headPanX: 0, headBobY: 0, headTilt: 0, headPitch: 0 };
  }
  const w = weight;
  const gx = softenAxis(gazeX);
  const gy = softenAxis(gazeY);
  return {
    headPanX: gx * 24 * w,
    headBobY: gy * 18 * w,
    headTilt: gx * 8 * w,
    headPitch: gy * 5.5 * w,
  };
}

/** 兼容旧调用 */
export function applyGazeToTarget(
  target: FaceParams,
  gazeX: number,
  gazeY: number,
  weight: number,
): void {
  const eyes = computeGazeEyes(gazeX, gazeY, weight);
  applyGazeEyeChannel(target.leftEye, eyes.left);
  applyGazeEyeChannel(target.rightEye, eyes.right);
  const body = computeGazeBody(gazeX, gazeY, weight);
  target.headPanX += body.headPanX;
  target.headBobY += body.headBobY;
  target.headTilt += body.headTilt;
  target.headPitch += body.headPitch;
}
