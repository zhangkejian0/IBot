import { useEffect, useRef, useState } from 'react';
import { faceController } from '../runtime/controller';
import { cloneNeutral } from '../params/neutral';
import { faceDisplayViewBox } from './faceLayout';
import {
  AMBIENT_PRESETS, getAmbientExpression, subscribeAmbientExpression,
  type AmbientDescriptor, type AmbientExpressionId, type EyeStyle, type MouthKind, type PropKind,
} from './ambientExpression';
import { chooseAutoMouth, type AutoMouthChoice } from './autoMouth';
import { splitEyeGaze } from './eyeGaze';
import { buildHeadTransform } from './headTransform';

/**
 * LineFace ── 线条化（monoline）风格。
 * 纯描边、圆头线条、扁平不发光；表情由「氛围表情」预设驱动（与氛围风格共享）。
 * 底层用 controller 提供眨眼/呼吸/注视微动。
 */

type EyeMode = 'ring' | 'wide' | 'happy' | 'sleep' | 'angry' | 'dizzy' | 'star' | 'x' | 'blank';
type MouthMode = 'line' | 'smile' | 'bigSmile' | 'frown' | 'o' | 'wave' | 'cat' | 'none' | 'auto';

const BG = '#0E1016';
const SW = 9;

const VIEW_CX = 500;
const FACE_CY = 355;
const LEFT_EYE_CX = 388;
const RIGHT_EYE_CX = 612;
const EYE_R = 70;
const PUPIL_R = 16;
const MOUTH_CX = 500;
const MOUTH_CY = 470;
const MOUTH_W = 92;

const NEUTRAL_COLOR = '#EAE6DC';

/** 把颜色按比例混向白色，得到更亮的同色 */
function lighten(hex: string, amt: number) {
  const m = hex.replace('#', '');
  const r = parseInt(m.slice(0, 2), 16);
  const g = parseInt(m.slice(2, 4), 16);
  const b = parseInt(m.slice(4, 6), 16);
  const mix = (c: number) => Math.round(c + (255 - c) * amt);
  const h = (c: number) => mix(c).toString(16).padStart(2, '0');
  return `#${h(r)}${h(g)}${h(b)}`;
}

const EYE_MAP: Record<EyeStyle, EyeMode> = {
  normal: 'ring', wide: 'wide', star: 'star', angry: 'angry',
  sleepyLid: 'sleep', shy: 'ring', closedSmile: 'happy',
  dead: 'x', dizzy: 'dizzy', blank: 'blank', wink: 'ring',
};

const MOUTH_MAP: Record<MouthKind, MouthMode> = {
  auto: 'auto', smile: 'smile', bigSmile: 'bigSmile', open: 'o', flat: 'line',
  frown: 'frown', pout: 'line', cat: 'cat', wavy: 'wave', small: 'line', hidden: 'none',
};

function lineColor(d: AmbientDescriptor) {
  return d.useSkinGlow ? NEUTRAL_COLOR : d.aura;
}
function isAnimated(d: AmbientDescriptor) {
  return !!(d.shake || d.spin) ||
    d.prop === 'sweat' || d.prop === 'magic' || d.prop === 'camera' ||
    d.prop === 'food' || d.prop === 'anger' || d.eye === 'dizzy';
}

export function LineFace() {
  const [params, setParams] = useState(() => cloneNeutral());
  const [expr, setExpr] = useState<AmbientExpressionId>(() => getAmbientExpression());
  const [clock, setClock] = useState(0);
  const rafRef = useRef(0);

  useEffect(() => {
    faceController.setRenderCallback((p) => setParams(p));
    const offExpr = subscribeAmbientExpression((id) => setExpr(id));
    return () => { faceController.stop(); offExpr(); };
  }, []);

  const desc = (AMBIENT_PRESETS.find((p) => p.id === expr) ?? AMBIENT_PRESETS[0]).desc;
  const C = lineColor(desc);

  const animated = isAnimated(desc);
  useEffect(() => {
    if (!animated) { setClock(0); return; }
    const loop = (t: number) => { setClock(t); rafRef.current = requestAnimationFrame(loop); };
    rafRef.current = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(rafRef.current);
  }, [animated]);

  const t = clock;
  const lOp = Math.max(0.05, params.leftEye.openness);
  const rOp = Math.max(0.05, params.rightEye.openness);
  const gx = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilX : params.rightEye.pupilX) * 35;
  const gy = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilY : params.rightEye.pupilY) * 28;

  // wink：左眼弯笑，右眼睁睛
  const baseEye = EYE_MAP[desc.eye];
  const leftEye: EyeMode = desc.eye === 'wink' ? 'happy' : baseEye;
  const rightEye: EyeMode = baseEye;
  const mouth = MOUTH_MAP[desc.mouth];

  const Clight = lighten(C, 0.35);  // 描边顶部高光色
  const Ccatch = lighten(C, 0.8);   // 瞳孔反光点

  const breatheScale = 1 + Math.sin(t / 620) * 0.01;
  const tiltExtra = (desc.headTilt ?? 0) + (desc.spin ? Math.sin(t / 480) * 7 : 0);
  const shakeX = desc.shake ? Math.sin(t / 45) * desc.shake : 0;
  const headTransform = buildHeadTransform(VIEW_CX, FACE_CY, params, {
    tilt: tiltExtra,
    shakeX,
    scale: breatheScale,
  });

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', background: BG, overflow: 'hidden' }}>
      <div style={{
        position: 'absolute', inset: 0,
        background: `radial-gradient(ellipse 60% 55% at 50% 50%, ${C}10 0%, transparent 70%)`,
        transition: 'background 0.8s ease', pointerEvents: 'none',
      }} />

      <svg
        viewBox={faceDisplayViewBox()}
        preserveAspectRatio="xMidYMid meet"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
      >
        <defs>
          {/* 描边竖向渐变：顶部偏亮 → 底部本色，制造光从上来的质感 */}
          <linearGradient id="line-stroke" gradientUnits="userSpaceOnUse"
                          x1={VIEW_CX} y1={FACE_CY - 110} x2={VIEW_CX} y2={FACE_CY + 110}>
            <stop offset="0%" stopColor={Clight} />
            <stop offset="60%" stopColor={C} />
            <stop offset="100%" stopColor={C} />
          </linearGradient>
          {/* 柔和外发光（克制） */}
          <filter id="line-glow" x="-30%" y="-30%" width="160%" height="160%">
            <feGaussianBlur stdDeviation="3.2" result="b" />
            <feMerge>
              <feMergeNode in="b" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>

        <g transform={headTransform}
           fill="none" stroke="url(#line-stroke)" strokeWidth={SW}
           strokeLinecap="round" strokeLinejoin="round"
           filter="url(#line-glow)">
          <Eye cx={LEFT_EYE_CX} cy={FACE_CY} isLeft mode={leftEye}
               openness={lOp} px={gx('left')} py={gy('left')} color={C} catchC={Ccatch} />
          <Eye cx={RIGHT_EYE_CX} cy={FACE_CY} isLeft={false} mode={rightEye}
               openness={rOp} px={gx('right')} py={gy('right')} color={C} catchC={Ccatch} />
          <Mouth mode={mouth} curve={params.mouth.curve} openness={params.mouth.openness}
                 cornerLift={params.mouth.cornerLift}
                 isHappy={desc.eye === 'star'} t={t} />
          {desc.prop && <Prop kind={desc.prop} color={C} t={t} />}
        </g>
      </svg>

      <div style={{
        position: 'absolute', left: 0, right: 0, bottom: 'max(16px, env(safe-area-inset-bottom))',
        textAlign: 'center', color: `${C}AA`,
        fontSize: 12, letterSpacing: 7, fontWeight: 400,
        opacity: desc.label ? 1 : 0, transition: 'opacity 0.6s ease', pointerEvents: 'none',
      }}>
        {desc.label ?? ''}
      </div>
    </div>
  );
}

function star(x: number, y: number, s: number) {
  return `M ${x} ${y - s} L ${x + s * 0.3} ${y - s * 0.3} L ${x + s} ${y} L ${x + s * 0.3} ${y + s * 0.3} ` +
         `L ${x} ${y + s} L ${x - s * 0.3} ${y + s * 0.3} L ${x - s} ${y} L ${x - s * 0.3} ${y - s * 0.3} Z`;
}

function Eye({
  cx, cy, isLeft, mode, openness, px, py, color, catchC,
}: {
  cx: number; cy: number; isLeft: boolean; mode: EyeMode;
  openness: number; px: number; py: number; color: string; catchC: string;
}) {
  const r = EYE_R;

  if (mode === 'happy') {
    return <path d={`M ${cx - r} ${cy + r * 0.35} Q ${cx} ${cy - r * 0.75} ${cx + r} ${cy + r * 0.35}`} />;
  }
  if (mode === 'sleep') {
    return <path d={`M ${cx - r} ${cy - 2} Q ${cx} ${cy + r * 0.55} ${cx + r} ${cy - 2}`} />;
  }
  if (mode === 'angry') {
    const inner = isLeft ? cx + r : cx - r;
    const outer = isLeft ? cx - r : cx + r;
    return (
      <>
        <path d={`M ${outer} ${cy - r * 0.5} L ${inner} ${cy - r * 0.05}`} />
        <path d={`M ${cx - r * 0.7} ${cy + r * 0.35} Q ${cx} ${cy + r * 0.05} ${cx + r * 0.7} ${cy + r * 0.35}`} />
      </>
    );
  }
  if (mode === 'x') {
    const a = r * 0.62;
    return (
      <>
        <path d={`M ${cx - a} ${cy - a} L ${cx + a} ${cy + a}`} />
        <path d={`M ${cx + a} ${cy - a} L ${cx - a} ${cy + a}`} />
      </>
    );
  }
  if (mode === 'blank') {
    return <path d={`M ${cx - r * 0.5} ${cy} L ${cx + r * 0.5} ${cy}`} strokeWidth={SW * 0.8} />;
  }
  if (mode === 'dizzy') {
    let d = `M ${cx} ${cy} `;
    const steps = 30, turns = 2.2, maxR = r * 0.7;
    for (let i = 1; i <= steps; i++) {
      const a = (i / steps) * turns * Math.PI * 2;
      const rr = (i / steps) * maxR;
      d += `L ${(cx + Math.cos(a) * rr).toFixed(1)} ${(cy + Math.sin(a) * rr).toFixed(1)} `;
    }
    return <path d={d} strokeWidth={SW * 0.7} />;
  }
  if (mode === 'star') {
    const ry = Math.max(3, r * openness);
    return (
      <>
        <ellipse cx={cx} cy={cy} rx={r} ry={ry} />
        {openness > 0.25 && (
          <path d={`M ${cx - r * 0.55} ${cy - r * 0.42} Q ${cx - r * 0.1} ${cy - r * 0.72} ${cx + r * 0.32} ${cy - r * 0.55}`}
                strokeWidth={SW * 0.5} opacity={0.7} />
        )}
        {openness > 0.2 && <path d={star(cx + px, cy + py - 2, 18)} fill={color} stroke="none" />}
        {openness > 0.2 && <circle cx={cx + px - 6} cy={cy + py - 10} r={4} fill={catchC} stroke="none" />}
      </>
    );
  }

  // ring / wide
  const ry = Math.max(3, r * openness);
  const wide = mode === 'wide';
  const ringRx = wide ? r * 1.05 : r;
  const ringRy = wide ? Math.max(3, ry * 1.05) : ry;
  const blink = openness < 0.2;
  const pr = (wide ? PUPIL_R * 0.78 : PUPIL_R * 1.18) * Math.max(0.5, openness);
  const { rollX, rollY, pupilX: pdx, pupilY: pdy } = splitEyeGaze(px, py);
  const eyeCx = cx + rollX;
  const eyeCy = cy + rollY;
  const pcx = eyeCx + pdx;
  const pcy = eyeCy + pdy - 2;
  return (
    <>
      <ellipse cx={eyeCx} cy={eyeCy} rx={ringRx} ry={ringRy} />
      {/* 眼内上缘弧形反光 */}
      {!blink && (
        <path d={`M ${eyeCx - r * 0.55} ${eyeCy - r * 0.45} Q ${eyeCx - r * 0.05} ${eyeCy - r * 0.78} ${eyeCx + r * 0.34} ${eyeCy - r * 0.58}`}
              strokeWidth={SW * 0.5} opacity={0.65} />
      )}
      {!blink && (
        <>
          <circle cx={pcx} cy={pcy} r={pr} fill={color} stroke="none" />
          {/* 瞳孔高光点 */}
          <circle cx={pcx - pr * 0.4} cy={pcy - pr * 0.45} r={Math.max(2.5, pr * 0.3)} fill={catchC} stroke="none" />
        </>
      )}
    </>
  );
}

function Mouth({ mode, curve, openness, cornerLift = 0, isHappy, t }: {
  mode: MouthMode; curve: number; openness: number; cornerLift?: number; isHappy: boolean; t: number;
}) {
  const cx = MOUTH_CX, cy = MOUTH_CY + cornerLift * 12, w = MOUTH_W;
  // auto：沿用底层 FaceParams（curve/openness）分流，与 AmbientFace 对齐。
  // chooseAutoMouth 返回 'open'，LineFace 本地用 'o'，这里转一下名。
  const autoToLine: Record<AutoMouthChoice, Exclude<MouthMode, 'auto'>> = {
    bigSmile: 'bigSmile', frown: 'frown', open: 'o', cat: 'cat',
  };
  const resolved: Exclude<MouthMode, 'auto'> =
    mode === 'auto' ? autoToLine[chooseAutoMouth(curve, openness, isHappy)] : mode;
  if (resolved === 'none') return null;
  if (resolved === 'o') {
    const ry = 8 + Math.abs(Math.sin(t / 200)) * 10 + openness * 10;
    return <ellipse cx={cx} cy={cy} rx={ry * 0.8} ry={ry} />;
  }
  if (resolved === 'bigSmile') {
    return <path d={`M ${cx - w * 0.7} ${cy - 6} Q ${cx} ${cy + 28} ${cx + w * 0.7} ${cy - 6}`} />;
  }
  if (resolved === 'smile') {
    return <path d={`M ${cx - w * 0.55} ${cy - 2} Q ${cx} ${cy + 16} ${cx + w * 0.55} ${cy - 2}`} />;
  }
  if (resolved === 'frown') {
    return <path d={`M ${cx - w * 0.55} ${cy + 8} Q ${cx} ${cy - 14} ${cx + w * 0.55} ${cy + 8}`} />;
  }
  if (resolved === 'wave') {
    return <path d={`M ${cx - w * 0.55} ${cy} Q ${cx - w * 0.27} ${cy - 9} ${cx} ${cy} T ${cx + w * 0.55} ${cy}`} />;
  }
  if (resolved === 'cat') {
    const h = w * 0.3;
    return <path d={`M ${cx - h} ${cy} Q ${cx - h / 2} ${cy + 8} ${cx} ${cy} Q ${cx + h / 2} ${cy + 8} ${cx + h} ${cy}`} />;
  }
  return <path d={`M ${cx - w * 0.4} ${cy} L ${cx + w * 0.4} ${cy}`} />;
}

function Prop({ kind, color, t }: { kind: PropKind; color: string; t: number }) {
  const RX = RIGHT_EYE_CX, fill = { fill: color, stroke: 'none' as const };

  if (kind === 'question') {
    return <text x={RX + 110} y={FACE_CY - 64} fontSize={76} fontWeight={700} {...fill}
                 textAnchor="middle" style={{ fontFamily: 'system-ui' }}>?</text>;
  }
  if (kind === 'sweat') {
    const drip = (t / 18) % 50;
    const x = RX + 86, y = FACE_CY - 56 + drip;
    return <path d={`M ${x} ${y - 14} Q ${x + 8} ${y - 1} ${x} ${y + 5} Q ${x - 8} ${y - 1} ${x} ${y - 14} Z`} {...fill} />;
  }
  if (kind === 'magic') {
    const k = 0.5 + 0.5 * Math.sin(t / 240);
    return (
      <g {...fill}>
        <path d={star(LEFT_EYE_CX - 84, FACE_CY - 66, 10 + k * 6)} opacity={0.5 + k * 0.5} />
        <path d={star(RX + 84, FACE_CY - 52, 8 + (1 - k) * 6)} opacity={0.5 + (1 - k) * 0.5} />
        <path d={star(VIEW_CX, FACE_CY - 96, 7)} opacity={0.7} />
      </g>
    );
  }
  if (kind === 'anger') {
    const cx = RX + 62, cy = FACE_CY - 70, p = 0.7 + 0.3 * Math.abs(Math.sin(t / 200));
    return (
      <g opacity={p}>
        <path d={`M ${cx} ${cy} L ${cx} ${cy + 28}`} />
        <path d={`M ${cx - 14} ${cy + 12} L ${cx + 14} ${cy + 12}`} />
        <path d={`M ${cx - 12} ${cy - 2} L ${cx - 19} ${cy - 11}`} />
        <path d={`M ${cx + 12} ${cy - 2} L ${cx + 19} ${cy - 11}`} />
      </g>
    );
  }
  if (kind === 'crack') {
    return (
      <g strokeWidth={SW * 0.55} opacity={0.7}>
        <path d="M 400 250 L 426 305 L 408 355 L 444 420" />
        <path d="M 610 258 L 584 308 L 620 352 L 592 414" />
      </g>
    );
  }
  if (kind === 'cup') {
    const cx = MOUTH_CX, top = MOUTH_CY - 20, bot = MOUTH_CY + 56;
    return (
      <g>
        <path d={`M ${cx - 38} ${top} L ${cx + 38} ${top} L ${cx + 28} ${bot} L ${cx - 28} ${bot} Z`} />
        <path d={`M ${cx + 16} ${top} L ${cx + 30} ${top - 46}`} />
      </g>
    );
  }
  if (kind === 'food') {
    const cx = MOUTH_CX + 84, cy = MOUTH_CY;
    return <path d={`M ${cx} ${cy - 30} L ${cx + 28} ${cy + 22} L ${cx - 28} ${cy + 22} Z`} />;
  }
  if (kind === 'camera') {
    const flash = Math.max(0, Math.sin(t / 400)) ** 6;
    const cx = MOUTH_CX, cy = MOUTH_CY + 30;
    return (
      <g>
        {flash > 0.05 && <rect x={-40} y={150} width={1080} height={520} fill={color} stroke="none" opacity={flash * 0.35} />}
        <rect x={cx - 50} y={cy - 24} width={100} height={56} rx={10} />
        <path d={`M ${cx - 16} ${cy - 24} L ${cx - 8} ${cy - 36} L ${cx + 8} ${cy - 36} L ${cx + 16} ${cy - 24}`} />
        <circle cx={cx} cy={cy + 6} r={18} />
      </g>
    );
  }
  if (kind === 'moon') {
    return (
      <g>
        <path d="M 752 250 a 30 30 0 1 0 16 54 a 23 23 0 1 1 -16 -54 Z" {...fill} />
        <path d={star(694, 322, 6)} {...fill} opacity={0.85} />
        <path d={star(250, 282, 6)} {...fill} opacity={0.8} />
      </g>
    );
  }
  if (kind === 'glass') {
    const cx = RX + 36, cy = FACE_CY + 18;
    return (
      <g>
        <circle cx={cx} cy={cy} r={64} />
        <path d={`M ${cx + 46} ${cy + 46} L ${cx + 96} ${cy + 96}`} strokeWidth={SW * 1.4} />
      </g>
    );
  }
  return null;
}
