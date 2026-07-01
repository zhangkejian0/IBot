/**
 * Reference-accurate anime eye paths (Amber / Genshin style).
 * innerSign → nose, outerSign → temple.
 */

type Pt = { x: number; y: number };

export type RefEyeScale = { cx: number; cy: number; scale: number };

function signs(isLeft: boolean) {
  const innerSign = isLeft ? 1 : -1;
  const outerSign = -innerSign;
  return { innerSign, outerSign };
}

function sc(v: number, s: number, c: number) { return c + v * s; }

export function refScleraPath(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(outerSign * 118, scale, cx)} ${sc(6, scale, cy)}`,
    `C ${sc(outerSign * 88, scale, cx)} ${sc(-48, scale, cy)} ${sc(outerSign * 14, scale, cx)} ${sc(-62, scale, cy)} ${sc(innerSign * 62, scale, cx)} ${sc(-38, scale, cy)}`,
    `C ${sc(innerSign * 88, scale, cx)} ${sc(-22, scale, cy)} ${sc(innerSign * 108, scale, cx)} ${sc(2, scale, cy)} ${sc(innerSign * 102, scale, cx)} ${sc(6, scale, cy)}`,
    `C ${sc(innerSign * 72, scale, cx)} ${sc(28, scale, cy)} ${sc(innerSign * 28, scale, cx)} ${sc(44, scale, cy)} ${cx} ${sc(46, scale, cy)}`,
    `C ${sc(outerSign * 28, scale, cx)} ${sc(44, scale, cy)} ${sc(outerSign * 72, scale, cx)} ${sc(28, scale, cy)} ${sc(outerSign * 118, scale, cx)} ${sc(6, scale, cy)}`,
    'Z',
  ].join(' ');
}

export function refUpperLidFilled(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  const wing = { x: sc(outerSign * 148, scale, cx), y: sc(-16, scale, cy) };
  const outerTop = { x: sc(outerSign * 108, scale, cx), y: sc(-54, scale, cy) };
  const midTop = { x: sc(outerSign * 18, scale, cx), y: sc(-62, scale, cy) };
  const innerTop = { x: sc(innerSign * 64, scale, cx), y: sc(-44, scale, cy) };
  const innerCorner = { x: sc(innerSign * 98, scale, cx), y: sc(-2, scale, cy) };
  const midLower = { x: sc(outerSign * 18, scale, cx), y: sc(-36, scale, cy) };
  const innerLower = { x: sc(innerSign * 70, scale, cx), y: sc(-18, scale, cy) };

  return [
    `M ${wing.x} ${wing.y}`,
    `C ${outerTop.x} ${outerTop.y} ${midTop.x} ${midTop.y} ${innerTop.x} ${innerTop.y}`,
    `C ${sc(innerSign * 82, scale, cx)} ${sc(-28, scale, cy)} ${sc(innerSign * 94, scale, cx)} ${sc(-8, scale, cy)} ${innerCorner.x} ${innerCorner.y}`,
    `C ${sc(innerSign * 86, scale, cx)} ${sc(-4, scale, cy)} ${innerLower.x} ${innerLower.y} ${midLower.x} ${midLower.y}`,
    `C ${sc(outerSign * 62, scale, cx)} ${sc(-28, scale, cy)} ${sc(outerSign * 104, scale, cx)} ${sc(-16, scale, cy)} ${wing.x} ${wing.y}`,
    'Z',
  ].join(' ');
}

/** 上眼线基线 — 仅描边，不做厚填充 */
export function refUpperLashLine(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(innerSign * 94, scale, cx)} ${sc(-3, scale, cy)}`,
    `C ${sc(innerSign * 66, scale, cx)} ${sc(-30, scale, cy)} ${sc(outerSign * 16, scale, cx)} ${sc(-56, scale, cy)} ${sc(outerSign * 104, scale, cx)} ${sc(-50, scale, cy)}`,
    `C ${sc(outerSign * 128, scale, cx)} ${sc(-46, scale, cy)} ${sc(outerSign * 142, scale, cx)} ${sc(-26, scale, cy)} ${sc(outerSign * 146, scale, cx)} ${sc(-10, scale, cy)}`,
  ].join(' ');
}

/** 极窄上睑遮罩 — 只盖住虹膜上缘，替代厚重填充体 */
export function refUpperLidThin(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(innerSign * 88, scale, cx)} ${sc(-5, scale, cy)}`,
    `C ${sc(innerSign * 62, scale, cx)} ${sc(-24, scale, cy)} ${sc(outerSign * 14, scale, cx)} ${sc(-48, scale, cy)} ${sc(outerSign * 98, scale, cx)} ${sc(-42, scale, cy)}`,
    `C ${sc(outerSign * 120, scale, cx)} ${sc(-38, scale, cy)} ${sc(outerSign * 132, scale, cx)} ${sc(-22, scale, cy)} ${sc(outerSign * 136, scale, cx)} ${sc(-6, scale, cy)}`,
    `C ${sc(outerSign * 120, scale, cx)} ${sc(-36, scale, cy)} ${sc(outerSign * 12, scale, cx)} ${sc(-44, scale, cy)} ${sc(innerSign * 86, scale, cx)} ${sc(2, scale, cy)}`,
    'Z',
  ].join(' ');
}

/** 自然弯睫 — 外长内短 */
export function refUpperLashesCurved(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { outerSign } = signs(isLeft);
  const specs = [
    { bx: 138, by: -38, len: 20, curl: 10 },
    { bx: 118, by: -50, len: 16, curl: 8 },
    { bx: 88, by: -56, len: 12, curl: 7 },
    { bx: 52, by: -52, len: 9, curl: 6 },
    { bx: 18, by: -42, len: 7, curl: 5 },
    { bx: -8, by: -28, len: 5, curl: 4 },
  ];
  return specs.map(({ bx, by, len, curl }) => {
    const x0 = sc(outerSign * bx, scale, cx);
    const y0 = sc(by, scale, cy);
    const x1 = x0 + outerSign * scale * 1.5;
    const y1 = y0 - len * scale;
    const cpx = x0 + outerSign * curl * scale;
    const cpy = y0 - len * scale * 0.42;
    return `M ${x0} ${y0} Q ${cpx} ${cpy} ${x1} ${y1}`;
  }).join(' ');
}

/** 外角加长睫束（描边，非三角填充） */
export function refOuterLashFan(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { outerSign } = signs(isLeft);
  const o = outerSign;
  return [
    `M ${sc(o * 104, scale, cx)} ${sc(-48, scale, cy)} Q ${sc(o * 128, scale, cx)} ${sc(-58, scale, cy)} ${sc(o * 136, scale, cx)} ${sc(-38, scale, cy)}`,
    `M ${sc(o * 108, scale, cx)} ${sc(-44, scale, cy)} Q ${sc(o * 134, scale, cx)} ${sc(-48, scale, cy)} ${sc(o * 142, scale, cx)} ${sc(-28, scale, cy)}`,
    `M ${sc(o * 112, scale, cx)} ${sc(-38, scale, cy)} Q ${sc(o * 138, scale, cx)} ${sc(-32, scale, cy)} ${sc(o * 146, scale, cx)} ${sc(-14, scale, cy)}`,
  ].join(' ');
}

/** Fine lash spikes protruding from upper lid top edge. @deprecated use refUpperLashesCurved */
export function refUpperLashSpikes(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  return refUpperLashesCurved(cx, cy, isLeft, scale);
}

export function refOuterLashTriangles(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  return refOuterLashFan(cx, cy, isLeft, scale);
}

export function refLowerLidPath(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(innerSign * 90, scale, cx)} ${sc(8, scale, cy)}`,
    `C ${sc(innerSign * 50, scale, cx)} ${sc(38, scale, cy)} ${cx} ${sc(46, scale, cy)} ${sc(outerSign * 60, scale, cx)} ${sc(36, scale, cy)}`,
    `M ${sc(outerSign * 80, scale, cx)} ${sc(22, scale, cy)}`,
    `C ${sc(outerSign * 104, scale, cx)} ${sc(14, scale, cy)} ${sc(outerSign * 114, scale, cx)} ${sc(8, scale, cy)} ${sc(outerSign * 118, scale, cx)} ${sc(4, scale, cy)}`,
  ].join(' ');
}

export function refLowerLidShadow(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(innerSign * 70, scale, cx)} ${sc(36, scale, cy)}`,
    `Q ${cx} ${sc(52, scale, cy)} ${sc(outerSign * 70, scale, cx)} ${sc(36, scale, cy)}`,
    `Q ${cx} ${sc(42, scale, cy)} ${sc(innerSign * 70, scale, cx)} ${sc(36, scale, cy)}`,
    'Z',
  ].join(' ');
}

export function refLidCreasePath(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(outerSign * 108, scale, cx)} ${sc(-72, scale, cy)}`,
    `C ${sc(outerSign * 42, scale, cx)} ${sc(-92, scale, cy)} ${sc(innerSign * 22, scale, cx)} ${sc(-88, scale, cy)} ${sc(innerSign * 74, scale, cx)} ${sc(-56, scale, cy)}`,
  ].join(' ');
}

export function refScleraShadow(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(outerSign * 108, scale, cx)} ${sc(-10, scale, cy)}`,
    `C ${sc(outerSign * 72, scale, cx)} ${sc(-36, scale, cy)} ${sc(outerSign * 18, scale, cx)} ${sc(-46, scale, cy)} ${sc(innerSign * 60, scale, cx)} ${sc(-30, scale, cy)}`,
    `C ${sc(innerSign * 80, scale, cx)} ${sc(-16, scale, cy)} ${sc(innerSign * 90, scale, cx)} ${sc(-2, scale, cy)} ${sc(innerSign * 84, scale, cx)} ${sc(4, scale, cy)}`,
    `L ${sc(outerSign * 50, scale, cx)} ${sc(-6, scale, cy)}`,
    'Z',
  ].join(' ');
}

export type RefIrisLayout = {
  cx: number; cy: number; rx: number; ry: number;
  pupilCx: number; pupilCy: number; pupilRx: number; pupilRy: number;
};

export function refIrisLayout(cx: number, cy: number, scale = 1): RefIrisLayout {
  // 卡哇伊：圆虹膜 + 圆瞳孔
  // 父级 Y 被 1.35× 拉伸，所以这里的 ry 都已预除以 1.35，保证视觉上是圆
  return {
    cx: sc(2, scale, cx), cy: sc(4, scale, cy),
    rx: 88 * scale, ry: 65 * scale,
    pupilCx: cx, pupilCy: sc(-4, scale, cy),
    pupilRx: 42 * scale, pupilRy: 31 * scale,
  };
}

export function refIrisTopShadow(cx: number, cy: number, rx: number, ry: number): string {
  return [
    `M ${cx - rx} ${cy - ry * 0.12}`,
    `A ${rx} ${ry * 0.75} 0 0 1 ${cx + rx} ${cy - ry * 0.12}`,
    `L ${cx + rx * 0.9} ${cy + ry * 0.06}`,
    `Q ${cx} ${cy} ${cx - rx * 0.9} ${cy + ry * 0.06}`,
    'Z',
  ].join(' ');
}

export function refIrisBottomGlow(cx: number, cy: number, rx: number, ry: number): string {
  const y = cy + ry * 0.5;
  const w = rx * 0.9;
  const h = ry * 0.4;
  return [
    `M ${cx - w} ${y}`,
    `Q ${cx - w * 0.28} ${y + h} ${cx} ${y + h * 0.95}`,
    `Q ${cx + w * 0.28} ${y + h} ${cx + w} ${y}`,
    `Q ${cx} ${y + h * 0.32} ${cx - w} ${y}`,
    'Z',
  ].join(' ');
}

export function refIrisInnerGlow(cx: number, cy: number, rx: number, ry: number): string {
  const y = cy + ry * 0.26;
  const w = rx * 0.54;
  const d = ry * 0.3;
  return [
    `M ${cx - w} ${y}`,
    `Q ${cx - w * 0.22} ${y + d} ${cx} ${y + d * 0.9}`,
    `Q ${cx + w * 0.22} ${y + d} ${cx + w} ${y}`,
    `Q ${cx} ${y + d * 0.25} ${cx - w} ${y}`,
    'Z',
  ].join(' ');
}

/** Vertical radial slits in lower amber iris. */
export function refIrisRadialSlits(cx: number, cy: number, rx: number, ry: number): string {
  const y0 = cy + ry * 0.15;
  const y1 = cy + ry * 0.55;
  const segs: string[] = [];
  for (let i = 0; i < 7; i++) {
    const t = (i - 3) / 3;
    const x = cx + t * rx * 0.55;
    segs.push(`M ${x} ${y0} L ${x + t * 3} ${y1}`);
  }
  return segs.join(' ');
}

export function refIrisGlints(cx: number, cy: number, rx: number, ry: number): string {
  const y = cy + ry * 0.32;
  const w = rx * 0.58;
  return [
    `M ${cx - w} ${y} L ${cx - w * 0.5} ${y}`,
    `M ${cx - w * 0.12} ${y + ry * 0.05} L ${cx + w * 0.28} ${y + ry * 0.05}`,
    `M ${cx + w * 0.38} ${y - ry * 0.02} L ${cx + w * 0.78} ${y - ry * 0.02}`,
  ].join(' ');
}

export function refIrisOuterRing(cx: number, cy: number, rx: number, ry: number): string {
  const orx = rx * 1.01;
  const ory = ry * 1.01;
  return [
    `M ${cx - orx} ${cy}`,
    `A ${orx} ${ory} 0 1 1 ${cx + orx} ${cy}`,
    `A ${orx} ${ory} 0 1 1 ${cx - orx} ${cy}`,
    'Z',
  ].join(' ');
}

export function refTearPool(cx: number, cy: number, rx: number, ry: number): string {
  const y = cy + ry * 0.56;
  const w = rx * 0.84;
  const b = ry * 0.11;
  return [
    `M ${cx - w} ${y}`,
    `Q ${cx} ${y + b} ${cx + w} ${y}`,
    `L ${cx + w} ${y + b * 0.38}`,
    `Q ${cx} ${y + b * 0.62} ${cx - w} ${y + b * 0.38}`,
    'Z',
  ].join(' ');
}

export type RefHighlights = {
  main: Pt & { r: number };
  mainStar: Pt & { r: number };
  sub: Pt & { r: number };
  soft: Pt & { r: number };
  amber: Array<Pt & { r: number; opacity: number }>;
  sparkles: Array<Pt & { r: number }>;
  innerCorner: Pt & { r: number };
  tearGlints: Array<Pt & { r: number }>;
};

export function refHighlights(cx: number, cy: number, isLeft: boolean, scale = 1): RefHighlights {
  const { innerSign, outerSign } = signs(isLeft);
  const ic = refIrisLayout(cx, cy, scale);
  const pr = ic.pupilRx;
  // 参考图的主高光在瞳孔的上半「内侧」（鼻侧），不是外侧
  return {
    main: { x: ic.pupilCx + innerSign * pr * 0.32, y: ic.pupilCy - pr * 0.65, r: pr * 0.5 },
    mainStar: { x: ic.pupilCx + innerSign * pr * 0.55, y: ic.pupilCy - pr * 0.42, r: pr * 0.22 },
    sub: { x: ic.pupilCx + outerSign * pr * 0.42, y: ic.pupilCy + pr * 0.22, r: pr * 0.32 },
    soft: { x: ic.pupilCx + innerSign * pr * 0.18, y: ic.pupilCy + pr * 0.55, r: pr * 0.26 },
    amber: [
      { x: ic.cx + outerSign * scale * 22, y: ic.cy + scale * 32, r: 3.5 * scale, opacity: 0.55 },
      { x: ic.cx + innerSign * scale * 10, y: ic.cy + scale * 38, r: 3.0 * scale, opacity: 0.5 },
      { x: ic.cx, y: ic.cy + scale * 42, r: 3.2 * scale, opacity: 0.55 },
      { x: ic.cx + outerSign * scale * 8, y: ic.cy + scale * 46, r: 2.5 * scale, opacity: 0.45 },
    ],
    sparkles: [
      { x: ic.cx + outerSign * scale * 24, y: ic.cy + scale * 36, r: 2.4 * scale },
      { x: ic.cx + innerSign * scale * 28, y: ic.cy + scale * 34, r: 2.0 * scale },
      { x: ic.cx + innerSign * scale * 8, y: ic.cy + scale * 48, r: 1.8 * scale },
    ],
    innerCorner: { x: ic.cx + innerSign * scale * 56, y: ic.cy + scale * 4, r: 2 * scale },
    tearGlints: [
      { x: ic.cx + innerSign * scale * 4, y: ic.cy + scale * 48, r: 3.5 * scale },
      { x: ic.cx + outerSign * scale * 16, y: ic.cy + scale * 46, r: 2.2 * scale },
    ],
  };
}

export function refLowerLashes(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { outerSign } = signs(isLeft);
  const o = outerSign;
  return [
    `M ${sc(o * 76, scale, cx)} ${sc(30, scale, cy)} Q ${sc(o * 84, scale, cx)} ${sc(38, scale, cy)} ${sc(o * 90, scale, cx)} ${sc(34, scale, cy)}`,
    `M ${sc(o * 92, scale, cx)} ${sc(24, scale, cy)} Q ${sc(o * 100, scale, cx)} ${sc(30, scale, cy)} ${sc(o * 104, scale, cx)} ${sc(26, scale, cy)}`,
  ].join(' ');
}

export function refBlushHatch(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  const lines: string[] = [];
  for (let i = 0; i < 5; i++) {
    const ox = (i - 2) * 14 * scale;
    lines.push(`M ${cx + ox + outerSign * 20 * scale} ${sc(58, scale, cy)} L ${cx + ox + innerSign * 18 * scale} ${sc(82 + i * 2, scale, cy)}`);
  }
  return lines.join(' ');
}

export function refEyebrowPath(cx: number, cy: number, isLeft: boolean, scale = 1): string {
  const { innerSign, outerSign } = signs(isLeft);
  return [
    `M ${sc(outerSign * 82, scale, cx)} ${sc(-72, scale, cy)}`,
    `Q ${sc(outerSign * 16, scale, cx)} ${sc(-86, scale, cy)} ${sc(innerSign * 52, scale, cx)} ${sc(-66, scale, cy)}`,
  ].join(' ');
}

export function refHairBangs(): string {
  // 大块刘海 — 留在眉毛上方，绝不挡眼睛
  // 眼睛顶部约 y=215（含拉伸），所以下沿尖最深到 y=200
  return [
    `M 80 60`,
    // 顶部大弧（左→右）
    `C 150 8 350 0 500 8`,
    `C 650 0 850 8 920 60`,
    // 右侧下垂
    `C 945 130 920 175 880 195`,
    // 右起第 1 个发尖（外侧最长）
    `C 850 180 820 165 800 180`,
    `C 790 188 780 195 770 192`,
    // 右起第 2 个发尖
    `C 745 180 720 160 695 170`,
    `C 680 180 660 188 650 180`,
    // 右起第 3 个发尖
    `C 625 168 605 150 580 160`,
    `C 565 172 545 185 535 178`,
    // 中央分头（∧）
    `C 520 158 510 130 500 120`,
    `C 490 130 480 158 465 178`,
    // 左起第 3 个发尖
    `C 455 185 435 172 420 160`,
    `C 395 150 375 168 350 180`,
    // 左起第 2 个发尖
    `C 340 188 320 180 305 170`,
    `C 280 160 255 180 230 188`,
    // 左起第 1 个发尖
    `C 220 195 200 188 195 178`,
    `C 180 160 150 175 120 195`,
    // 左侧上回闭合
    `C 80 175 55 130 80 60`,
    `Z`,
  ].join(' ');
}

export function refHairSide(isLeft: boolean): string {
  // 侧发：从耳边垂下，宽顶窄底的发束
  if (isLeft) {
    return [
      `M 65 180`,
      // 外缘下垂
      `C 60 280 80 400 105 510`,
      `C 130 515 155 510 175 500`,
      // 回到顶（内缘）
      `C 165 380 140 280 105 175`,
      `C 90 170 75 172 65 180`,
      `Z`,
    ].join(' ');
  }
  return [
    `M 935 180`,
    `C 940 280 920 400 895 510`,
    `C 870 515 845 510 825 500`,
    `C 835 380 860 280 895 175`,
    `C 910 170 925 172 935 180`,
    `Z`,
  ].join(' ');
}

export function refStarPath(x: number, y: number, r: number): string {
  const ir = r * 0.3;
  return [
    `M ${x} ${y - r}`, `L ${x + ir} ${y - ir}`, `L ${x + r} ${y}`,
    `L ${x + ir} ${y + ir}`, `L ${x} ${y + r}`, `L ${x - ir} ${y + ir}`,
    `L ${x - r} ${y}`, `L ${x - ir} ${y - ir}`, 'Z',
  ].join(' ');
}
