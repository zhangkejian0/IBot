import type { OscillatorKind, OscillatorSpec, OscillatorTarget, FaceParams } from '../types';

const TAU = Math.PI * 2;

function shape(kind: OscillatorKind, phase: number): number {
  const p = phase - Math.floor(phase);
  const t = p * TAU;
  switch (kind) {
    case 'sine':
      return Math.sin(t);
    case 'pulse':
      return Math.max(0, Math.sin(t));
    case 'bounce': {
      const s = Math.sin(t);
      return s > 0 ? s * (1 - p * 0.5) : s * 0.3;
    }
    case 'wiggle': {
      const a = Math.sin(t) * 0.7;
      const b = Math.sin(t * 1.73 + 1.2) * 0.5;
      const c = Math.sin(t * 0.5 + 0.6) * 0.3;
      return (a + b + c) / 1.5;
    }
    case 'sway':
      return Math.sin(t);
    case 'breathDecay': {
      const inhale = p < 0.35 ? (p / 0.35) : 0;
      const exhale = p >= 0.35 ? 1 - Math.min(1, (p - 0.35) / 0.65) : 0;
      return inhale + exhale * 0.7 - 0.5;
    }
  }
}

function applyToTarget(p: FaceParams, target: OscillatorTarget, delta: number) {
  switch (target) {
    case 'leftEye.openness':       p.leftEye.openness += delta; break;
    case 'leftEye.pupilX':         p.leftEye.pupilX += delta; break;
    case 'leftEye.pupilY':         p.leftEye.pupilY += delta; break;
    case 'leftEye.pupilScale':     p.leftEye.pupilScale += delta; break;
    case 'leftEye.upperLidCurve':  p.leftEye.upperLidCurve += delta; break;
    case 'rightEye.openness':      p.rightEye.openness += delta; break;
    case 'rightEye.pupilX':        p.rightEye.pupilX += delta; break;
    case 'rightEye.pupilY':        p.rightEye.pupilY += delta; break;
    case 'rightEye.pupilScale':    p.rightEye.pupilScale += delta; break;
    case 'rightEye.upperLidCurve': p.rightEye.upperLidCurve += delta; break;
    case 'mouth.curve':            p.mouth.curve += delta; break;
    case 'mouth.openness':         p.mouth.openness += delta; break;
    case 'mouth.cornerLift':       p.mouth.cornerLift += delta; break;
    case 'mouth.width':            p.mouth.width += delta; break;
    case 'blush.intensity':        p.blush.intensity += delta; break;
    case 'headTilt':               p.headTilt += delta; break;
    case 'headBobY':               p.headBobY += delta; break;
    case 'pupilScale':
      p.leftEye.pupilScale += delta;
      p.rightEye.pupilScale += delta;
      break;
  }
}

export type ActiveOscillator = {
  spec: OscillatorSpec;
  weight: number;
};

export function applyOscillators(out: FaceParams, oscs: ActiveOscillator[], nowSec: number) {
  for (const { spec, weight } of oscs) {
    if (weight === 0) continue;
    const phase = nowSec * spec.freq + (spec.phase ?? 0);
    const v = shape(spec.kind, phase) * spec.amp * weight;
    applyToTarget(out, spec.target, v);
  }
}
