import type { FaceParams, EyeParams } from '../types';

const NEUTRAL_EYE: EyeParams = {
  openness: 1.0,
  upperLidCurve: 0.0,
  lowerLidCurve: 0.0,
  lidTilt: 0.0,
  pupilX: 0.0,
  pupilY: 0.0,
  pupilScale: 1.0,
  highlightOn: 1.0,
  tearAmount: 0.0,
};

export const NEUTRAL_PARAMS: FaceParams = {
  leftEye: { ...NEUTRAL_EYE },
  rightEye: { ...NEUTRAL_EYE },
  mouth: { curve: 0.0, openness: 0.05, width: 1.0, cornerLift: 0.0 },
  blush: { intensity: 0.0, size: 1.0 },
  headTilt: 0.0,
  headBobY: 0.0,
};

export function cloneNeutral(): FaceParams {
  return {
    leftEye: { ...NEUTRAL_EYE },
    rightEye: { ...NEUTRAL_EYE },
    mouth: { ...NEUTRAL_PARAMS.mouth },
    blush: { ...NEUTRAL_PARAMS.blush },
    headTilt: 0,
    headBobY: 0,
  };
}
