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

/** 分解水平/垂直分量；horizBlend≈1 表示主要在左/右看 */
function gazeComponents(gazeX: number, gazeY: number) {
  const gx = softenAxis(gazeX);
  const gy = softenAxis(gazeY);
  const absX = Math.abs(gx);
  const absY = Math.abs(gy);
  const horizBlend = absX / (absX + absY * 0.4 + 0.1);
  return { gx, gy, horizBlend };
}

/**
 * 眼球：水平跟得足，垂直克制；左右看时与整脸平移配合。
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
  const { gx, gy, horizBlend } = gazeComponents(gazeX, gazeY);
  const dist = Math.min(1, Math.hypot(gx, gy));

  const pupilX = (0.94 + horizBlend * 0.06) * w;
  const pupilY = 0.62 * w;
  const vergence = 0.055 * w * (0.4 + 0.6 * dist);

  const openDelta = -gy * 0.035 * w;
  const lidCurve = -gy * 0.045 * w;

  return {
    left: {
      pupilX: gx * pupilX + vergence * gx,
      pupilY: gy * pupilY,
      openness: openDelta,
      upperLidCurve: lidCurve + gx * 0.08 * w,
      lidTilt: gx * 0.12 * w,
    },
    right: {
      pupilX: gx * pupilX - vergence * gx,
      pupilY: gy * pupilY,
      openness: openDelta,
      upperLidCurve: lidCurve - gx * 0.08 * w,
      lidTilt: -gx * 0.08 * w,
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
 * 头部：左右时整脸水平平移为主；上下幅度明显减小。
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
  const { gx, gy, horizBlend } = gazeComponents(gazeX, gazeY);

  // 偏左右时：加大整脸平移，减弱绕轴转动（「整体挪过去」而非猛甩头）
  const panScale = 1 + horizBlend * 0.65;
  const tiltScale = 1 - horizBlend * 0.4;

  return {
    headPanX: gx * 38 * w * panScale,
    headBobY: gy * 6 * w,
    headTilt: gx * 4.5 * w * tiltScale,
    headPitch: gy * 2 * w,
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
