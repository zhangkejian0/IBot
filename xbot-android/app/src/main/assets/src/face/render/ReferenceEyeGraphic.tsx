import type { EyeParams } from '../types';
import { FACE_BG } from './faceLayout';
import {
  refBlushHatch, refEyebrowPath, refHairBangs, refHairSide,
  refHighlights, refIrisBottomGlow, refIrisGlints, refIrisInnerGlow, refIrisLayout,
  refIrisOuterRing, refIrisRadialSlits, refIrisTopShadow, refLowerLashes, refLowerLidPath,
  refLowerLidShadow, refLidCreasePath, refScleraPath, refScleraShadow,
  refTearPool, refUpperLashLine, refUpperLashesCurved, refUpperLidThin, refOuterLashFan,
} from '../../preview/referenceEyePaths';

const SCLERA = '#F0EBE4';
const LID_LINE = '#2A221C';
const LID_MASK = '#141010';
const LASH = '#4A3830';
const LID_SOFT = '#5A4840';
const LID_CREASE = '#6A5848';
const LOWER_LID_SHADOW = '#9A8070';

type Props = {
  cx: number;
  cy: number;
  isLeft: boolean;
  eye: EyeParams;
  scale?: number;
  clipId: string;
};

export function ReferenceEyeGraphic({ cx, cy, isLeft, eye, scale = 1, clipId }: Props) {
  const ic = refIrisLayout(cx, cy, scale);
  const h = refHighlights(cx, cy, isLeft, scale);
  const hi = Math.max(0, Math.min(1, eye.highlightOn));
  const tear = Math.max(0, Math.min(1, eye.tearAmount));
  const lidOp = Math.max(0, Math.min(1, eye.openness * 1.3));
  const scleraD = refScleraPath(cx, cy, isLeft, scale);
  const gazeDx = eye.pupilX * ic.rx * 0.35;
  const gazeDy = eye.pupilY * ic.ry * 0.25;

  // 眼皮闭合量：openness=1 完全睁开，openness=0 全闭；curve 控制上睑下沿的弧度（happy 时下凹形成⌣笑眼）
  const closeAmt = Math.max(0, Math.min(1, 1 - eye.openness));
  const curve = Math.max(-1, Math.min(1, eye.upperLidCurve));
  // 上眼皮遮罩高度：从 eye 顶部往下盖，最大盖到 eye 高度的 110%
  const lidH = 130 * scale * closeAmt;
  const lidTopY = cy - 80 * scale;
  // 下沿弧度：负 curve（happy 弯起）让中段更低（覆盖更多），形成笑眼
  const lidBottomMid = lidTopY + lidH + (curve < 0 ? -curve * 28 * scale : 0);
  const lidBottomEdge = lidTopY + lidH;
  // 眼线最低不透明度：闭眼时仍要看到那条"闭眼线"
  const lidLineOp = Math.max(0.7, lidOp);

  return (
    <g>
      <path d={scleraD} fill={SCLERA} />
      <g clipPath={`url(#${clipId})`}>
        <path d={refScleraShadow(cx, cy, isLeft, scale)} fill="url(#re-scleraShadow)" opacity={0.4} />
        <g transform={`translate(${gazeDx} ${gazeDy})`}>
        <ellipse cx={ic.cx} cy={ic.cy} rx={ic.rx} ry={ic.ry} fill="url(#re-iris)" />
        <path d={refIrisOuterRing(ic.cx, ic.cy, ic.rx, ic.ry)} fill="none" stroke="#5A1C08" strokeWidth={3 * scale} opacity={0.65} />
        <path d={refIrisBottomGlow(ic.cx, ic.cy, ic.rx, ic.ry)} fill="url(#re-irisBottom)" opacity={1.0} />
        <path d={refIrisInnerGlow(ic.cx, ic.cy, ic.rx, ic.ry)} fill="url(#re-innerGlow)" opacity={0.85} />
        <path d={refIrisRadialSlits(ic.cx, ic.cy, ic.rx, ic.ry)} fill="none" stroke="#FFB830" strokeWidth={1.6 * scale} opacity={0.55} strokeLinecap="round" />
        <path d={refIrisTopShadow(ic.cx, ic.cy, ic.rx, ic.ry)} fill="url(#re-irisTop)" opacity={0.55} />
        <path d={refIrisGlints(ic.cx, ic.cy, ic.rx, ic.ry)} fill="none" stroke="#FFF0A8" strokeWidth={1.6 * scale} opacity={0.6} strokeLinecap="round" />
        <ellipse cx={ic.cx - ic.rx * 0.12} cy={ic.cy - ic.ry * 0.18} rx={ic.rx * 0.72} ry={ic.ry * 0.55} fill="url(#re-cornea)" opacity={0.2} />
        <ellipse
          cx={ic.pupilCx} cy={ic.pupilCy}
          rx={ic.pupilRx * eye.pupilScale * 1.06} ry={ic.pupilRy * eye.pupilScale * 1.06}
          fill="none" stroke="url(#re-pupilRim)" strokeWidth={2.8 * scale} opacity={0.55}
        />
        <ellipse
          cx={ic.pupilCx} cy={ic.pupilCy}
          rx={ic.pupilRx * eye.pupilScale} ry={ic.pupilRy * eye.pupilScale}
          fill="url(#re-pupil)"
        />
        <ellipse
          cx={ic.pupilCx} cy={ic.pupilCy + ic.pupilRy * eye.pupilScale * 0.38}
          rx={ic.pupilRx * eye.pupilScale * 0.38} ry={ic.pupilRy * eye.pupilScale * 0.14}
          fill="url(#re-pupilFloor)" opacity={0.35 * hi}
        />
        <circle cx={h.main.x} cy={h.main.y} r={h.main.r * 1.4} fill="url(#re-mainCatchBloom)" opacity={hi * 0.45} />
        <circle cx={h.main.x} cy={h.main.y} r={h.main.r * 1.0} fill="url(#re-mainCatch)" opacity={hi * 1.0} />
        <circle cx={h.mainStar.x} cy={h.mainStar.y} r={h.mainStar.r * 1.2} fill="url(#re-softCatch)" opacity={hi * 0.85} />
        <circle cx={h.sub.x} cy={h.sub.y} r={h.sub.r * 1.1} fill="url(#re-softCatch)" opacity={hi * 0.8} />
        <circle cx={h.soft.x} cy={h.soft.y} r={h.soft.r * 1.3} fill="url(#re-amberCatch)" opacity={hi * 0.55} />
        {h.amber.map((a, i) => (
          <circle key={`a${i}`} cx={a.x} cy={a.y} r={a.r} fill="url(#re-amberCatch)" opacity={a.opacity * hi} />
        ))}
        {h.sparkles.map((sp, i) => (
          <circle key={`s${i}`} cx={sp.x} cy={sp.y} r={sp.r * 1.4} fill="url(#re-softCatch)" opacity={hi * (0.7 + i * 0.05)} />
        ))}
        <circle cx={h.innerCorner.x} cy={h.innerCorner.y} r={h.innerCorner.r + tear * scale * 1.2} fill="url(#re-softCatch)" opacity={hi * (0.28 + tear * 0.38)} />
        {tear > 0.04 && (
          <>
            <path d={refTearPool(ic.cx, ic.cy, ic.rx, ic.ry)} fill="url(#re-tear)" opacity={tear * 0.75} />
            {h.tearGlints.map((tg, i) => (
              <circle key={`t${i}`} cx={tg.x} cy={tg.y} r={tg.r + tear * scale * 1.2} fill="url(#re-softCatch)" opacity={tear * hi * 0.72} />
            ))}
          </>
        )}
        </g>
        {closeAmt > 0.02 && (
          <path
            d={[
              `M ${cx - 200 * scale} ${lidTopY}`,
              `L ${cx + 200 * scale} ${lidTopY}`,
              `L ${cx + 200 * scale} ${lidBottomEdge}`,
              `Q ${cx} ${lidBottomMid} ${cx - 200 * scale} ${lidBottomEdge}`,
              `Z`,
            ].join(' ')}
            fill={FACE_BG}
          />
        )}
      </g>
      <path d={refLowerLidShadow(cx, cy, isLeft, scale)} fill={LOWER_LID_SHADOW} opacity={0.1 * lidOp} />
      <path d={refLowerLidPath(cx, cy, isLeft, scale)} fill="none" stroke={LID_SOFT} strokeWidth={1.2 * scale} strokeLinecap="round" opacity={0.45 * lidOp} />
      <path d={refLowerLashes(cx, cy, isLeft, scale)} fill="none" stroke={LASH} strokeWidth={0.9 * scale} strokeLinecap="round" opacity={0.42 * lidOp} />
      {/* 上睫：窄遮罩 + 眼线 + 弯睫 + 外角束 */}
      <path d={refUpperLidThin(cx, cy, isLeft, scale)} fill={LID_MASK} opacity={lidOp * 0.18} />
      <path d={refUpperLashLine(cx, cy, isLeft, scale)} fill="none" stroke={LID_LINE} strokeWidth={1.6 * scale} strokeLinecap="round" strokeLinejoin="round" opacity={Math.max(0.55, lidOp * 0.6)} />
      <path d={refUpperLashesCurved(cx, cy, isLeft, scale)} fill="none" stroke={LASH} strokeWidth={1 * scale} strokeLinecap="round" opacity={0.58 * lidOp} />
      <path d={refOuterLashFan(cx, cy, isLeft, scale)} fill="none" stroke={LASH} strokeWidth={1.1 * scale} strokeLinecap="round" opacity={0.52 * lidOp} />
      <path d={refLidCreasePath(cx, cy, isLeft, scale)} fill="none" stroke={LID_CREASE} strokeWidth={1 * scale} strokeLinecap="round" opacity={0.22 * lidOp} />
    </g>
  );
}

export function ReferenceEyeDefs() {
  return (
    <>
      <linearGradient id="re-iris" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#A03818" />
        <stop offset="20%" stopColor="#D06020" />
        <stop offset="40%" stopColor="#F08830" />
        <stop offset="60%" stopColor="#F8A040" />
        <stop offset="78%" stopColor="#FFC860" />
        <stop offset="92%" stopColor="#FFE890" />
        <stop offset="100%" stopColor="#FFF8D8" />
      </linearGradient>
      <linearGradient id="re-irisTop" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#120604" stopOpacity={1} />
        <stop offset="100%" stopColor="#120604" stopOpacity={0} />
      </linearGradient>
      <linearGradient id="re-irisBottom" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#FFD860" stopOpacity={0.25} />
        <stop offset="100%" stopColor="#FFFCE0" stopOpacity={0.98} />
      </linearGradient>
      <linearGradient id="re-innerGlow" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#FFECA0" stopOpacity={0.85} />
        <stop offset="100%" stopColor="#FFB830" stopOpacity={0.12} />
      </linearGradient>
      <radialGradient id="re-pupil" cx="0.48" cy="0.32" r="0.62">
        <stop offset="0%" stopColor="#4A1B08" />
        <stop offset="35%" stopColor="#220A04" />
        <stop offset="70%" stopColor="#0A0402" />
        <stop offset="100%" stopColor="#000000" />
      </radialGradient>
      <radialGradient id="re-pupilRim" cx="0.5" cy="0.5" r="0.5">
        <stop offset="72%" stopColor="#FFB860" stopOpacity={0} />
        <stop offset="88%" stopColor="#E88830" stopOpacity={0.45} />
        <stop offset="100%" stopColor="#C06018" stopOpacity={0.25} />
      </radialGradient>
      <radialGradient id="re-pupilFloor" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0%" stopColor="#FFE8A0" stopOpacity={0.6} />
        <stop offset="100%" stopColor="#FFE8A0" stopOpacity={0} />
      </radialGradient>
      <radialGradient id="re-mainCatch" cx="0.42" cy="0.38" r="0.58">
        <stop offset="0%" stopColor="#FFFFFF" stopOpacity={1} />
        <stop offset="45%" stopColor="#FFFFFF" stopOpacity={0.92} />
        <stop offset="78%" stopColor="#FFF8E8" stopOpacity={0.35} />
        <stop offset="100%" stopColor="#FFFFFF" stopOpacity={0} />
      </radialGradient>
      <radialGradient id="re-mainCatchBloom" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0%" stopColor="#FFFFFF" stopOpacity={0.55} />
        <stop offset="100%" stopColor="#FFFFFF" stopOpacity={0} />
      </radialGradient>
      <radialGradient id="re-softCatch" cx="0.45" cy="0.42" r="0.55">
        <stop offset="0%" stopColor="#FFFFFF" stopOpacity={0.85} />
        <stop offset="70%" stopColor="#FFF8E8" stopOpacity={0.25} />
        <stop offset="100%" stopColor="#FFFFFF" stopOpacity={0} />
      </radialGradient>
      <radialGradient id="re-amberCatch" cx="0.5" cy="0.5" r="0.5">
        <stop offset="0%" stopColor="#FFE890" stopOpacity={0.75} />
        <stop offset="100%" stopColor="#FFE890" stopOpacity={0} />
      </radialGradient>
      <radialGradient id="re-cornea" cx="0.35" cy="0.28" r="0.65">
        <stop offset="0%" stopColor="#FFFFFF" stopOpacity={0.45} />
        <stop offset="55%" stopColor="#FFFFFF" stopOpacity={0.08} />
        <stop offset="100%" stopColor="#FFFFFF" stopOpacity={0} />
      </radialGradient>
      <linearGradient id="re-tear" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#FFFFFF" stopOpacity={0.88} />
        <stop offset="100%" stopColor="#C8E0F8" stopOpacity={0.38} />
      </linearGradient>
      <linearGradient id="re-scleraShadow" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#7A8498" stopOpacity={0.42} />
        <stop offset="100%" stopColor="#7A8498" stopOpacity={0} />
      </linearGradient>
    </>
  );
}

export function ReferenceBlushHatch({ cx, cy, isLeft, intensity, scale = 1 }: {
  cx: number; cy: number; isLeft: boolean; intensity: number; scale?: number;
}) {
  if (intensity <= 0) return null;
  return (
    <path
      d={refBlushHatch(cx, cy, isLeft, scale)}
      fill="none" stroke="#A85850" strokeWidth={1.5 * scale} strokeLinecap="round"
      opacity={intensity * 0.48}
    />
  );
}

export function ReferenceEyebrow({ cx, cy, isLeft, scale = 1 }: {
  cx: number; cy: number; isLeft: boolean; scale?: number;
}) {
  return (
    <path
      d={refEyebrowPath(cx, cy, isLeft, scale)}
      fill="none" stroke="#6E5040" strokeWidth={2.2 * scale} strokeLinecap="round" opacity={0.75}
    />
  );
}

export function ReferenceHairBack() {
  return (
    <>
      <path d={refHairSide(true)} fill="url(#re-hairSide)" opacity={0.85} />
      <path d={refHairSide(false)} fill="url(#re-hairSide)" opacity={0.85} />
    </>
  );
}

export function ReferenceHairFront() {
  return <path d={refHairBangs()} fill="url(#re-hair)" opacity={0.92} />;
}

export function ReferenceHair() {
  return (
    <>
      <ReferenceHairBack />
      <ReferenceHairFront />
    </>
  );
}

export function ReferenceHairDefs() {
  return (
    <>
      <linearGradient id="re-hair" x1="0.5" y1="0" x2="0.5" y2="1">
        <stop offset="0%" stopColor="#7A5038" />
        <stop offset="100%" stopColor="#5A3828" />
      </linearGradient>
      <linearGradient id="re-hairSide" x1="0" y1="0.5" x2="1" y2="0.5">
        <stop offset="0%" stopColor="#6A4428" />
        <stop offset="100%" stopColor="#4A3018" />
      </linearGradient>
    </>
  );
}
