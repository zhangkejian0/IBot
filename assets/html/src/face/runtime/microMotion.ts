import type { FaceParams, MicroMotionProfile } from '../types';

export const PROFILE_IDLE: MicroMotionProfile = {
  blinkPeriodMs: 4200,
  blinkDurationMs: 150,
  breathAmp: 1.2,
  saccadeAmp: 0,
};

export const PROFILE_LISTENING: MicroMotionProfile = {
  blinkPeriodMs: 3800,
  blinkDurationMs: 150,
  breathAmp: 2.4,
  saccadeAmp: 0,
};

export const PROFILE_THINKING: MicroMotionProfile = {
  blinkPeriodMs: 5000,
  blinkDurationMs: 160,
  breathAmp: 1.0,
  saccadeAmp: 0.18,
};

export const PROFILE_HAPPY: MicroMotionProfile = {
  blinkPeriodMs: 5500,
  blinkDurationMs: 130,
  breathAmp: 1.4,
  saccadeAmp: 0,
};

export const PROFILE_CONFUSED: MicroMotionProfile = {
  blinkPeriodMs: 4500,
  blinkDurationMs: 150,
  breathAmp: 1.0,
  saccadeAmp: 0,
};

export const PROFILE_ANGRY: MicroMotionProfile = {
  blinkPeriodMs: 6000,
  blinkDurationMs: 140,
  breathAmp: 1.8,
  saccadeAmp: 0,
};

export const PROFILE_SLEEPY: MicroMotionProfile = {
  blinkPeriodMs: 2000,
  blinkDurationMs: 380,
  breathAmp: 2.0,
  saccadeAmp: 0,
};

export const PROFILE_SLEEPING: MicroMotionProfile = {
  blinkPeriodMs: 999_999,
  blinkDurationMs: 0,
  breathAmp: 3.0,
  saccadeAmp: 0,
};

export const PROFILE_WAKING: MicroMotionProfile = {
  blinkPeriodMs: 2500,
  blinkDurationMs: 280,
  breathAmp: 1.8,
  saccadeAmp: 0,
};

let nextBlinkAtMs = 1500;
let blinkStartedAtMs = -1;
let currentBlinkDurationMs = 0;

function blinkFactor(nowMs: number, profile: MicroMotionProfile): number {
  if (blinkStartedAtMs < 0) {
    if (nowMs >= nextBlinkAtMs) {
      blinkStartedAtMs = nowMs;
      currentBlinkDurationMs = profile.blinkDurationMs;
    } else {
      return 1;
    }
  }
  const t = (nowMs - blinkStartedAtMs) / currentBlinkDurationMs;
  if (t >= 1) {
    blinkStartedAtMs = -1;
    const jitter = (Math.random() - 0.5) * profile.blinkPeriodMs * 0.3;
    nextBlinkAtMs = nowMs + profile.blinkPeriodMs + jitter;
    return 1;
  }
  return 1 - Math.sin(t * Math.PI);
}

export function applyMicroMotion(out: FaceParams, nowMs: number, profile: MicroMotionProfile) {
  const factor = blinkFactor(nowMs, profile);
  out.leftEye.openness *= factor;
  out.rightEye.openness *= factor;

  if (profile.breathAmp > 0) {
    out.headBobY += Math.sin((nowMs / 1000) * 0.6 * Math.PI * 2) * profile.breathAmp * 0.4;
  }

  if (profile.saccadeAmp > 0) {
    const s = nowMs / 1000;
    const dx = Math.sin(s * 1.3) * 0.6 + Math.sin(s * 0.4 + 1.7) * 0.4;
    out.leftEye.pupilX  += dx * profile.saccadeAmp;
    out.rightEye.pupilX += dx * profile.saccadeAmp;
  }
}
