import { useEffect, useRef, useState } from 'react';
import { faceController } from '../runtime/controller';
import { cloneNeutral } from '../params/neutral';
import {
  AMBIENT_PRESETS, getAmbientExpression, subscribeAmbientExpression,
  type AmbientExpressionId, type EyeStyle, type MouthKind, type PropKind,
} from './ambientExpression';
import { chooseAutoMouth } from './autoMouth';

/**
 * KawaiiFace ── 卡哇伊麻薯团子风格。
 * 设计方式完全不同于氛围/线条：亮色粉彩背景 + 圆滚滚团子身体 + 小短手 +
 * 大腮红 + Q 弹挤压呼吸；脸只是身体上很小的一部分。
 * 同样由「氛围表情」预设驱动。
 */

const VB_W = 600;
const VB_H = 640;
const BODY_CX = 300;
const BODY_CY = 340;
const BODY_RX = 188;
const BODY_RY = 202;
const EYE_DX = 76;
const EYE_CY = 322;
const CHEEK_DX = 150;
const CHEEK_CY = 372;
const MOUTH_CY = 374;

const EYE_DARK = '#4A3A52';
const CHEEK = '#FF9DBB';

/** 混向白：得到粉彩浅色 */
function pastel(hex: string, amt: number) {
  const m = hex.replace('#', '');
  const r = parseInt(m.slice(0, 2), 16), g = parseInt(m.slice(2, 4), 16), b = parseInt(m.slice(4, 6), 16);
  const mix = (c: number) => Math.round(c + (255 - c) * amt).toString(16).padStart(2, '0');
  return `#${mix(r)}${mix(g)}${mix(b)}`;
}
/** 混向黑：得到更深的同色（描边/阴影） */
function deepen(hex: string, amt: number) {
  const m = hex.replace('#', '');
  const r = parseInt(m.slice(0, 2), 16), g = parseInt(m.slice(2, 4), 16), b = parseInt(m.slice(4, 6), 16);
  const mix = (c: number) => Math.round(c * (1 - amt)).toString(16).padStart(2, '0');
  return `#${mix(r)}${mix(g)}${mix(b)}`;
}

export function KawaiiFace() {
  const [params, setParams] = useState(() => cloneNeutral());
  const [expr, setExpr] = useState<AmbientExpressionId>(() => getAmbientExpression());
  const [clock, setClock] = useState(0);
  const rafRef = useRef(0);

  useEffect(() => {
    faceController.setRenderCallback((p) => setParams(p));
    const offExpr = subscribeAmbientExpression((id) => setExpr(id));
    const loop = (t: number) => { setClock(t); rafRef.current = requestAnimationFrame(loop); };
    rafRef.current = requestAnimationFrame(loop);
    return () => { faceController.stop(); offExpr(); cancelAnimationFrame(rafRef.current); };
  }, []);

  const desc = (AMBIENT_PRESETS.find((p) => p.id === expr) ?? AMBIENT_PRESETS[0]).desc;
  const t = clock;

  const accent = desc.aura;                 // 情绪主色
  const bodyCol = desc.desaturate ? '#D9D6DE' : pastel(accent, 0.66);
  const bodyDeep = deepen(bodyCol, 0.12);
  const bgTop = desc.desaturate ? '#E9E7EC' : pastel(accent, 0.86);
  const bgBot = desc.desaturate ? '#D7D4DC' : pastel(accent, 0.7);

  const lOp = Math.max(0.06, params.leftEye.openness);
  const rOp = Math.max(0.06, params.rightEye.openness);
  const gx = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilX : params.rightEye.pupilX) * 8;
  const gy = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilY : params.rightEye.pupilY) * 6;

  // Q 弹挤压呼吸：横向胀一点、纵向压一点（来回）
  const squash = Math.sin(t / 620) * 0.035;
  const sx = 1 + squash;
  const sy = 1 - squash * 0.85;
  const bob = Math.sin(t / 620) * 5 + params.headBobY;

  const baseEye = EYE_MAP[desc.eye];
  const leftEye: EyeMode = desc.eye === 'wink' ? 'happy' : baseEye;
  const rightEye: EyeMode = baseEye;
  const mouth = MOUTH_MAP[desc.mouth];

  // 腮红强度（卡哇伊默认就有一点）
  const blush = Math.max(0.5, desc.blush);

  const tiltExtra = (desc.headTilt ?? 0) * 0.6 + (desc.spin ? Math.sin(t / 480) * 6 : 0);
  const shakeX = desc.shake ? Math.sin(t / 45) * desc.shake : 0;

  const bodyTransform =
    `translate(${(BODY_CX + shakeX).toFixed(2)} ${(BODY_CY + bob).toFixed(2)}) ` +
    `rotate(${tiltExtra.toFixed(2)}) scale(${sx.toFixed(4)} ${sy.toFixed(4)}) translate(${-BODY_CX} ${-BODY_CY})`;

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', overflow: 'hidden',
                  background: `linear-gradient(160deg, ${bgTop} 0%, ${bgBot} 100%)`,
                  transition: 'background 0.7s ease' }}>
      <svg viewBox={`0 0 ${VB_W} ${VB_H}`} preserveAspectRatio="xMidYMid meet"
           style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}>
        <defs>
          <radialGradient id="kw-body" cx="0.42" cy="0.32" r="0.75">
            <stop offset="0%" stopColor={pastel(bodyCol, 0.4)} />
            <stop offset="60%" stopColor={bodyCol} />
            <stop offset="100%" stopColor={bodyDeep} />
          </radialGradient>
          <radialGradient id="kw-cheek" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%" stopColor={CHEEK} stopOpacity={0.9} />
            <stop offset="100%" stopColor={CHEEK} stopOpacity={0} />
          </radialGradient>
          <filter id="kw-soft" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="10" />
          </filter>
        </defs>

        {/* 落地软阴影 */}
        <ellipse cx={BODY_CX} cy={BODY_CY + BODY_RY + 26} rx={BODY_RX * (0.82 + squash)} ry={26}
                 fill={deepen(bgBot, 0.18)} opacity={0.5} filter="url(#kw-soft)" />

        {/* 背景飘浮小装饰 */}
        <BgSparkle accent={accent} t={t} desaturate={!!desc.desaturate} />

        <g transform={bodyTransform} filter={desc.desaturate ? undefined : undefined}>
          {/* 小短手 */}
          <ellipse cx={BODY_CX - BODY_RX + 6} cy={BODY_CY + 60} rx={26} ry={34}
                   fill="url(#kw-body)" transform={`rotate(-18 ${BODY_CX - BODY_RX + 6} ${BODY_CY + 60})`} />
          <ellipse cx={BODY_CX + BODY_RX - 6} cy={BODY_CY + 60} rx={26} ry={34}
                   fill="url(#kw-body)" transform={`rotate(18 ${BODY_CX + BODY_RX - 6} ${BODY_CY + 60})`} />

          {/* 团子身体 */}
          <ellipse cx={BODY_CX} cy={BODY_CY} rx={BODY_RX} ry={BODY_RY} fill="url(#kw-body)" />
          {/* 顶部高光 */}
          <ellipse cx={BODY_CX - 44} cy={BODY_CY - BODY_RY * 0.52} rx={70} ry={34}
                   fill="#FFFFFF" opacity={0.28} filter="url(#kw-soft)" />

          {/* 腮红 */}
          <ellipse cx={BODY_CX - CHEEK_DX} cy={CHEEK_CY} rx={34} ry={24} fill="url(#kw-cheek)" opacity={blush} />
          <ellipse cx={BODY_CX + CHEEK_DX} cy={CHEEK_CY} rx={34} ry={24} fill="url(#kw-cheek)" opacity={blush} />

          {/* 眼睛 */}
          <Eye cx={BODY_CX - EYE_DX} cy={EYE_CY} isLeft mode={leftEye} openness={lOp} px={gx('left')} py={gy('left')} accent={accent} />
          <Eye cx={BODY_CX + EYE_DX} cy={EYE_CY} isLeft={false} mode={rightEye} openness={rOp} px={gx('right')} py={gy('right')} accent={accent} />

          {/* 嘴 */}
          <Mouth mode={mouth} curve={params.mouth.curve} openness={params.mouth.openness}
                 isHappy={desc.eye === 'star'} t={t} />

          {/* 脸侧装饰道具 */}
          {desc.prop && <Prop kind={desc.prop} accent={accent} t={t} />}

          {/* 石化裂纹叠在身体上 */}
          {desc.desaturate && desc.prop === 'crack' && (
            <g stroke="#8A8690" strokeWidth={3} fill="none" opacity={0.6} strokeLinecap="round">
              <path d={`M ${BODY_CX - 40} ${BODY_CY - 120} l 18 60 l -14 50 l 22 70`} />
              <path d={`M ${BODY_CX + 60} ${BODY_CY - 90} l -16 50 l 20 46`} />
            </g>
          )}
        </g>

        {/* 状态文字 */}
        {desc.label && (
          <text x={VB_W / 2} y={VB_H - 26} textAnchor="middle"
                fontSize={20} fontWeight={700} fill={deepen(accent, 0.25)}
                style={{ fontFamily: 'system-ui', letterSpacing: 3 }}>{desc.label}</text>
        )}
      </svg>
    </div>
  );
}

/* ────────── 眼睛 ────────── */

type EyeMode = 'bean' | 'wide' | 'happy' | 'sleep' | 'angry' | 'dizzy' | 'star' | 'x' | 'dot';
const EYE_MAP: Record<EyeStyle, EyeMode> = {
  normal: 'bean', wide: 'wide', star: 'star', angry: 'angry',
  sleepyLid: 'sleep', shy: 'happy', closedSmile: 'happy',
  dead: 'x', dizzy: 'dizzy', blank: 'dot', wink: 'bean',
};

function starShape(x: number, y: number, s: number) {
  let d = '';
  for (let i = 0; i < 10; i++) {
    const a = -Math.PI / 2 + i * Math.PI / 5;
    const rr = i % 2 === 0 ? s : s * 0.45;
    d += `${i === 0 ? 'M' : 'L'}${(x + Math.cos(a) * rr).toFixed(1)} ${(y + Math.sin(a) * rr).toFixed(1)} `;
  }
  return d + 'Z';
}

function Eye({ cx, cy, isLeft, mode, openness, px, py, accent }: {
  cx: number; cy: number; isLeft: boolean; mode: EyeMode;
  openness: number; px: number; py: number; accent: string;
}) {
  const stroke = { stroke: EYE_DARK, strokeWidth: 9, fill: 'none' as const, strokeLinecap: 'round' as const };

  if (mode === 'happy') {
    return <path d={`M ${cx - 24} ${cy + 8} Q ${cx} ${cy - 18} ${cx + 24} ${cy + 8}`} {...stroke} />;
  }
  if (mode === 'sleep') {
    return <path d={`M ${cx - 24} ${cy} Q ${cx} ${cy + 14} ${cx + 24} ${cy}`} {...stroke} />;
  }
  if (mode === 'angry') {
    const dir = isLeft ? 1 : -1;
    return (
      <>
        <path d={`M ${cx - 22 * dir} ${cy - 18} L ${cx + 18 * dir} ${cy - 4}`} {...stroke} />
        <ellipse cx={cx} cy={cy + 8} rx={15} ry={18} fill={EYE_DARK} />
      </>
    );
  }
  if (mode === 'x') {
    return (
      <g {...stroke} strokeWidth={8}>
        <path d={`M ${cx - 16} ${cy - 16} L ${cx + 16} ${cy + 16}`} />
        <path d={`M ${cx + 16} ${cy - 16} L ${cx - 16} ${cy + 16}`} />
      </g>
    );
  }
  if (mode === 'dot') {
    return <circle cx={cx} cy={cy} r={9} fill={EYE_DARK} />;
  }
  if (mode === 'dizzy') {
    let d = `M ${cx} ${cy} `;
    for (let i = 1; i <= 26; i++) {
      const a = (i / 26) * 2.2 * Math.PI * 2, r = (i / 26) * 20;
      d += `L ${(cx + Math.cos(a) * r).toFixed(1)} ${(cy + Math.sin(a) * r).toFixed(1)} `;
    }
    return <path d={d} {...stroke} strokeWidth={6} />;
  }
  if (mode === 'star') {
    return (
      <g>
        <path d={starShape(cx, cy, 24)} fill={EYE_DARK} />
        <circle cx={cx - 7} cy={cy - 8} r={5} fill="#FFFFFF" />
      </g>
    );
  }

  // bean / wide：实心亮眼豆 + 高光
  const blink = openness < 0.18;
  if (blink) {
    return <path d={`M ${cx - 22} ${cy} Q ${cx} ${cy + 10} ${cx + 22} ${cy}`} {...stroke} />;
  }
  const wide = mode === 'wide';
  const rx = wide ? 26 : 22;
  const ry = (wide ? 32 : 28) * Math.max(0.4, openness);
  const ex = cx + px, ey = cy + py;
  return (
    <g>
      <ellipse cx={ex} cy={ey} rx={rx} ry={ry} fill={EYE_DARK} />
      <circle cx={ex - rx * 0.32} cy={ey - ry * 0.4} r={rx * 0.32} fill="#FFFFFF" />
      <circle cx={ex + rx * 0.18} cy={ey + ry * 0.28} r={rx * 0.13} fill="#FFFFFF" opacity={0.8} />
    </g>
  );
}

/* ────────── 嘴 ────────── */

type MouthMode = 'cat' | 'smile' | 'bigSmile' | 'open' | 'line' | 'frown' | 'wave' | 'none' | 'auto';
const MOUTH_MAP: Record<MouthKind, MouthMode> = {
  auto: 'auto', smile: 'smile', bigSmile: 'bigSmile', open: 'open', flat: 'line',
  frown: 'frown', pout: 'cat', cat: 'cat', wavy: 'wave', small: 'cat', hidden: 'none',
};

function Mouth({ mode, curve, openness, isHappy, t }: {
  mode: MouthMode; curve: number; openness: number; isHappy: boolean; t: number;
}) {
  const cx = BODY_CX, cy = MOUTH_CY;
  const stroke = { stroke: EYE_DARK, strokeWidth: 6, fill: 'none' as const, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const };
  // auto：沿用底层 FaceParams（curve/openness）分流，与 AmbientFace 对齐
  const resolved: Exclude<MouthMode, 'auto'> =
    mode === 'auto' ? chooseAutoMouth(curve, openness, isHappy) : mode;
  if (resolved === 'none') return null;
  if (resolved === 'cat') {
    return <path d={`M ${cx - 16} ${cy} Q ${cx - 8} ${cy + 9} ${cx} ${cy} Q ${cx + 8} ${cy + 9} ${cx + 16} ${cy}`} {...stroke} />;
  }
  if (resolved === 'smile') {
    return <path d={`M ${cx - 18} ${cy - 2} Q ${cx} ${cy + 14} ${cx + 18} ${cy - 2}`} {...stroke} />;
  }
  if (resolved === 'bigSmile') {
    return <path d={`M ${cx - 22} ${cy - 4} Q ${cx} ${cy + 22} ${cx + 22} ${cy - 4} Q ${cx} ${cy + 6} ${cx - 22} ${cy - 4} Z`}
                 fill={deepen('#FF7A93', 0)} stroke="none" />;
  }
  if (resolved === 'open') {
    const ry = 7 + Math.abs(Math.sin(t / 200)) * 7 + openness * 8;
    return <ellipse cx={cx} cy={cy + 2} rx={ry * 0.8} ry={ry} fill="#E06A82" />;
  }
  if (resolved === 'frown') {
    return <path d={`M ${cx - 16} ${cy + 8} Q ${cx} ${cy - 8} ${cx + 16} ${cy + 8}`} {...stroke} />;
  }
  if (resolved === 'wave') {
    return <path d={`M ${cx - 18} ${cy} Q ${cx - 9} ${cy - 7} ${cx} ${cy} T ${cx + 18} ${cy}`} {...stroke} />;
  }
  return <path d={`M ${cx - 12} ${cy} L ${cx + 12} ${cy}`} {...stroke} />;
}

/* ────────── 背景飘浮装饰 ────────── */

function heart(x: number, y: number, s: number) {
  return `M ${x} ${y + s * 0.3} C ${x - s} ${y - s * 0.6} ${x - s * 0.5} ${y - s} ${x} ${y - s * 0.35} ` +
         `C ${x + s * 0.5} ${y - s} ${x + s} ${y - s * 0.6} ${x} ${y + s * 0.3} Z`;
}

function BgSparkle({ accent, t, desaturate }: { accent: string; t: number; desaturate: boolean }) {
  if (desaturate) return null;
  const col = deepen(accent, 0.1);
  const items = [
    { x: 90, y: 140, s: 12, ph: 0 },
    { x: 510, y: 110, s: 9, ph: 1.7 },
    { x: 70, y: 430, s: 8, ph: 3.1 },
    { x: 530, y: 470, s: 11, ph: 4.4 },
  ];
  return (
    <g fill={col}>
      {items.map((it, i) => {
        const k = 0.4 + 0.6 * (0.5 + 0.5 * Math.sin(t / 500 + it.ph));
        const fy = it.y + Math.sin(t / 900 + it.ph) * 8;
        return <path key={i} d={starShape(it.x, fy, it.s)} opacity={k * 0.5} />;
      })}
    </g>
  );
}

/* ────────── 脸侧道具 ────────── */

function Prop({ kind, accent, t }: { kind: PropKind; accent: string; t: number }) {
  const deep = deepen(accent, 0.2);

  if (kind === 'magic') {
    const k = 0.5 + 0.5 * Math.sin(t / 240);
    return (
      <g>
        <path d={heart(BODY_CX - 150, EYE_CY - 150 + Math.sin(t / 600) * 6, 16)} fill="#FF7FA8" opacity={0.5 + k * 0.5} />
        <path d={heart(BODY_CX + 150, EYE_CY - 120 + Math.cos(t / 600) * 6, 12)} fill="#FF7FA8" opacity={0.5 + (1 - k) * 0.5} />
        <path d={starShape(BODY_CX + 120, EYE_CY - 175, 11)} fill={deep} opacity={0.7} />
      </g>
    );
  }
  if (kind === 'sweat') {
    const drip = (t / 18) % 46;
    const x = BODY_CX + 60, y = EYE_CY - 50 + drip;
    return <path d={`M ${x} ${y - 14} Q ${x + 9} ${y - 1} ${x} ${y + 6} Q ${x - 9} ${y - 1} ${x} ${y - 14} Z`} fill="#7FC8F0" />;
  }
  if (kind === 'question') {
    return <text x={BODY_CX + 130} y={EYE_CY - 120} fontSize={66} fontWeight={800} fill={deep}
                 textAnchor="middle" style={{ fontFamily: 'system-ui' }}>?</text>;
  }
  if (kind === 'anger') {
    const cx = BODY_CX + 110, cy = EYE_CY - 120, p = 0.7 + 0.3 * Math.abs(Math.sin(t / 200));
    return (
      <g stroke="#F0506E" strokeWidth={6} strokeLinecap="round" fill="none" opacity={p}>
        <path d={`M ${cx} ${cy} L ${cx} ${cy + 26}`} />
        <path d={`M ${cx - 13} ${cy + 11} L ${cx + 13} ${cy + 11}`} />
        <path d={`M ${cx - 11} ${cy - 2} L ${cx - 18} ${cy - 10}`} />
        <path d={`M ${cx + 11} ${cy - 2} L ${cx + 18} ${cy - 10}`} />
      </g>
    );
  }
  if (kind === 'moon') {
    return (
      <g fill={deep}>
        <path d="M 470 120 a 26 26 0 1 0 14 48 a 20 20 0 1 1 -14 -48 Z" />
        <path d={starShape(130, 150, 9)} opacity={0.8} />
        <path d={starShape(500, 230, 7)} opacity={0.7} />
      </g>
    );
  }
  if (kind === 'glass') {
    const cx = BODY_CX + 150, cy = EYE_CY + 30;
    return (
      <g fill="none" stroke={deep} strokeWidth={9} strokeLinecap="round">
        <circle cx={cx} cy={cy} r={42} />
        <path d={`M ${cx + 30} ${cy + 30} L ${cx + 66} ${cy + 66}`} strokeWidth={13} />
      </g>
    );
  }
  if (kind === 'cup') {
    const cx = BODY_CX, top = MOUTH_CY - 6, bot = MOUTH_CY + 60;
    return (
      <g>
        <path d={`M ${cx - 30} ${top} L ${cx + 30} ${top} L ${cx + 22} ${bot} L ${cx - 22} ${bot} Z`}
              fill="#FFF6F2" stroke={deep} strokeWidth={4} strokeLinejoin="round" />
        <path d={`M ${cx + 12} ${top} L ${cx + 24} ${top - 38}`} stroke="#FF8FB0" strokeWidth={7} strokeLinecap="round" />
      </g>
    );
  }
  if (kind === 'food') {
    const cx = BODY_CX + 96, cy = MOUTH_CY - 10;
    return <path d={`M ${cx} ${cy - 26} L ${cx + 24} ${cy + 20} L ${cx - 24} ${cy + 20} Z`}
                 fill="#FFFFFF" stroke="#E6E0D4" strokeWidth={3} strokeLinejoin="round" />;
  }
  if (kind === 'camera') {
    const flash = Math.max(0, Math.sin(t / 400)) ** 6;
    const cx = BODY_CX, cy = MOUTH_CY + 30;
    return (
      <g>
        {flash > 0.05 && <rect x={0} y={0} width={VB_W} height={VB_H} fill="#FFFFFF" opacity={flash * 0.4} />}
        <rect x={cx - 42} y={cy - 20} width={84} height={48} rx={8} fill="#5A5060" />
        <circle cx={cx} cy={cy + 4} r={15} fill="#2A2430" stroke="#FFC2D2" strokeWidth={3} />
      </g>
    );
  }
  return null;
}
