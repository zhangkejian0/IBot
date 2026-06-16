import type { EyeParams } from '../face/types';
import {
  buildEyePath, buildIrisCrescentPath, buildIrisHorizonPath, buildIrisTexturePath,
  buildLashClumpPath, buildLidCreasePath, buildLowerLashPath, buildLowerLidPath,
  buildOuterIrisRingPath, buildPupilRingPath, buildScleraShadowPath, buildStarHighlightPath,
  buildTearPoolPath, buildUpperLashPath, buildUpperLidPath, IRIS_RX, IRIS_RY,
} from '../face/render/paths';

const PUPIL_BASE_R = 31;
const PUPIL_MAX_OFFSET_X = 124 * 0.35;
const PUPIL_MAX_OFFSET_Y = 92 * 0.25;

export type EyeGeometry = {
  clipD: string;
  scleraShadowD: string;
  iris: { cx: number; cy: number; rx: number; ry: number };
  pupil: { cx: number; cy: number; rx: number; ry: number };
  pupilRingD: string;
  outerIrisRingD: string;
  irisTextureD: string;
  crescentD: string;
  irisHorizonD: string;
  highlights: {
    main: { cx: number; cy: number; r: number; opacity: number };
    soft1: { cx: number; cy: number; r: number; opacity: number };
    soft2: { cx: number; cy: number; r: number; opacity: number };
    sparkle: { cx: number; cy: number; r: number; opacity: number };
    sparkle2: { cx: number; cy: number; r: number; opacity: number };
    sparkle3: { cx: number; cy: number; r: number; opacity: number };
    starD: string;
    starOpacity: number;
    inner: { cx: number; cy: number; r: number; opacity: number };
    tear1: { cx: number; cy: number; r: number; opacity: number };
    tear2: { cx: number; cy: number; r: number; opacity: number };
  };
  tearPoolD: string;
  tearPoolOpacity: number;
  lids: {
    upperD: string;
    lowerD: string;
    creaseD: string;
    opacity: number;
  };
  lashes: {
    upperD: string;
    clumpD: string;
    lowerD: string;
    opacity: number;
  };
};

export function computeEyeGeometry(
  eye: EyeParams,
  cx: number,
  cy: number,
  isLeftEye: boolean,
): EyeGeometry {
  const clipD = buildEyePath(eye, cx, cy);
  const op = Math.max(0, eye.openness);
  const irisRx = IRIS_RX * (0.96 + op * 0.04);
  const irisRy = IRIS_RY * (0.96 + op * 0.04);
  const hi = Math.max(0, Math.min(1, eye.highlightOn));
  const tear = Math.max(0, Math.min(1, eye.tearAmount));
  const lidVis = Math.min(1, op * 1.3);

  const pupilCX = cx + eye.pupilX * PUPIL_MAX_OFFSET_X;
  const pupilCY = cy + eye.pupilY * PUPIL_MAX_OFFSET_Y + irisRy * 0.03;
  const pupilR = PUPIL_BASE_R * eye.pupilScale;

  const hx = pupilCX - pupilR * 0.72;
  const hy = pupilCY - pupilR * 0.78;
  const innerSign = isLeftEye ? -1 : 1;

  return {
    clipD,
    scleraShadowD: buildScleraShadowPath(eye, cx, cy, isLeftEye, irisRx, irisRy),
    iris: { cx, cy, rx: irisRx, ry: irisRy },
    pupil: { cx: pupilCX, cy: pupilCY, rx: pupilR, ry: pupilR * 1.08 },
    pupilRingD: buildPupilRingPath(pupilCX, pupilCY, pupilR),
    outerIrisRingD: buildOuterIrisRingPath(cx, cy, irisRx, irisRy),
    irisTextureD: buildIrisTexturePath(cx, cy, irisRx, irisRy),
    crescentD: buildIrisCrescentPath(cx, cy, irisRx, irisRy),
    irisHorizonD: buildIrisHorizonPath(cx, cy, irisRx, irisRy),
    highlights: {
      main: { cx: hx, cy: hy, r: 10.5 * eye.pupilScale, opacity: hi },
      soft1: {
        cx: pupilCX + pupilR * 0.74, cy: pupilCY + pupilR * 0.58,
        r: 5.8 * eye.pupilScale, opacity: hi * 0.72,
      },
      soft2: {
        cx: pupilCX + pupilR * 0.42, cy: pupilCY + pupilR * 0.78,
        r: 3.5 * eye.pupilScale, opacity: hi * 0.62,
      },
      sparkle: {
        cx: pupilCX - pupilR * 0.52, cy: pupilCY - pupilR * 0.18,
        r: 3.6 * eye.pupilScale, opacity: hi * 0.82,
      },
      sparkle2: { cx: cx - irisRx * 0.32, cy: cy + irisRy * 0.44, r: 2.2, opacity: hi * 0.7 },
      sparkle3: { cx: cx + irisRx * 0.55, cy: cy + irisRy * 0.34, r: 2.5, opacity: hi * 0.75 },
      starD: buildStarHighlightPath(pupilCX + pupilR * 0.55, pupilCY + pupilR * 0.28, 4.2 * eye.pupilScale),
      starOpacity: hi * 0.72,
      inner: {
        cx: cx + innerSign * irisRx * 0.78, cy: cy + irisRy * 0.06,
        r: 2 + tear * 1.5, opacity: hi * (0.35 + tear * 0.45),
      },
      tear1: {
        cx: cx + irisRx * 0.08, cy: cy + irisRy * 0.44,
        r: 3.5 + tear * 2.5, opacity: tear * hi * 0.9,
      },
      tear2: {
        cx: cx - irisRx * 0.2, cy: cy + irisRy * 0.42,
        r: 2 + tear * 1.5, opacity: tear * hi * 0.65,
      },
    },
    tearPoolD: buildTearPoolPath(cx, cy, irisRx, irisRy),
    tearPoolOpacity: tear * 0.75,
    lids: {
      upperD: buildUpperLidPath(eye, cx, cy, isLeftEye),
      lowerD: buildLowerLidPath(eye, cx, cy, isLeftEye),
      creaseD: buildLidCreasePath(eye, cx, cy, isLeftEye),
      opacity: lidVis,
    },
    lashes: {
      upperD: buildUpperLashPath(cx, cy, irisRx, irisRy, isLeftEye),
      clumpD: buildLashClumpPath(cx, cy, irisRx, irisRy, isLeftEye),
      lowerD: buildLowerLashPath(cx, cy, irisRx, irisRy, isLeftEye),
      opacity: lidVis,
    },
  };
}
