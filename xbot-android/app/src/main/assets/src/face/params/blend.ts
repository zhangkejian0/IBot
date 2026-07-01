import type { FaceParams } from '../types';
import { NEUTRAL_PARAMS, cloneNeutral } from './neutral';

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));

type Layer = { pose: FaceParams; weight: number };

const EYE_FIELDS: (keyof FaceParams['leftEye'])[] = [
  'openness', 'upperLidCurve', 'lowerLidCurve', 'lidTilt',
  'pupilX', 'pupilY', 'pupilScale', 'highlightOn', 'tearAmount',
];

const EYE_RANGES: Record<keyof FaceParams['leftEye'], [number, number]> = {
  openness:      [0, 1.2],
  upperLidCurve: [-1.2, 1.2],
  lowerLidCurve: [-1.2, 1.2],
  lidTilt:       [-1, 1],
  pupilX:        [-1, 1],
  pupilY:        [-1, 1],
  pupilScale:    [0.4, 1.6],
  highlightOn:   [0, 1],
  tearAmount:    [0, 1],
};

function addEyeDelta(out: FaceParams['leftEye'], base: FaceParams['leftEye'], layer: FaceParams['leftEye'], w: number) {
  for (const k of EYE_FIELDS) {
    out[k] += w * (layer[k] - base[k]);
  }
}

function clampEye(eye: FaceParams['leftEye']) {
  for (const k of EYE_FIELDS) {
    const [lo, hi] = EYE_RANGES[k];
    eye[k] = clamp(eye[k], lo, hi);
  }
}

export function blendParams(layers: Layer[]): FaceParams {
  const out = cloneNeutral();
  const base = NEUTRAL_PARAMS;

  for (const { pose, weight } of layers) {
    if (weight === 0) continue;
    addEyeDelta(out.leftEye, base.leftEye, pose.leftEye, weight);
    addEyeDelta(out.rightEye, base.rightEye, pose.rightEye, weight);

    out.mouth.curve     += weight * (pose.mouth.curve     - base.mouth.curve);
    out.mouth.openness  += weight * (pose.mouth.openness  - base.mouth.openness);
    out.mouth.width     += weight * (pose.mouth.width     - base.mouth.width);
    out.mouth.cornerLift+= weight * (pose.mouth.cornerLift- base.mouth.cornerLift);

    out.blush.intensity += weight * (pose.blush.intensity - base.blush.intensity);
    out.blush.size      += weight * (pose.blush.size      - base.blush.size);

    out.headTilt        += weight * (pose.headTilt        - base.headTilt);
    out.headBobY        += weight * (pose.headBobY        - base.headBobY);
  }

  clampEye(out.leftEye);
  clampEye(out.rightEye);
  out.mouth.curve = clamp(out.mouth.curve, -1.2, 1.2);
  out.mouth.openness = clamp(out.mouth.openness, 0, 1);
  out.mouth.width = clamp(out.mouth.width, 0.3, 1.6);
  out.mouth.cornerLift = clamp(out.mouth.cornerLift, -0.8, 0.8);
  out.blush.intensity = clamp(out.blush.intensity, 0, 1);
  out.blush.size = clamp(out.blush.size, 0.5, 1.6);
  out.headTilt = clamp(out.headTilt, -20, 20);
  out.headBobY = clamp(out.headBobY, -15, 15);

  return out;
}
