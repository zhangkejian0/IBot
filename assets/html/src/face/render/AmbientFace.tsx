import { useEffect, useRef, useState } from 'react';
import { faceController } from '../runtime/controller';
import { cloneNeutral } from '../params/neutral';
import { faceDisplayViewBox } from './faceLayout';
import {
  AMBIENT_PRESETS, getAmbientExpression, subscribeAmbientExpression,
  type AmbientDescriptor, type AmbientExpressionId, type EyeStyle, type MouthKind, type PropKind,
} from './ambientExpression';
import { getAmbientSkin, subscribeAmbientSkin, type AmbientSkin } from './ambientSkin';
import { chooseAutoMouth } from './autoMouth';

/**
 * AmbientFace ── 暗背景 + 柔光晕染 + 可爱 Q 眼。
 * 表情完全由「氛围表情」预设驱动（见 ambientExpression.ts）。
 * 底层仍用 controller 提供眨眼/呼吸/注视微动，但不再用 FSM 状态选表情。
 */

const VIEW_CX = 500;
const FACE_CY = 355;
const LEFT_EYE_CX = 395;
const RIGHT_EYE_CX = 605;
const EYE_BASE_R = 92;
const PUPIL_BASE_R = 50;
const HIGHLIGHT_MAIN_R = 15;
const HIGHLIGHT_SUB_R = 7;
const BLUSH_DX = 72;
const BLUSH_DY = 66;
const BLUSH_RX = 30;
const BLUSH_RY = 22;

const MOUTH_CX = 500;
const MOUTH_CY = 484;
const MOUTH_BASE_W = 62;

/** 通用星形/闪光路径：points=尖角数，inner=内凹半径 */
function starPath(cx: number, cy: number, outer: number, inner: number, points: number, rot: number) {
  const step = Math.PI / points;
  let d = '';
  for (let i = 0; i < points * 2; i++) {
    const rad = rot + i * step;
    const rr = i % 2 === 0 ? outer : inner;
    const x = cx + Math.cos(rad) * rr;
    const y = cy + Math.sin(rad) * rr;
    d += `${i === 0 ? 'M' : 'L'}${x.toFixed(2)} ${y.toFixed(2)} `;
  }
  return d + 'Z';
}

function isAnimated(d: AmbientDescriptor): boolean {
  return !!(d.shake || d.spin) ||
    d.prop === 'sweat' || d.prop === 'magic' || d.prop === 'camera' ||
    d.prop === 'food' || d.prop === 'anger';
}

export function AmbientFace() {
  const [params, setParams] = useState(() => cloneNeutral());
  const [expr, setExpr] = useState<AmbientExpressionId>(() => getAmbientExpression());
  const [skin, setSkin] = useState<AmbientSkin>(() => getAmbientSkin());
  const [clock, setClock] = useState(0);

  useEffect(() => {
    faceController.setRenderCallback((p) => setParams(p));
    const offExpr = subscribeAmbientExpression((id) => setExpr(id));
    const offSkin = subscribeAmbientSkin((s) => setSkin(s));
    return () => { faceController.stop(); offExpr(); offSkin(); };
  }, []);

  const preset = AMBIENT_PRESETS.find((p) => p.id === expr) ?? AMBIENT_PRESETS[0];
  const desc: AmbientDescriptor = preset.desc;

  // 动画时钟：仅当当前描述符需要时运行
  const animated = isAnimated(desc);
  const rafRef = useRef(0);
  useEffect(() => {
    if (!animated) { setClock(0); return; }
    const loop = (t: number) => { setClock(t); rafRef.current = requestAnimationFrame(loop); };
    rafRef.current = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(rafRef.current);
  }, [animated]);

  const lOp = Math.max(0.04, params.leftEye.openness);
  const rOp = Math.max(0.04, params.rightEye.openness);
  const gx = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilX : params.rightEye.pupilX) * 27;
  const gy = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilY : params.rightEye.pupilY) * 24;

  // wink：左眼弯笑，右眼睁睛
  const leftEyeStyle: EyeStyle = desc.eye === 'wink' ? 'closedSmile' : desc.eye;
  const rightEyeStyle: EyeStyle = desc.eye === 'wink' ? 'normal' : desc.eye;

  // 头部：呼吸缩放 + 预设倾角/抖动/晕摆
  const t = clock;
  const breatheScale = 1 + params.headBobY * 0.012;
  const tiltExtra = (desc.headTilt ?? 0) + (desc.spin ? Math.sin(t / 480) * 7 : 0);
  const shakeX = desc.shake ? Math.sin(t / 45) * desc.shake : 0;
  const headTransform =
    `translate(${(VIEW_CX + shakeX).toFixed(2)} ${(FACE_CY + params.headBobY).toFixed(2)}) ` +
    `rotate(${(params.headTilt + tiltExtra).toFixed(3)}) scale(${breatheScale.toFixed(4)}) ` +
    `translate(${-VIEW_CX} ${-FACE_CY})`;

  const blushOpacity = desc.blush * 0.9;
  const auraStrong = desc.glowBoost ?? 0;
  // 中性（默认）时情绪光跟随皮肤；明确情绪预设才用情绪色
  const emotionAura = desc.useSkinGlow ? skin.glow : desc.aura;

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', background: skin.bg, overflow: 'hidden', transition: 'background 0.8s ease' }}>
      {/* 屏幕环境光（随皮肤） */}
      <div style={{
        position: 'absolute', inset: 0,
        background: `radial-gradient(ellipse 95% 75% at 50% 58%, ${skin.glow}${auraStrong ? '5C' : '38'} 0%, ${skin.glow}12 40%, transparent 76%)`,
        transition: 'background 0.8s ease', pointerEvents: 'none',
      }} />
      {/* 顶部环境光（随皮肤） */}
      <div style={{
        position: 'absolute', inset: 0,
        background: `radial-gradient(ellipse 70% 35% at 50% 8%, ${skin.glow}1C 0%, transparent 70%)`,
        transition: 'background 0.8s ease', pointerEvents: 'none',
      }} />
      {/* 情绪点缀光（随表情） */}
      <div style={{
        position: 'absolute', inset: 0,
        background: `radial-gradient(ellipse 60% 45% at 50% 56%, ${emotionAura}24 0%, ${emotionAura}0A 45%, transparent 72%)`,
        transition: 'background 1.0s ease', pointerEvents: 'none',
      }} />

      <svg
        viewBox={faceDisplayViewBox()}
        preserveAspectRatio="xMidYMid meet"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
      >
        <defs>
          <radialGradient id="amb-eye" cx="0.4" cy="0.36" r="0.62">
            <stop offset="0%" stopColor={skin.eyeStops[0]} />
            <stop offset="55%" stopColor={skin.eyeStops[1]} />
            <stop offset="100%" stopColor={skin.eyeStops[2]} />
          </radialGradient>
          <radialGradient id="amb-pupil" cx="0.5" cy="0.4" r="0.6">
            <stop offset="0%" stopColor={skin.pupilStops[0]} />
            <stop offset="55%" stopColor={skin.pupilStops[1]} />
            <stop offset="100%" stopColor={skin.pupilStops[2]} />
          </radialGradient>
          <radialGradient id="amb-aura" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%" stopColor={emotionAura} stopOpacity={0.85} />
            <stop offset="100%" stopColor={emotionAura} stopOpacity={0} />
          </radialGradient>
          <radialGradient id="amb-blush" cx="0.5" cy="0.45" r="0.5">
            <stop offset="0%" stopColor={skin.blush} stopOpacity={0.95} />
            <stop offset="60%" stopColor={skin.blush} stopOpacity={0.5} />
            <stop offset="100%" stopColor={skin.blush} stopOpacity={0} />
          </radialGradient>
          <radialGradient id="amb-gloss" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%" stopColor="#FFFFFF" stopOpacity={0.9} />
            <stop offset="45%" stopColor="#FFFFFF" stopOpacity={0.35} />
            <stop offset="100%" stopColor="#FFFFFF" stopOpacity={0} />
          </radialGradient>
          <filter id="amb-soft" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="24" />
          </filter>
          <filter id="amb-gray">
            <feColorMatrix type="saturate" values="0.12" />
          </filter>
        </defs>

        <g transform={headTransform} filter={desc.desaturate ? 'url(#amb-gray)' : undefined}>
          {/* 眼后大块情绪柔光 */}
          <ellipse cx={LEFT_EYE_CX} cy={FACE_CY} rx={EYE_BASE_R * 1.9} ry={EYE_BASE_R * 1.7}
                   fill="url(#amb-aura)" filter="url(#amb-soft)" opacity={1 + auraStrong * 0.6} />
          <ellipse cx={RIGHT_EYE_CX} cy={FACE_CY} rx={EYE_BASE_R * 1.9} ry={EYE_BASE_R * 1.7}
                   fill="url(#amb-aura)" filter="url(#amb-soft)" opacity={1 + auraStrong * 0.6} />

          {/* 腮红 */}
          {blushOpacity > 0 && (
            <>
              <ellipse cx={LEFT_EYE_CX - BLUSH_DX} cy={FACE_CY + BLUSH_DY} rx={BLUSH_RX} ry={BLUSH_RY}
                       fill="url(#amb-blush)" opacity={blushOpacity} />
              <ellipse cx={RIGHT_EYE_CX + BLUSH_DX} cy={FACE_CY + BLUSH_DY} rx={BLUSH_RX} ry={BLUSH_RY}
                       fill="url(#amb-blush)" opacity={blushOpacity} />
            </>
          )}

          <Eye cx={LEFT_EYE_CX} cy={FACE_CY} isLeft style={leftEyeStyle}
               openness={lOp} pupilDx={gx('left')} pupilDy={gy('left')}
               pupilScale={params.leftEye.pupilScale} highlightOn={params.leftEye.highlightOn}
               aura={emotionAura} hi={skin.highlight} bg={skin.bg} />
          <Eye cx={RIGHT_EYE_CX} cy={FACE_CY} isLeft={false} style={rightEyeStyle}
               openness={rOp} pupilDx={gx('right')} pupilDy={gy('right')}
               pupilScale={params.rightEye.pupilScale} highlightOn={params.rightEye.highlightOn}
               aura={emotionAura} hi={skin.highlight} bg={skin.bg} />

          <Mouth kind={desc.mouth}
                 curve={params.mouth.curve} openness={params.mouth.openness} width={params.mouth.width}
                 isHappy={desc.eye === 'star'} t={t} hi={skin.highlight} />

          {desc.prop && <Prop kind={desc.prop} t={t} aura={emotionAura} />}
        </g>
      </svg>

      {/* 状态文字 */}
      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 'max(14px, env(safe-area-inset-bottom))',
        textAlign: 'center', color: 'rgba(255,255,255,0.6)',
        fontSize: 13, letterSpacing: 6, fontWeight: 300,
        textShadow: `0 0 14px ${emotionAura}66`,
        opacity: desc.label ? 1 : 0, transition: 'opacity 0.6s ease', pointerEvents: 'none',
      }}>
        {desc.label ?? ''}
      </div>
    </div>
  );
}

function Eye({
  cx, cy, isLeft, style, openness, pupilDx, pupilDy, pupilScale, highlightOn, aura, hi, bg,
}: {
  cx: number; cy: number; isLeft: boolean; style: EyeStyle;
  openness: number; pupilDx: number; pupilDy: number;
  pupilScale: number; highlightOn: number; aura: string; hi: string; bg: string;
}) {
  const rx = EYE_BASE_R;
  // 数值保险：避免异常帧把 NaN 传给 SVG 属性
  if (!Number.isFinite(openness)) openness = 0.04;
  if (!Number.isFinite(pupilScale)) pupilScale = 1;

  // ─── 弯笑眼 ^_^ ───
  if (style === 'closedSmile') {
    const d = `M ${cx - rx * 0.8} ${cy + 4} Q ${cx} ${cy - rx * 0.62} ${cx + rx * 0.8} ${cy + 4}`;
    return <path d={d} fill="none" stroke={hi} strokeWidth={13} strokeLinecap="round" />;
  }

  // ─── 晕倒/错误 ✕ ✕ ───
  if (style === 'dead') {
    const a = rx * 0.52;
    return (
      <g stroke="#E8E2F0" strokeWidth={12} strokeLinecap="round">
        <line x1={cx - a} y1={cy - a} x2={cx + a} y2={cy + a} />
        <line x1={cx + a} y1={cy - a} x2={cx - a} y2={cy + a} />
      </g>
    );
  }

  // ─── 晕眩 @ @ 漩涡 ───
  if (style === 'dizzy') {
    let d = `M ${cx} ${cy} `;
    const turns = 2.4, steps = 40, maxR = rx * 0.62;
    for (let i = 1; i <= steps; i++) {
      const a = (i / steps) * turns * Math.PI * 2;
      const r = (i / steps) * maxR;
      d += `L ${(cx + Math.cos(a) * r).toFixed(1)} ${(cy + Math.sin(a) * r).toFixed(1)} `;
    }
    return <path d={d} fill="none" stroke="#C9BEE6" strokeWidth={7} strokeLinecap="round" strokeLinejoin="round" />;
  }

  // ─── 石化 空白竖眼 ───
  if (style === 'blank') {
    const ry = rx * 0.92;
    return (
      <g>
        <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="#C8CCD6" />
        <line x1={cx} y1={cy - ry * 0.5} x2={cx} y2={cy + ry * 0.5}
              stroke="#7C8290" strokeWidth={8} strokeLinecap="round" />
      </g>
    );
  }

  // ─── 以下都基于「圆眼白 + 瞳孔」骨架 ───
  // 各样式对开合/瞳孔的修正
  let openK = openness;
  let pupilK = 1;
  let pupilDownPush = 0;
  let lidSlant = 0;            // 上眼睑斜压（angry）
  if (style === 'wide')      { openK = Math.max(openness, 0.95); pupilK = 0.62; }
  if (style === 'sleepyLid') { openK = Math.min(openness, 0.42); pupilDownPush = 18; }
  if (style === 'shy')       { openK = Math.min(openness, 0.7);  pupilDownPush = -10; }
  if (style === 'angry')     { lidSlant = 1; pupilK = 0.9; }

  const ry = rx * openK;
  const pupilRx = PUPIL_BASE_R * pupilScale * pupilK;
  const pupilRy = PUPIL_BASE_R * pupilScale * pupilK * Math.max(0.25, openK);
  const pupilCx = cx + pupilDx;
  const pupilCy = cy + pupilDy - 4 + pupilDownPush;

  // ─── 星星眼 ★ ───
  if (style === 'star' && openK > 0.3) {
    const starOuter = pupilRx * 1.18;
    const starInner = starOuter * 0.44;
    const sx = pupilCx, sy = pupilCy + 2;
    return (
      <g>
        <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="url(#amb-eye)" />
        <path d={starPath(sx, sy, starOuter * 1.25, starInner * 1.25, 5, -Math.PI / 2)}
              fill={aura} opacity={0.45} filter="url(#amb-soft)" />
        <path d={starPath(sx, sy, starOuter, starInner, 5, -Math.PI / 2)} fill="url(#amb-pupil)" />
        <circle cx={sx - starOuter * 0.18} cy={sy - starOuter * 0.3} r={HIGHLIGHT_SUB_R * 1.1} fill={hi} opacity={0.95} />
        <path d={starPath(cx + (isLeft ? -1 : 1) * rx * 0.5, cy - ry * 0.5, 11, 3.6, 4, -Math.PI / 2)}
              fill={hi} opacity={0.85} />
      </g>
    );
  }

  const hx = pupilCx + (isLeft ? 1 : -1) * pupilRx * 0.32 - 14;
  const hy = pupilCy - pupilRy * 0.45;
  const hx2 = pupilCx + (isLeft ? -1 : 1) * pupilRx * 0.4;
  const hy2 = pupilCy + pupilRy * 0.42;

  return (
    <g>
      <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="url(#amb-eye)" />
      <ellipse cx={pupilCx} cy={pupilCy} rx={pupilRx} ry={pupilRy} fill="url(#amb-pupil)" />

      {openK > 0.2 && (
        <ellipse cx={pupilCx} cy={pupilCy + pupilRy * 0.42} rx={pupilRx * 0.74} ry={pupilRy * 0.5}
                 fill={aura} opacity={0.5 * highlightOn} />
      )}
      {openK > 0.12 && (
        <circle cx={hx} cy={hy} r={HIGHLIGHT_MAIN_R * 2.1 * Math.max(0.5, openK)}
                fill="url(#amb-gloss)" opacity={0.9 * highlightOn} />
      )}
      {openK > 0.12 && (
        <path d={starPath(hx, hy, HIGHLIGHT_MAIN_R * 1.15 * Math.max(0.5, openK),
                          HIGHLIGHT_MAIN_R * 0.4 * Math.max(0.5, openK), 4, -Math.PI / 2)}
              fill={hi} opacity={0.98 * highlightOn} />
      )}
      {openK > 0.2 && (
        <circle cx={hx2} cy={hy2} r={HIGHLIGHT_SUB_R * Math.max(0.5, openK)} fill={hi} opacity={0.78 * highlightOn} />
      )}

      {/* 生气：上眼睑内低外高斜压 */}
      {lidSlant > 0 && (
        <path
          d={[
            `M ${cx - rx - 2} ${cy - ry - 2}`,
            `L ${cx + rx + 2} ${cy - ry - 2}`,
            `L ${cx + rx + 2} ${cy - ry + (isLeft ? ry * 1.5 : ry * 0.4)}`,
            `L ${cx - rx - 2} ${cy - ry + (isLeft ? ry * 0.4 : ry * 1.5)}`,
            'Z',
          ].join(' ')}
          fill={bg}
        />
      )}
    </g>
  );
}

function Mouth({
  kind, curve, openness, width, isHappy, t, hi,
}: {
  kind: MouthKind; curve: number; openness: number; width: number; isHappy: boolean; t: number; hi: string;
}) {
  const cx = MOUTH_CX, cy = MOUTH_CY, mw = MOUTH_BASE_W * width;

  if (kind === 'hidden') return null;

  if (kind === 'flat') {
    return <line x1={cx - mw * 0.7} y1={cy} x2={cx + mw * 0.7} y2={cy}
                 stroke="rgba(255,255,255,0.6)" strokeWidth={4} strokeLinecap="round" />;
  }

  if (kind === 'frown') {
    const d = `M ${cx - mw * 0.7} ${cy + 8} Q ${cx} ${cy - 12} ${cx + mw * 0.7} ${cy + 8}`;
    return <path d={d} fill="none" stroke="rgba(255,200,200,0.8)" strokeWidth={5} strokeLinecap="round" />;
  }

  if (kind === 'open') {
    const r = 16 + (openness > 0.05 ? openness * 16 : 0) + (t ? Math.abs(Math.sin(t / 180)) * 6 : 0);
    return <ellipse cx={cx} cy={cy} rx={r * 0.78} ry={r * 0.7} fill="rgba(255,170,190,0.78)" />;
  }

  if (kind === 'wavy') {
    const d = `M ${cx - mw * 0.7} ${cy} Q ${cx - mw * 0.35} ${cy - 7} ${cx} ${cy} ` +
              `T ${cx + mw * 0.7} ${cy}`;
    return <path d={d} fill="none" stroke="rgba(255,255,255,0.6)" strokeWidth={4} strokeLinecap="round" />;
  }

  if (kind === 'smile') {
    const d = `M ${cx - mw * 0.85} ${cy - 2} Q ${cx} ${cy + 20} ${cx + mw * 0.85} ${cy - 2}`;
    return <path d={d} fill="none" stroke={hi} strokeWidth={5} strokeLinecap="round" opacity={0.92} />;
  }

  if (kind === 'cat') {
    const half = mw * 0.45;
    const d = `M ${cx - half} ${cy} Q ${cx - half / 2} ${cy + 6} ${cx} ${cy} Q ${cx + half / 2} ${cy + 6} ${cx + half} ${cy}`;
    return <path d={d} fill="none" stroke="rgba(255,255,255,0.6)" strokeWidth={3.5} strokeLinecap="round" />;
  }

  if (kind === 'small' || kind === 'pout') {
    const half = mw * 0.4;
    return <line x1={cx - half} y1={cy} x2={cx + half} y2={cy} stroke="rgba(255,255,255,0.55)" strokeWidth={3.5} strokeLinecap="round" />;
  }

  if (kind === 'bigSmile') {
    const amp = 24;
    const d = `M ${cx - mw * 0.92} ${cy - 2} Q ${cx} ${cy + amp} ${cx + mw * 0.92} ${cy - 2}`;
    return (
      <g>
        <path d={d} fill="none" stroke={hi} strokeWidth={11} strokeLinecap="round" opacity={0.18} />
        <path d={d} fill="none" stroke={hi} strokeWidth={5} strokeLinecap="round" opacity={0.95} />
      </g>
    );
  }

  // kind === 'auto' → 沿用参数驱动（阈值统一见 autoMouth.ts）
  switch (chooseAutoMouth(curve, openness, isHappy)) {
    case 'bigSmile': {
      const amp = 14 + openness * 16 + Math.max(0, curve) * 12;
      const d = `M ${cx - mw * 0.92} ${cy - 2} Q ${cx} ${cy + amp} ${cx + mw * 0.92} ${cy - 2}`;
      return (
        <g>
          <path d={d} fill="none" stroke={hi} strokeWidth={11} strokeLinecap="round" opacity={0.18} />
          <path d={d} fill="none" stroke={hi} strokeWidth={5} strokeLinecap="round" opacity={0.95} />
        </g>
      );
    }
    case 'frown': {
      const amp = 10 + (-curve) * 10;
      const d = `M ${cx - mw * 0.7} ${cy + amp * 0.4} Q ${cx} ${cy - amp} ${cx + mw * 0.7} ${cy + amp * 0.4}`;
      return <path d={d} fill="none" stroke="rgba(255,255,255,0.75)" strokeWidth={4} strokeLinecap="round" />;
    }
    case 'open': {
      const r = 5 + openness * 16;
      return <ellipse cx={cx} cy={cy} rx={r * 0.78} ry={r * 0.62} fill="rgba(255,180,200,0.7)" />;
    }
    default: { // cat
      const half = mw * 0.45;
      const d = `M ${cx - half} ${cy} Q ${cx - half / 2} ${cy + 5} ${cx} ${cy} Q ${cx + half / 2} ${cy + 5} ${cx + half} ${cy}`;
      return <path d={d} fill="none" stroke="rgba(255,255,255,0.55)" strokeWidth={3.5} strokeLinecap="round" />;
    }
  }
}

function Prop({ kind, t, aura }: { kind: PropKind; t: number; aura: string }) {
  // 流汗：水滴沿太阳穴滑落
  if (kind === 'sweat') {
    const drip = ((t / 18) % 60);
    const x = RIGHT_EYE_CX + 96;
    const y = FACE_CY - 70 + drip;
    return (
      <path d={`M ${x} ${y - 16} Q ${x + 9} ${y - 2} ${x} ${y + 6} Q ${x - 9} ${y - 2} ${x} ${y - 16} Z`}
            fill="#8FD0FF" opacity={0.85} />
    );
  }

  // 饮料杯（盖住嘴） + 吸管
  if (kind === 'cup') {
    const cx = MOUTH_CX, top = MOUTH_CY - 28, bot = MOUTH_CY + 70;
    return (
      <g>
        <path d={`M ${cx - 46} ${top} L ${cx + 46} ${top} L ${cx + 34} ${bot} L ${cx - 34} ${bot} Z`}
              fill="#EDEFF6" stroke="#C7CCDA" strokeWidth={2} />
        <rect x={cx - 46} y={top - 6} width={92} height={10} rx={4} fill="#D7DCEA" />
        <line x1={cx + 18} y1={top - 6} x2={cx + 34} y2={top - 60} stroke="#FF8FB0" strokeWidth={7} strokeLinecap="round" />
      </g>
    );
  }

  // 饭团（吃东西）
  if (kind === 'food') {
    const cx = MOUTH_CX + 92, cy = MOUTH_CY + 6;
    return (
      <g>
        <path d={`M ${cx} ${cy - 34} L ${cx + 32} ${cy + 24} L ${cx - 32} ${cy + 24} Z`}
              fill="#FFFFFF" stroke="#E6E2D6" strokeWidth={2} strokeLinejoin="round" />
        <rect x={cx - 14} y={cy - 6} width={28} height={22} rx={3} fill="#3A3F4C" />
      </g>
    );
  }

  // 相机 + 闪光
  if (kind === 'camera') {
    const flash = Math.max(0, Math.sin(t / 400)) ** 6;
    const cx = MOUTH_CX, cy = MOUTH_CY + 36;
    return (
      <g>
        {flash > 0.05 && <rect x={-40} y={150} width={1080} height={520} fill="#FFFFFF" opacity={flash * 0.6} />}
        <rect x={cx - 56} y={cy - 26} width={112} height={64} rx={10} fill="#2A2E3C" stroke="#4A5066" strokeWidth={2} />
        <rect x={cx - 18} y={cy - 38} width={36} height={14} rx={4} fill="#2A2E3C" />
        <circle cx={cx} cy={cy + 6} r={20} fill="#10131C" stroke="#6AA8FF" strokeWidth={3} />
        <circle cx={cx} cy={cy + 6} r={9} fill="#1B2740" />
        <circle cx={cx + 38} cy={cy - 14} r={4} fill="#FF7A7A" />
      </g>
    );
  }

  // 夜间模式：月亮 + 星
  if (kind === 'moon') {
    return (
      <g fill="#DCE4FF">
        <path d="M 760 250 a 30 30 0 1 0 14 56 a 24 24 0 1 1 -14 -56 Z" opacity={0.95} />
        <path d={starPath(700, 320, 7, 3, 4, -Math.PI / 2)} opacity={0.9} />
        <path d={starPath(800, 360, 5, 2.2, 4, -Math.PI / 2)} opacity={0.8} />
        <path d={starPath(250, 280, 6, 2.6, 4, -Math.PI / 2)} opacity={0.85} />
      </g>
    );
  }

  // 魔法环绕星
  if (kind === 'magic') {
    const pts = [0, 1, 2, 3, 4].map((i) => {
      const a = t / 700 + (i / 5) * Math.PI * 2;
      return { x: VIEW_CX + Math.cos(a) * 250, y: FACE_CY + Math.sin(a) * 150, k: 0.5 + 0.5 * Math.sin(t / 200 + i) };
    });
    return (
      <g fill={aura}>
        {pts.map((p, i) => (
          <path key={i} d={starPath(p.x, p.y, 9 + p.k * 6, 3.5, 4, -Math.PI / 2)} opacity={0.5 + p.k * 0.5} />
        ))}
      </g>
    );
  }

  // 疑惑问号
  if (kind === 'question') {
    return (
      <text x={RIGHT_EYE_CX + 120} y={FACE_CY - 80} fontSize={90} fontWeight={700}
            fill="#9EE8C4" textAnchor="middle" style={{ fontFamily: 'system-ui' }}>?</text>
    );
  }

  // 怒气青筋
  if (kind === 'anger') {
    const cx = RIGHT_EYE_CX + 70, cy = FACE_CY - 80;
    const pulse = 0.7 + 0.3 * Math.abs(Math.sin(t / 200));
    return (
      <g stroke="#FF5A6E" strokeWidth={6} strokeLinecap="round" fill="none" opacity={pulse}>
        <path d={`M ${cx} ${cy} L ${cx} ${cy + 34}`} />
        <path d={`M ${cx - 16} ${cy + 14} L ${cx + 16} ${cy + 14}`} />
        <path d={`M ${cx - 14} ${cy - 2} L ${cx - 22} ${cy - 12}`} />
        <path d={`M ${cx + 14} ${cy - 2} L ${cx + 22} ${cy - 12}`} />
      </g>
    );
  }

  // 石化裂纹
  if (kind === 'crack') {
    return (
      <g stroke="#5A606E" strokeWidth={3} fill="none" opacity={0.7}>
        <path d="M 400 230 L 430 300 L 410 360 L 450 430" />
        <path d="M 610 240 L 580 300 L 620 350 L 590 420" />
        <path d="M 500 440 L 520 490 L 500 540" />
      </g>
    );
  }

  // 放大镜观察
  if (kind === 'glass') {
    const cx = RIGHT_EYE_CX + 30, cy = FACE_CY + 20;
    return (
      <g>
        <circle cx={cx} cy={cy} r={70} fill="#BfE4FF" fillOpacity={0.12} stroke="#A9D6FF" strokeWidth={8} />
        <circle cx={cx} cy={cy} r={70} fill="none" stroke="#FFFFFF" strokeWidth={2} opacity={0.5} />
        <line x1={cx + 50} y1={cy + 50} x2={cx + 110} y2={cy + 110} stroke="#7FB7E6" strokeWidth={16} strokeLinecap="round" />
        <path d={`M ${cx - 36} ${cy - 30} A 50 50 0 0 1 ${cx + 6} ${cy - 46}`} stroke="#FFFFFF" strokeWidth={5} fill="none" opacity={0.6} />
      </g>
    );
  }

  return null;
}
