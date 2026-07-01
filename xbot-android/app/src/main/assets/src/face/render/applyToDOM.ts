import type { FaceParams } from '../types';
import {
  buildBlushHatchPath, buildEyePath, buildIrisCrescentPath, buildIrisHorizonPath,
  buildIrisTexturePath, buildLashClumpPath, buildLidCreasePath, buildLowerLashPath,
  buildLowerLidPath, buildMouthPath, buildOuterIrisRingPath, buildPupilRingPath,
  buildScleraShadowPath, buildStarHighlightPath, buildTearPoolPath, buildUpperLashPath,
  buildUpperLidPath, EYE_RX, EYE_RY, IRIS_RX, IRIS_RY,
} from './paths';

export const VIEW_W = 1000;
export const VIEW_H = 600;
export const LEFT_EYE_CX = 335;
export const RIGHT_EYE_CX = 665;
export const EYE_CY = 252;
export const MOUTH_CX = 500;
export const MOUTH_CY = 448;
export const LEFT_BLUSH_CX = 268;
export const RIGHT_BLUSH_CX = 732;
export const BLUSH_CY = 338;
export const PUPIL_BASE_R = 31;

const PUPIL_MAX_OFFSET_X = EYE_RX * 0.35;
const PUPIL_MAX_OFFSET_Y = EYE_RY * 0.25;

export type FaceRefs = {
  headGroup: SVGGElement;
  leftEyeSclera: SVGPathElement;
  leftScleraShadow: SVGPathElement;
  leftEyeClipPath: SVGPathElement;
  rightEyeSclera: SVGPathElement;
  rightScleraShadow: SVGPathElement;
  rightEyeClipPath: SVGPathElement;
  leftIris: SVGEllipseElement;
  rightIris: SVGEllipseElement;
  leftPupilRing: SVGPathElement;
  rightPupilRing: SVGPathElement;
  leftOuterIrisRing: SVGPathElement;
  rightOuterIrisRing: SVGPathElement;
  leftIrisTexture: SVGPathElement;
  rightIrisTexture: SVGPathElement;
  leftIrisCrescent: SVGPathElement;
  rightIrisCrescent: SVGPathElement;
  leftIrisHorizon: SVGPathElement;
  rightIrisHorizon: SVGPathElement;
  leftPupil: SVGEllipseElement;
  rightPupil: SVGEllipseElement;
  leftHighlight: SVGCircleElement;
  rightHighlight: SVGCircleElement;
  leftHighlightSoft1: SVGCircleElement;
  rightHighlightSoft1: SVGCircleElement;
  leftHighlightSoft2: SVGCircleElement;
  rightHighlightSoft2: SVGCircleElement;
  leftSparkle: SVGCircleElement;
  rightSparkle: SVGCircleElement;
  leftSparkle2: SVGCircleElement;
  rightSparkle2: SVGCircleElement;
  leftSparkle3: SVGCircleElement;
  rightSparkle3: SVGCircleElement;
  leftStarHighlight: SVGPathElement;
  rightStarHighlight: SVGPathElement;
  leftInnerHighlight: SVGCircleElement;
  rightInnerHighlight: SVGCircleElement;
  leftTearPool: SVGPathElement;
  rightTearPool: SVGPathElement;
  leftTearHighlight: SVGCircleElement;
  rightTearHighlight: SVGCircleElement;
  leftTearHighlight2: SVGCircleElement;
  rightTearHighlight2: SVGCircleElement;
  leftLowerLid: SVGPathElement;
  rightLowerLid: SVGPathElement;
  leftUpperLid: SVGPathElement;
  rightUpperLid: SVGPathElement;
  leftLidCrease: SVGPathElement;
  rightLidCrease: SVGPathElement;
  leftUpperLashes: SVGPathElement;
  rightUpperLashes: SVGPathElement;
  leftLashClumps: SVGPathElement;
  rightLashClumps: SVGPathElement;
  leftLowerLashes: SVGPathElement;
  rightLowerLashes: SVGPathElement;
  leftBlushHatch: SVGPathElement;
  rightBlushHatch: SVGPathElement;
  mouthPath: SVGPathElement;
  mouthTongue: SVGEllipseElement;
};

type EyeElements = {
  sclera: SVGPathElement;
  scleraShadow: SVGPathElement;
  clip: SVGPathElement;
  iris: SVGEllipseElement;
  pupilRing: SVGPathElement;
  outerIrisRing: SVGPathElement;
  irisTexture: SVGPathElement;
  crescent: SVGPathElement;
  irisHorizon: SVGPathElement;
  pupil: SVGEllipseElement;
  highlight: SVGCircleElement;
  highlightSoft1: SVGCircleElement;
  highlightSoft2: SVGCircleElement;
  sparkle: SVGCircleElement;
  sparkle2: SVGCircleElement;
  sparkle3: SVGCircleElement;
  starHighlight: SVGPathElement;
  innerHighlight: SVGCircleElement;
  tearPool: SVGPathElement;
  tearHighlight: SVGCircleElement;
  tearHighlight2: SVGCircleElement;
  lowerLid: SVGPathElement;
  upperLid: SVGPathElement;
  lidCrease: SVGPathElement;
  upperLashes: SVGPathElement;
  lashClumps: SVGPathElement;
  lowerLashes: SVGPathElement;
};

function applyEye(
  eye: FaceParams['leftEye'],
  cx: number,
  isLeftEye: boolean,
  els: EyeElements,
) {
  const d = buildEyePath(eye, cx, EYE_CY);
  els.sclera.setAttribute('d', d);
  els.clip.setAttribute('d', d);

  const op = Math.max(0, eye.openness);
  const irisRx = IRIS_RX * (0.96 + op * 0.04);
  const irisRy = IRIS_RY * (0.96 + op * 0.04);

  els.scleraShadow.setAttribute('d', buildScleraShadowPath(eye, cx, EYE_CY, isLeftEye, irisRx, irisRy));

  els.iris.setAttribute('cx', String(cx));
  els.iris.setAttribute('cy', String(EYE_CY));
  els.iris.setAttribute('rx', String(irisRx));
  els.iris.setAttribute('ry', String(irisRy));

  const pupilCX = cx + eye.pupilX * PUPIL_MAX_OFFSET_X;
  const pupilCY = EYE_CY + eye.pupilY * PUPIL_MAX_OFFSET_Y + irisRy * 0.03;
  const pupilR = PUPIL_BASE_R * eye.pupilScale;

  els.pupil.setAttribute('cx', String(pupilCX));
  els.pupil.setAttribute('cy', String(pupilCY));
  els.pupil.setAttribute('rx', String(pupilR));
  els.pupil.setAttribute('ry', String(pupilR * 1.08));

  els.pupilRing.setAttribute('d', buildPupilRingPath(pupilCX, pupilCY, pupilR));
  els.outerIrisRing.setAttribute('d', buildOuterIrisRingPath(cx, EYE_CY, irisRx, irisRy));
  els.irisTexture.setAttribute('d', buildIrisTexturePath(cx, EYE_CY, irisRx, irisRy));
  els.crescent.setAttribute('d', buildIrisCrescentPath(cx, EYE_CY, irisRx, irisRy));
  els.irisHorizon.setAttribute('d', buildIrisHorizonPath(cx, EYE_CY, irisRx, irisRy));

  const hi = Math.max(0, Math.min(1, eye.highlightOn));
  const tear = Math.max(0, Math.min(1, eye.tearAmount));
  const lidVis = Math.min(1, op * 1.3);

  // Primary crisp highlight: high upper-left, like the reference eye.
  const hx = pupilCX - pupilR * 0.72;
  const hy = pupilCY - pupilR * 0.78;
  const mainR = 10.5 * eye.pupilScale;
  els.highlight.setAttribute('cx', String(hx));
  els.highlight.setAttribute('cy', String(hy));
  els.highlight.setAttribute('r', String(mainR));
  els.highlight.setAttribute('opacity', String(hi));

  // Bright amber-side glints in the lower-right iris.
  els.highlightSoft1.setAttribute('cx', String(pupilCX + pupilR * 0.74));
  els.highlightSoft1.setAttribute('cy', String(pupilCY + pupilR * 0.58));
  els.highlightSoft1.setAttribute('r', String(5.8 * eye.pupilScale));
  els.highlightSoft1.setAttribute('opacity', String(hi * 0.72));

  els.highlightSoft2.setAttribute('cx', String(pupilCX + pupilR * 0.42));
  els.highlightSoft2.setAttribute('cy', String(pupilCY + pupilR * 0.78));
  els.highlightSoft2.setAttribute('r', String(3.5 * eye.pupilScale));
  els.highlightSoft2.setAttribute('opacity', String(hi * 0.62));

  // Tiny companion dot below the main catchlight.
  els.sparkle.setAttribute('cx', String(pupilCX - pupilR * 0.52));
  els.sparkle.setAttribute('cy', String(pupilCY - pupilR * 0.18));
  els.sparkle.setAttribute('r', String(3.6 * eye.pupilScale));
  els.sparkle.setAttribute('opacity', String(hi * 0.82));

  // Iris lower sparkles
  els.sparkle2.setAttribute('cx', String(cx - irisRx * 0.32));
  els.sparkle2.setAttribute('cy', String(EYE_CY + irisRy * 0.44));
  els.sparkle2.setAttribute('r', String(2.2));
  els.sparkle2.setAttribute('opacity', String(hi * 0.7));

  els.sparkle3.setAttribute('cx', String(cx + irisRx * 0.55));
  els.sparkle3.setAttribute('cy', String(EYE_CY + irisRy * 0.34));
  els.sparkle3.setAttribute('r', String(2.5));
  els.sparkle3.setAttribute('opacity', String(hi * 0.75));

  els.starHighlight.setAttribute('d', buildStarHighlightPath(pupilCX + pupilR * 0.55, pupilCY + pupilR * 0.28, 4.2 * eye.pupilScale));
  els.starHighlight.setAttribute('opacity', String(hi * 0.72));

  const innerSign = isLeftEye ? -1 : 1;
  els.innerHighlight.setAttribute('cx', String(cx + innerSign * irisRx * 0.78));
  els.innerHighlight.setAttribute('cy', String(EYE_CY + irisRy * 0.06));
  els.innerHighlight.setAttribute('r', String(2 + tear * 1.5));
  els.innerHighlight.setAttribute('opacity', String(hi * (0.35 + tear * 0.45)));

  els.tearPool.setAttribute('d', buildTearPoolPath(cx, EYE_CY, irisRx, irisRy));
  els.tearPool.setAttribute('opacity', String(tear * 0.75));

  els.tearHighlight.setAttribute('cx', String(cx + irisRx * 0.08));
  els.tearHighlight.setAttribute('cy', String(EYE_CY + irisRy * 0.44));
  els.tearHighlight.setAttribute('r', String(3.5 + tear * 2.5));
  els.tearHighlight.setAttribute('opacity', String(tear * hi * 0.9));

  els.tearHighlight2.setAttribute('cx', String(cx - irisRx * 0.2));
  els.tearHighlight2.setAttribute('cy', String(EYE_CY + irisRy * 0.42));
  els.tearHighlight2.setAttribute('r', String(2 + tear * 1.5));
  els.tearHighlight2.setAttribute('opacity', String(tear * hi * 0.65));

  els.upperLid.setAttribute('d', buildUpperLidPath(eye, cx, EYE_CY, isLeftEye));
  els.upperLid.setAttribute('opacity', String(lidVis));
  els.lowerLid.setAttribute('d', buildLowerLidPath(eye, cx, EYE_CY, isLeftEye));
  els.lowerLid.setAttribute('opacity', String(lidVis * 0.7));
  els.lidCrease.setAttribute('d', buildLidCreasePath(eye, cx, EYE_CY, isLeftEye));
  els.lidCrease.setAttribute('opacity', String(lidVis * 0.6));

  els.upperLashes.setAttribute('d', buildUpperLashPath(cx, EYE_CY, irisRx, irisRy, isLeftEye));
  els.upperLashes.setAttribute('opacity', String(lidVis * 0.92));
  els.lashClumps.setAttribute('d', buildLashClumpPath(cx, EYE_CY, irisRx, irisRy, isLeftEye));
  els.lashClumps.setAttribute('opacity', String(lidVis));
  els.lowerLashes.setAttribute('d', buildLowerLashPath(cx, EYE_CY, irisRx, irisRy, isLeftEye));
  els.lowerLashes.setAttribute('opacity', String(lidVis * 0.75));
}

export function writeFaceToRefs(refs: FaceRefs, p: FaceParams) {
  refs.headGroup.setAttribute(
    'transform',
    `translate(${VIEW_W / 2} ${VIEW_H / 2 + p.headBobY}) rotate(${p.headTilt}) translate(${-VIEW_W / 2} ${-VIEW_H / 2})`,
  );

  applyEye(p.leftEye, LEFT_EYE_CX, true, {
    sclera: refs.leftEyeSclera, scleraShadow: refs.leftScleraShadow, clip: refs.leftEyeClipPath,
    iris: refs.leftIris, pupilRing: refs.leftPupilRing, outerIrisRing: refs.leftOuterIrisRing,
    irisTexture: refs.leftIrisTexture, crescent: refs.leftIrisCrescent, irisHorizon: refs.leftIrisHorizon,
    pupil: refs.leftPupil, highlight: refs.leftHighlight,
    highlightSoft1: refs.leftHighlightSoft1, highlightSoft2: refs.leftHighlightSoft2,
    sparkle: refs.leftSparkle, sparkle2: refs.leftSparkle2, sparkle3: refs.leftSparkle3,
    starHighlight: refs.leftStarHighlight, innerHighlight: refs.leftInnerHighlight,
    tearPool: refs.leftTearPool, tearHighlight: refs.leftTearHighlight, tearHighlight2: refs.leftTearHighlight2,
    lowerLid: refs.leftLowerLid, upperLid: refs.leftUpperLid, lidCrease: refs.leftLidCrease,
    upperLashes: refs.leftUpperLashes, lashClumps: refs.leftLashClumps, lowerLashes: refs.leftLowerLashes,
  });

  applyEye(p.rightEye, RIGHT_EYE_CX, false, {
    sclera: refs.rightEyeSclera, scleraShadow: refs.rightScleraShadow, clip: refs.rightEyeClipPath,
    iris: refs.rightIris, pupilRing: refs.rightPupilRing, outerIrisRing: refs.rightOuterIrisRing,
    irisTexture: refs.rightIrisTexture, crescent: refs.rightIrisCrescent, irisHorizon: refs.rightIrisHorizon,
    pupil: refs.rightPupil, highlight: refs.rightHighlight,
    highlightSoft1: refs.rightHighlightSoft1, highlightSoft2: refs.rightHighlightSoft2,
    sparkle: refs.rightSparkle, sparkle2: refs.rightSparkle2, sparkle3: refs.rightSparkle3,
    starHighlight: refs.rightStarHighlight, innerHighlight: refs.rightInnerHighlight,
    tearPool: refs.rightTearPool, tearHighlight: refs.rightTearHighlight, tearHighlight2: refs.rightTearHighlight2,
    lowerLid: refs.rightLowerLid, upperLid: refs.rightUpperLid, lidCrease: refs.rightLidCrease,
    upperLashes: refs.rightUpperLashes, lashClumps: refs.rightLashClumps, lowerLashes: refs.rightLowerLashes,
  });

  refs.mouthPath.setAttribute('d', buildMouthPath(p.mouth, MOUTH_CX, MOUTH_CY));
  const tongueOpacity = Math.min(1, Math.max(0, (p.mouth.openness - 0.08) * 2.5));
  const tongueRy = Math.max(0, (p.mouth.openness - 0.05) * 22);
  refs.mouthTongue.setAttribute('opacity', String(tongueOpacity));
  refs.mouthTongue.setAttribute('rx', String(18 * p.mouth.width));
  refs.mouthTongue.setAttribute('ry', String(tongueRy));
  refs.mouthTongue.setAttribute('cy', String(MOUTH_CY + 6 + tongueRy * 0.3));

  const blushOpacity = Math.max(0, Math.min(1, p.blush.intensity));
  refs.leftBlushHatch.setAttribute('d', buildBlushHatchPath(LEFT_BLUSH_CX, BLUSH_CY, true));
  refs.leftBlushHatch.setAttribute('opacity', String(blushOpacity * 0.7));
  refs.rightBlushHatch.setAttribute('d', buildBlushHatchPath(RIGHT_BLUSH_CX, BLUSH_CY, false));
  refs.rightBlushHatch.setAttribute('opacity', String(blushOpacity * 0.7));
}
