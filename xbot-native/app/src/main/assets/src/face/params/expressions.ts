import type { ExpressionName, ExpressionRecipe } from '../types';

export const EXPRESSION_RECIPES: Record<ExpressionName, ExpressionRecipe> = {
  neutral: {
    eyeShape: 'round',
    mouthShape: 'neutral',
    smileAmount: 0,
    blush: 0,
    headTilt: 0,
    animations: [],
  },

  happy: {
    eyeShape: 'round',
    mouthShape: 'bigSmile',
    smileAmount: 0.9,
    blush: 0.65,
    tearAmount: 0.85,
    animations: [
      { kind: 'pulse', target: 'leftEye.pupilScale', amp: 0.06, freq: 1.4 },
      { kind: 'pulse', target: 'rightEye.pupilScale', amp: 0.06, freq: 1.4, phase: 0.05 },
      { kind: 'sine', target: 'leftEye.tearAmount', amp: 0.06, freq: 0.9 },
      { kind: 'sine', target: 'rightEye.tearAmount', amp: 0.06, freq: 0.9, phase: 0.15 },
      { kind: 'bounce', target: 'mouth.cornerLift', amp: 0.05, freq: 1.1 },
      { kind: 'pulse', target: 'blush.intensity', amp: 0.12, freq: 0.7 },
    ],
  },

  confused: {
    eyeShape: 'round',
    mouthShape: 'small',
    headTilt: -10,
    eyebrowAsymmetry: 0.5,
    blush: 0.1,
    animations: [
      { kind: 'sway', target: 'headTilt', amp: 2.5, freq: 0.35 },
      { kind: 'sine', target: 'leftEye.pupilX', amp: 0.06, freq: 0.55 },
      { kind: 'sine', target: 'rightEye.pupilX', amp: 0.06, freq: 0.55, phase: 0.1 },
    ],
  },

  sleepy: {
    eyeShape: 'halfLid',
    mouthShape: 'small',
    highlightDim: 0.55,
    animations: [
      { kind: 'breathDecay', target: 'headBobY', amp: 4, freq: 0.22 },
      { kind: 'sine', target: 'leftEye.openness', amp: 0.1, freq: 0.28 },
      { kind: 'sine', target: 'rightEye.openness', amp: 0.1, freq: 0.28, phase: 0.08 },
    ],
  },

  sleeping: {
    eyeShape: 'closed',
    mouthShape: 'small',
    highlightDim: 1.0,
    animations: [
      { kind: 'sine', target: 'headBobY', amp: 5, freq: 0.18 },
      { kind: 'sine', target: 'mouth.openness', amp: 0.03, freq: 0.18 },
    ],
  },

  thinking: {
    eyeShape: 'narrow',
    mouthShape: 'small',
    animations: [
      { kind: 'wiggle', target: 'leftEye.pupilX', amp: 0.12, freq: 1.5 },
      { kind: 'wiggle', target: 'rightEye.pupilX', amp: 0.12, freq: 1.5, phase: 0.2 },
      { kind: 'sine', target: 'leftEye.pupilY', amp: 0.04, freq: 0.4 },
      { kind: 'sine', target: 'rightEye.pupilY', amp: 0.04, freq: 0.4 },
    ],
  },

  listening: {
    eyeShape: 'round',
    mouthShape: 'small',
    animations: [
      { kind: 'sine', target: 'headBobY', amp: 2.5, freq: 0.5 },
      { kind: 'pulse', target: 'leftEye.pupilScale', amp: 0.04, freq: 0.8 },
      { kind: 'pulse', target: 'rightEye.pupilScale', amp: 0.04, freq: 0.8 },
    ],
  },

  gazing: {
    eyeShape: 'round',
    mouthShape: 'neutral',
    animations: [],
  },

  waking: {
    eyeShape: 'halfLid',
    mouthShape: 'small',
    animations: [
      { kind: 'sine', target: 'leftEye.openness', amp: 0.08, freq: 0.3 },
      { kind: 'sine', target: 'rightEye.openness', amp: 0.08, freq: 0.3 },
    ],
  },
};
