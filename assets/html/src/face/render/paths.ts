import type { EyeParams, MouthParams } from '../types';

export const EYE_RX = 124;
export const EYE_RY = 92;
/** Iris sits inside an almond sclera, with visible white wedges at both corners. */
export const IRIS_RX = 68;
export const IRIS_RY = 82;

type Pt = { x: number; y: number };

function cubicPoint(t: number, p0: Pt, p1: Pt, p2: Pt, p3: Pt): Pt {
  const u = 1 - t;
  return {
    x: u * u * u * p0.x + 3 * u * u * t * p1.x + 3 * u * t * t * p2.x + t * t * t * p3.x,
    y: u * u * u * p0.y + 3 * u * u * t * p1.y + 3 * u * t * t * p2.y + t * t * t * p3.y,
  };
}

function cubicTangent(t: number, p0: Pt, p1: Pt, p2: Pt, p3: Pt): Pt {
  const u = 1 - t;
  return {
    x: 3 * u * u * (p1.x - p0.x) + 6 * u * t * (p2.x - p1.x) + 3 * t * t * (p3.x - p2.x),
    y: 3 * u * u * (p1.y - p0.y) + 6 * u * t * (p2.y - p1.y) + 3 * t * t * (p3.y - p2.y),
  };
}

function eyeLidGeometry(eye: EyeParams, cx: number, cy: number) {
  const rx = EYE_RX;
  const ry = EYE_RY;
  const op = Math.max(0, eye.openness);
  const uc = eye.upperLidCurve;
  const lc = eye.lowerLidCurve;
  const tilt = eye.lidTilt;

  let topPeakY = -ry * (0.14 + op * 0.6);
  if (uc < 0) topPeakY += ry * (-uc) * 0.35;
  else        topPeakY *= (1 + uc * 0.22);

  let botPeakY = ry * (0.16 + op * 0.36);
  if (lc < 0) botPeakY -= ry * (-lc) * 0.32;
  else        botPeakY *= (1 + lc * 0.18);

  const cornerL_Y = 10 + tilt * ry * 0.22;
  const cornerR_Y = -6 - tilt * ry * 0.22;

  return {
    rx, ry, op,
    leftX: cx - rx,
    rightX: cx + rx,
    topY: cy + topPeakY,
    botY: cy + botPeakY,
    cornerL_Y,
    cornerR_Y,
    ctrlOffsetX: rx * 0.58,
  };
}

function upperLidBezier(eye: EyeParams, cx: number, cy: number, isLeftEye: boolean) {
  const g = eyeLidGeometry(eye, cx, cy);
  if (isLeftEye) {
    return {
      p0: { x: g.leftX - 6, y: cy + g.cornerL_Y + 6 },
      p1: { x: cx - g.ctrlOffsetX * 0.9, y: g.topY + 4 },
      p2: { x: cx + g.ctrlOffsetX * 0.62, y: g.topY - 10 },
      p3: { x: g.rightX + 2, y: cy + g.cornerR_Y - 2 },
    };
  }
  return {
    p0: { x: g.rightX + 6, y: cy + g.cornerL_Y + 6 },
    p1: { x: cx + g.ctrlOffsetX * 0.9, y: g.topY + 4 },
    p2: { x: cx - g.ctrlOffsetX * 0.62, y: g.topY - 10 },
    p3: { x: g.leftX - 2, y: cy + g.cornerR_Y - 2 },
  };
}

function lowerLidBezier(eye: EyeParams, cx: number, cy: number, isLeftEye: boolean) {
  const g = eyeLidGeometry(eye, cx, cy);
  if (isLeftEye) {
    return {
      p0: { x: g.rightX, y: cy + g.cornerR_Y },
      p1: { x: cx + g.ctrlOffsetX, y: g.botY },
      p2: { x: cx - g.ctrlOffsetX, y: g.botY },
      p3: { x: g.leftX, y: cy + g.cornerL_Y },
    };
  }
  return {
    p0: { x: g.leftX, y: cy + g.cornerL_Y },
    p1: { x: cx - g.ctrlOffsetX, y: g.botY },
    p2: { x: cx + g.ctrlOffsetX, y: g.botY },
    p3: { x: g.rightX, y: cy + g.cornerR_Y },
  };
}

function lidNormal(t: number, p0: Pt, p1: Pt, p2: Pt, p3: Pt): Pt {
  const tan = cubicTangent(t, p0, p1, p2, p3);
  const len = Math.hypot(tan.x, tan.y) || 1;
  let nx = -tan.y / len;
  let ny = tan.x / len;
  if (ny > 0) { nx = -nx; ny = -ny; }
  return { x: nx, y: ny };
}

function irisPoint(cx: number, cy: number, rx: number, ry: number, angle: number): Pt {
  return { x: cx + rx * Math.cos(angle), y: cy + ry * Math.sin(angle) };
}

function irisNormal(angle: number): Pt {
  const nx = Math.cos(angle) / Math.sqrt(Math.cos(angle) ** 2 + (Math.sin(angle) * 0.85) ** 2);
  const ny = Math.sin(angle) / Math.sqrt(Math.cos(angle) ** 2 + (Math.sin(angle) * 0.85) ** 2);
  return { x: nx, y: ny };
}

function eyeProfile(eye: EyeParams, cx: number, cy: number, isLeftEye: boolean) {
  const outerSign = isLeftEye ? -1 : 1;
  const innerSign = -outerSign;
  const op = Math.max(0.05, Math.min(1, eye.openness));
  const close = 1 - op;
  const liftTop = close * 46;
  const liftBottom = close * 32;
  const tiltY = eye.lidTilt * 9;

  return {
    outerSign,
    innerSign,
    outer: { x: cx + outerSign * 123, y: cy + 2 + tiltY },
    inner: { x: cx + innerSign * 108, y: cy + 1 - tiltY * 0.45 },
    topOuter: { x: cx + outerSign * 86, y: cy - 43 + liftTop + tiltY * 0.3 },
    topPeak: { x: cx + outerSign * 18, y: cy - 58 + liftTop * 0.72 },
    topInner: { x: cx + innerSign * 58, y: cy - 34 + liftTop + tiltY * 0.1 },
    lowInner: { x: cx + innerSign * 62, y: cy + 24 - liftBottom - tiltY * 0.2 },
    lowMid: { x: cx + outerSign * 4, y: cy + 42 - liftBottom * 0.82 },
    lowOuter: { x: cx + outerSign * 86, y: cy + 30 - liftBottom + tiltY * 0.25 },
  };
}

export function buildEyePath(eye: EyeParams, cx: number, cy: number): string {
  const p = eyeProfile(eye, cx, cy, cx < 500);

  return [
    `M ${p.outer.x} ${p.outer.y}`,
    `C ${p.topOuter.x} ${p.topOuter.y} ${p.topPeak.x} ${p.topPeak.y} ${p.topInner.x} ${p.topInner.y}`,
    `C ${cx + p.innerSign * 78} ${cy - 23} ${p.inner.x - p.innerSign * 12} ${p.inner.y - 5} ${p.inner.x} ${p.inner.y}`,
    `C ${p.lowInner.x} ${p.lowInner.y} ${p.lowMid.x} ${p.lowMid.y} ${p.lowOuter.x} ${p.lowOuter.y}`,
    `C ${cx + p.outerSign * 104} ${cy + 24} ${p.outer.x - p.outerSign * 14} ${p.outer.y + 7} ${p.outer.x} ${p.outer.y}`,
    'Z',
  ].join(' ');
}

/** Shadow cast by upper lid onto sclera. */
export function buildScleraShadowPath(
  eye: EyeParams, cx: number, cy: number, isLeftEye: boolean, irisRx: number, irisRy: number,
): string {
  if (eye.openness < 0.12) return '';
  const p = eyeProfile(eye, cx, cy, isLeftEye);
  return [
    `M ${p.outer.x + p.outerSign * 4} ${p.outer.y + 2}`,
    `C ${p.topOuter.x} ${p.topOuter.y + 9} ${p.topPeak.x} ${p.topPeak.y + 12} ${p.topInner.x} ${p.topInner.y + 7}`,
    `C ${cx + p.innerSign * 80} ${cy - 16} ${p.inner.x - p.innerSign * 8} ${p.inner.y + 2} ${p.inner.x} ${p.inner.y + 4}`,
    `L ${cx + p.innerSign * irisRx * 0.45} ${cy - irisRy * 0.08}`,
    `L ${cx + p.outerSign * irisRx * 0.72} ${cy - irisRy * 0.12}`,
    'Z',
  ].join(' ');
}

/** Thick upper eyeliner tapering to inner corner + outer wing. */
export function buildUpperLidPath(eye: EyeParams, cx: number, cy: number, isLeftEye: boolean): string {
  if (eye.openness < 0.12) return '';
  const p = eyeProfile(eye, cx, cy, isLeftEye);
  const wing = { x: p.outer.x + p.outerSign * 42, y: p.outer.y - 24 };
  const browOuter = { x: cx + p.outerSign * 104, y: cy - 56 };
  const browPeak = { x: cx + p.outerSign * 12, y: cy - 77 };
  const browInner = { x: cx + p.innerSign * 70, y: cy - 42 };
  const lowerInner = { x: cx + p.innerSign * 58, y: cy - 25 };
  const lowerMid = { x: cx + p.outerSign * 2, y: cy - 42 };
  const lowerOuter = { x: cx + p.outerSign * 89, y: cy - 24 };
  return [
    `M ${wing.x} ${wing.y}`,
    `C ${cx + p.outerSign * 136} ${cy - 39} ${browOuter.x} ${browOuter.y} ${browPeak.x} ${browPeak.y}`,
    `C ${cx + p.innerSign * 23} ${cy - 75} ${browInner.x} ${browInner.y} ${p.inner.x} ${p.inner.y - 2}`,
    `C ${lowerInner.x} ${lowerInner.y} ${lowerMid.x} ${lowerMid.y} ${lowerOuter.x} ${lowerOuter.y}`,
    `C ${cx + p.outerSign * 112} ${cy - 19} ${p.outer.x} ${p.outer.y} ${wing.x} ${wing.y}`,
    'Z',
  ].join(' ');
}

export function buildLowerLidPath(eye: EyeParams, cx: number, cy: number, isLeftEye: boolean): string {
  if (eye.openness < 0.12) return '';
  const p = eyeProfile(eye, cx, cy, isLeftEye);
  return [
    `M ${p.inner.x - p.innerSign * 8} ${p.inner.y + 10}`,
    `C ${p.lowInner.x} ${p.lowInner.y + 6} ${p.lowMid.x} ${p.lowMid.y + 5} ${p.lowOuter.x} ${p.lowOuter.y + 3}`,
    `C ${cx + p.outerSign * 111} ${cy + 28} ${p.outer.x - p.outerSign * 6} ${p.outer.y + 12} ${p.outer.x} ${p.outer.y + 4}`,
  ].join(' ');
}

export function buildLidCreasePath(eye: EyeParams, cx: number, cy: number, isLeftEye: boolean): string {
  if (eye.openness < 0.12) return '';
  const p = eyeProfile(eye, cx, cy, isLeftEye);
  return [
    `M ${cx + p.outerSign * 94} ${cy - 64}`,
    `C ${cx + p.outerSign * 38} ${cy - 86} ${cx + p.innerSign * 24} ${cy - 78} ${cx + p.innerSign * 72} ${cy - 48}`,
  ].join(' ');
}

/**
 * Upper lashes radiating from the iris upper arc (reference style).
 * Long, fine strokes — longest at outer corner.
 */
export function buildUpperLashPath(
  cx: number, cy: number, irisRx: number, irisRy: number, isLeftEye: boolean,
): string {
  const outerSign = isLeftEye ? -1 : 1;
  const startAngle = isLeftEye ? -Math.PI * 0.88 : -Math.PI * 0.12;
  const endAngle = isLeftEye ? -Math.PI * 0.12 : -Math.PI * 0.88;
  const count = 10;
  const segments: string[] = [];

  for (let i = 0; i < count; i++) {
    const t = i / (count - 1);
    const angle = startAngle + t * (endAngle - startAngle);
    const pt = irisPoint(cx, cy, irisRx * 1.02, irisRy * 1.02, angle);
    const n = irisNormal(angle);
    const outerBias = isLeftEye ? (1 - t) : t;
    const lashLen = 7 + outerBias * outerBias * 23;
    const curlX = outerSign * outerBias * 16;
    const tipX = pt.x + n.x * lashLen * 0.25 + curlX;
    const tipY = pt.y + n.y * lashLen;
    const ctrlX = pt.x + n.x * lashLen * 0.5 + curlX * 0.5;
    const ctrlY = pt.y + n.y * lashLen * 0.5;
    segments.push(`M ${pt.x} ${pt.y} Q ${ctrlX} ${ctrlY} ${tipX} ${tipY}`);
  }

  return segments.join(' ');
}

/** Thick outer lash clumps, like the reference's dark triangular corner lashes. */
export function buildLashClumpPath(
  cx: number, cy: number, irisRx: number, irisRy: number, isLeftEye: boolean,
): string {
  const outerSign = isLeftEye ? -1 : 1;
  const baseAngle = isLeftEye ? -Math.PI * 0.82 : -Math.PI * 0.18;
  const segments: string[] = [];
  for (let i = 0; i < 3; i++) {
    const angle = baseAngle + (i - 1) * 0.11;
    const pt = irisPoint(cx, cy, irisRx * 1.03, irisRy * 1.03, angle);
    const n = irisNormal(angle);
    const len = 24 + i * 5;
    const tipX = pt.x + n.x * len * 0.18 + outerSign * len * 0.72;
    const tipY = pt.y + n.y * len - 2;
    segments.push(`M ${pt.x} ${pt.y} Q ${pt.x + outerSign * 18} ${pt.y - len * 0.45} ${tipX} ${tipY}`);
  }
  return segments.join(' ');
}

export function buildLowerLashPath(
  cx: number, cy: number, irisRx: number, irisRy: number, isLeftEye: boolean,
): string {
  const outerSign = isLeftEye ? -1 : 1;
  const startAngle = isLeftEye ? Math.PI * 0.92 : Math.PI * 0.08;
  const endAngle = isLeftEye ? Math.PI * 0.58 : Math.PI * 0.42;
  const count = 3;
  const segments: string[] = [];

  for (let i = 0; i < count; i++) {
    const t = i / (count - 1);
    const angle = startAngle + t * (endAngle - startAngle);
    const pt = irisPoint(cx, cy, irisRx * 0.98, irisRy * 0.98, angle);
    const n = irisNormal(angle);
    const len = 4 + t * 3;
    const tipX = pt.x + n.x * len * 0.15 + outerSign * len * 0.2;
    const tipY = pt.y + n.y * len;
    segments.push(`M ${pt.x} ${pt.y} L ${tipX} ${tipY}`);
  }
  return segments.join(' ');
}

/** Horizontal "horizon" reflection across lower iris. */
export function buildIrisHorizonPath(cx: number, cy: number, irisRx: number, irisRy: number): string {
  const y = cy + irisRy * 0.18;
  const w = irisRx * 0.64;
  return [
    `M ${cx - w} ${y}`,
    `Q ${cx - w * 0.2} ${y - irisRy * 0.04} ${cx} ${y - irisRy * 0.03}`,
    `Q ${cx + w * 0.2} ${y - irisRy * 0.04} ${cx + w} ${y}`,
  ].join(' ');
}

/** Tight ring immediately around pupil. */
export function buildPupilRingPath(cx: number, cy: number, pupilR: number): string {
  const r = pupilR * 1.06;
  return [
    `M ${cx - r} ${cy}`,
    `A ${r} ${r * 0.95} 0 1 1 ${cx + r} ${cy}`,
    `A ${r} ${r * 0.95} 0 1 1 ${cx - r} ${cy}`,
    `Z`,
  ].join(' ');
}

/** Faint outer iris ring. */
export function buildOuterIrisRingPath(cx: number, cy: number, irisRx: number, irisRy: number): string {
  const rx = irisRx * 1.02;
  const ry = irisRy * 1.02;
  return [
    `M ${cx - rx} ${cy}`,
    `A ${rx} ${ry} 0 1 1 ${cx + rx} ${cy}`,
    `A ${rx} ${ry} 0 1 1 ${cx - rx} ${cy}`,
    `Z`,
  ].join(' ');
}

/** Angular amber facets in the lower iris, matching the reference's jewel-like blocks. */
export function buildIrisTexturePath(cx: number, cy: number, irisRx: number, irisRy: number): string {
  const y = cy + irisRy * 0.04;
  const w = irisRx * 0.56;
  const d = irisRy * 0.42;
  return [
    `M ${cx - w * 0.9} ${y + d * 0.12}`,
    `L ${cx - w * 0.18} ${y + d * 0.95}`,
    `L ${cx + w * 0.15} ${y + d * 0.3}`,
    `L ${cx + w * 0.72} ${y + d * 0.88}`,
    `L ${cx + w} ${y + d * 0.16}`,
    `Q ${cx} ${y + d * 0.52} ${cx - w * 0.9} ${y + d * 0.12}`,
    `Z`,
    `M ${cx - w * 0.42} ${y + d * 0.3}`,
    `L ${cx - w * 0.08} ${y + d * 0.78}`,
    `M ${cx + w * 0.25} ${y + d * 0.28}`,
    `L ${cx + w * 0.55} ${y + d * 0.66}`,
  ].join(' ');
}

export function buildIrisCrescentPath(cx: number, cy: number, irisRx: number, irisRy: number): string {
  const y = cy + irisRy * 0.22;
  const w = irisRx * 0.5;
  const depth = irisRy * 0.32;
  return [
    `M ${cx - w} ${y}`,
    `Q ${cx - w * 0.2} ${y + depth} ${cx} ${y + depth * 0.85}`,
    `Q ${cx + w * 0.2} ${y + depth} ${cx + w} ${y}`,
    `Q ${cx} ${y + depth * 0.25} ${cx - w} ${y}`,
    `Z`,
  ].join(' ');
}

export function buildTearPoolPath(cx: number, cy: number, irisRx: number, irisRy: number): string {
  const y = cy + irisRy * 0.38;
  const w = irisRx * 0.85;
  const bulge = irisRy * 0.14;
  return [
    `M ${cx - w} ${y}`,
    `Q ${cx - w * 0.35} ${y + bulge} ${cx} ${y + bulge * 1.08}`,
    `Q ${cx + w * 0.35} ${y + bulge} ${cx + w} ${y}`,
    `L ${cx + w} ${y + bulge * 0.28}`,
    `Q ${cx} ${y + bulge * 0.5} ${cx - w} ${y + bulge * 0.28}`,
    `Z`,
  ].join(' ');
}

export function buildStarHighlightPath(cx: number, cy: number, r: number): string {
  const ir = r * 0.32;
  return [
    `M ${cx} ${cy - r}`,
    `L ${cx + ir} ${cy - ir}`,
    `L ${cx + r} ${cy}`,
    `L ${cx + ir} ${cy + ir}`,
    `L ${cx} ${cy + r}`,
    `L ${cx - ir} ${cy + ir}`,
    `L ${cx - r} ${cy}`,
    `L ${cx - ir} ${cy - ir}`,
    `Z`,
  ].join(' ');
}

/** Diagonal blush hatching lines beneath eye. */
export function buildBlushHatchPath(bcx: number, bcy: number, isLeft: boolean): string {
  const dir = isLeft ? 1 : -1;
  const lines: string[] = [];
  for (let i = 0; i < 6; i++) {
    const ox = (i - 2.5) * 14;
    const x1 = bcx + ox;
    const y1 = bcy - 8;
    const x2 = x1 + dir * 22;
    const y2 = bcy + 18 + i * 2;
    lines.push(`M ${x1} ${y1} L ${x2} ${y2}`);
  }
  return lines.join(' ');
}

export const MOUTH_W = 75;
export const MOUTH_H = 24;

export function buildMouthPath(mouth: MouthParams, cx: number, cy: number): string {
  const mw = MOUTH_W * mouth.width;
  const lift = mouth.cornerLift * MOUTH_H * 0.9;
  const cv = mouth.curve;
  const op = mouth.openness;

  const lx = cx - mw;
  const rx = cx + mw;
  const ly = cy - lift;
  const ry = cy - lift;

  const upperMidY = cy + cv * MOUTH_H * 0.8;
  const upperCtrlOffsetX = mw * 0.5;
  const upperCtrlY = upperMidY;

  const lowerMidY = cy + cv * MOUTH_H * 0.8 + op * MOUTH_H * 2.2 + Math.max(0, cv) * MOUTH_H * 0.3;
  const lowerCtrlOffsetX = mw * 0.45;
  const lowerCtrlY = lowerMidY;

  return [
    `M ${lx} ${ly}`,
    `C ${lx + upperCtrlOffsetX} ${upperCtrlY} ${rx - upperCtrlOffsetX} ${upperCtrlY} ${rx} ${ry}`,
    `C ${rx - lowerCtrlOffsetX} ${lowerCtrlY} ${lx + lowerCtrlOffsetX} ${lowerCtrlY} ${lx} ${ly}`,
    `Z`,
  ].join(' ');
}
