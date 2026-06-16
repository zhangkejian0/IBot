import { useEffect, useState } from 'react';
import { faceController } from '../runtime/controller';
import { eventBus } from '../runtime/eventBus';
import { cloneNeutral } from '../params/neutral';
import { faceDisplayViewBox } from './faceLayout';
import type { FaceState } from '../types';

/**
 * AmbientFace ── 暗背景 + 柔光晕染 + 可爱 Q 眼。
 * 用于手机桌面表达信息：环境光打底、珍珠眼传神、底部状态文字。
 */

type StateStyle = {
  aura: string;       // 情绪环境光
  blushOn: boolean;   // 是否显示粉腮红
  smileEye: boolean;  // 是否压成笑眼 ⌣
  label?: string;     // 状态文字
};

const STATE_STYLE: Record<FaceState, StateStyle> = {
  idle:      { aura: '#FF9978', blushOn: true,  smileEye: false },
  gazing:    { aura: '#7BC0FF', blushOn: true,  smileEye: false, label: '看着你' },
  listening: { aura: '#56C8FF', blushOn: true,  smileEye: false, label: '聆听中' },
  thinking:  { aura: '#B098FF', blushOn: false, smileEye: false, label: '思考中' },
  happy:     { aura: '#FFCC55', blushOn: true,  smileEye: true },
  confused:  { aura: '#74DCAA', blushOn: false, smileEye: false, label: '嗯？' },
  sleepy:    { aura: '#7090D0', blushOn: false, smileEye: false },
  sleeping:  { aura: '#3E4E92', blushOn: false, smileEye: false, label: '休息中' },
  waking:    { aura: '#7090D0', blushOn: true,  smileEye: false },
};

const BG = '#0A0A14';

// faceDisplayViewBox = "60 200 880 340" → 中心 (500, 370)
const VIEW_CX = 500;
const FACE_CY = 355;
// 眼睛更大、靠得稍近一点（卡哇伊比例）
const LEFT_EYE_CX = 395;
const RIGHT_EYE_CX = 605;
const EYE_BASE_R = 92;
const PUPIL_BASE_R = 44;          // 大瞳孔
const HIGHLIGHT_MAIN_R = 13;
const HIGHLIGHT_SUB_R = 6;
const BLUSH_DX = 75;              // 腮红距离眼中心
const BLUSH_DY = 65;
const BLUSH_RX = 38;
const BLUSH_RY = 14;

const MOUTH_CX = 500;
const MOUTH_CY = 484;
const MOUTH_BASE_W = 62;

export function AmbientFace() {
  const [params, setParams] = useState(() => cloneNeutral());
  const [state, setState] = useState<FaceState>(() => faceController.getState());

  useEffect(() => {
    faceController.setRenderCallback((p) => setParams(p));
    const off = eventBus.on('state:change', ({ state }) => setState(state));
    return () => {
      faceController.stop();
      off();
    };
  }, []);

  const style = STATE_STYLE[state];

  const lOp = Math.max(0.04, params.leftEye.openness);
  const rOp = Math.max(0.04, params.rightEye.openness);

  const gx = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilX : params.rightEye.pupilX) * 22;
  const gy = (s: 'left' | 'right') => (s === 'left' ? params.leftEye.pupilY : params.rightEye.pupilY) * 18;

  // 笑眼系数：state.smileEye=true 或 upperLidCurve 强烈负
  const lSmileK = style.smileEye ? 0.85 : Math.max(0, -params.leftEye.upperLidCurve);
  const rSmileK = style.smileEye ? 0.85 : Math.max(0, -params.rightEye.upperLidCurve);

  // 头部
  const headTransform =
    `translate(${VIEW_CX} ${FACE_CY + params.headBobY}) rotate(${params.headTilt}) translate(${-VIEW_CX} ${-FACE_CY})`;

  // 是否显示腮红（开关 + 强度）
  const blushOpacity = style.blushOn ? 0.55 : 0;

  return (
    <div style={{ position: 'relative', width: '100%', height: '100%', background: BG, overflow: 'hidden' }}>
      {/* 屏底情绪环境光 */}
      <div
        style={{
          position: 'absolute', inset: 0,
          background: `radial-gradient(ellipse 95% 75% at 50% 58%, ${style.aura}3B 0%, ${style.aura}14 38%, transparent 75%)`,
          transition: 'background 1.2s ease',
          pointerEvents: 'none',
        }}
      />
      {/* 顶部很淡的反向晕，加深空间感 */}
      <div
        style={{
          position: 'absolute', inset: 0,
          background: `radial-gradient(ellipse 70% 35% at 50% 8%, ${style.aura}1A 0%, transparent 70%)`,
          transition: 'background 1.2s ease',
          pointerEvents: 'none',
        }}
      />

      <svg
        viewBox={faceDisplayViewBox()}
        preserveAspectRatio="xMidYMid meet"
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
      >
        <defs>
          {/* 眼白：珍珠质感 */}
          <radialGradient id="amb-eye" cx="0.4" cy="0.36" r="0.62">
            <stop offset="0%"   stopColor="#FFFFFF" />
            <stop offset="55%"  stopColor="#F4F0FA" />
            <stop offset="100%" stopColor="#D6CFE6" />
          </radialGradient>
          {/* 瞳孔：深紫蓝渐变到黑 */}
          <radialGradient id="amb-pupil" cx="0.5" cy="0.4" r="0.6">
            <stop offset="0%"   stopColor="#3C3870" />
            <stop offset="55%"  stopColor="#15102E" />
            <stop offset="100%" stopColor="#02000A" />
          </radialGradient>
          {/* 眼后情绪柔光 */}
          <radialGradient id="amb-aura" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%"   stopColor={style.aura} stopOpacity={0.85} />
            <stop offset="100%" stopColor={style.aura} stopOpacity={0} />
          </radialGradient>
          {/* 腮红渐变 */}
          <radialGradient id="amb-blush" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0%"   stopColor="#FFA8C8" stopOpacity={1} />
            <stop offset="100%" stopColor="#FFA8C8" stopOpacity={0} />
          </radialGradient>
          <filter id="amb-soft" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="24" />
          </filter>
        </defs>

        <g transform={headTransform}>
          {/* 眼后大块情绪柔光 */}
          <ellipse cx={LEFT_EYE_CX} cy={FACE_CY}
                   rx={EYE_BASE_R * 1.9} ry={EYE_BASE_R * 1.7}
                   fill="url(#amb-aura)" filter="url(#amb-soft)" />
          <ellipse cx={RIGHT_EYE_CX} cy={FACE_CY}
                   rx={EYE_BASE_R * 1.9} ry={EYE_BASE_R * 1.7}
                   fill="url(#amb-aura)" filter="url(#amb-soft)" />

          {/* 腮红（在眼睛下两侧） */}
          {blushOpacity > 0 && (
            <>
              <ellipse cx={LEFT_EYE_CX - BLUSH_DX} cy={FACE_CY + BLUSH_DY}
                       rx={BLUSH_RX} ry={BLUSH_RY}
                       fill="url(#amb-blush)" opacity={blushOpacity} />
              <ellipse cx={RIGHT_EYE_CX + BLUSH_DX} cy={FACE_CY + BLUSH_DY}
                       rx={BLUSH_RX} ry={BLUSH_RY}
                       fill="url(#amb-blush)" opacity={blushOpacity} />
            </>
          )}

          {/* 左眼 */}
          <Eye
            cx={LEFT_EYE_CX} cy={FACE_CY} isLeft
            openness={lOp} smileK={lSmileK}
            pupilDx={gx('left')} pupilDy={gy('left')}
            pupilScale={params.leftEye.pupilScale}
            highlightOn={params.leftEye.highlightOn}
          />
          {/* 右眼 */}
          <Eye
            cx={RIGHT_EYE_CX} cy={FACE_CY} isLeft={false}
            openness={rOp} smileK={rSmileK}
            pupilDx={gx('right')} pupilDy={gy('right')}
            pupilScale={params.rightEye.pupilScale}
            highlightOn={params.rightEye.highlightOn}
          />

          {/* 嘴 */}
          <Mouth
            curve={params.mouth.curve}
            openness={params.mouth.openness}
            width={params.mouth.width}
            isHappy={style.smileEye}
          />
        </g>
      </svg>

      {/* 状态文字 */}
      <div
        style={{
          position: 'absolute',
          left: 0, right: 0, bottom: 'max(14px, env(safe-area-inset-bottom))',
          textAlign: 'center',
          color: 'rgba(255,255,255,0.6)',
          fontSize: 13, letterSpacing: 6, fontWeight: 300,
          textShadow: `0 0 14px ${style.aura}66`,
          opacity: style.label ? 1 : 0,
          transition: 'opacity 0.8s ease',
          pointerEvents: 'none',
        }}
      >
        {style.label ?? ''}
      </div>
    </div>
  );
}

function Eye({
  cx, cy, isLeft, openness, smileK, pupilDx, pupilDy, pupilScale, highlightOn,
}: {
  cx: number; cy: number; isLeft: boolean;
  openness: number; smileK: number;
  pupilDx: number; pupilDy: number;
  pupilScale: number; highlightOn: number;
}) {
  const rx = EYE_BASE_R;
  const ry = EYE_BASE_R * openness;

  const pupilRx = PUPIL_BASE_R * pupilScale;
  // 瞳孔垂直压缩跟随 openness（眼睛闭时瞳孔也跟着压）
  const pupilRy = PUPIL_BASE_R * pupilScale * Math.max(0.25, openness);

  // 瞳孔默认略往上（look-up 卡哇伊感）
  const pupilCy = cy + pupilDy - 4;
  const pupilCx = cx + pupilDx;

  // 笑眼遮罩：背景色弧从顶部盖下来形成 ⌣
  const showSmileMask = smileK > 0.05 && openness > 0.15;

  // 高光位置：偏内上（鼻侧上方）= kawaii 标准位
  const hx = pupilCx + (isLeft ? 1 : -1) * pupilRx * 0.32 - 14;
  const hy = pupilCy - pupilRy * 0.45;
  // 副高光：对角位置，小一圈
  const hx2 = pupilCx + (isLeft ? -1 : 1) * pupilRx * 0.4;
  const hy2 = pupilCy + pupilRy * 0.42;

  return (
    <g>
      {/* 眼白珍珠 */}
      <ellipse cx={cx} cy={cy} rx={rx} ry={ry} fill="url(#amb-eye)" />

      {/* 瞳孔 */}
      <ellipse cx={pupilCx} cy={pupilCy} rx={pupilRx} ry={pupilRy} fill="url(#amb-pupil)" />

      {/* 主高光 */}
      {openness > 0.12 && (
        <circle cx={hx} cy={hy}
                r={HIGHLIGHT_MAIN_R * Math.max(0.5, openness)}
                fill="#FFFFFF"
                opacity={0.96 * highlightOn} />
      )}
      {/* 副高光（卡哇伊双闪） */}
      {openness > 0.2 && (
        <circle cx={hx2} cy={hy2}
                r={HIGHLIGHT_SUB_R * Math.max(0.5, openness)}
                fill="#FFFFFF"
                opacity={0.78 * highlightOn} />
      )}

      {/* 笑眼盖（与背景同色弧）从眼睛上方往下压，形成 ⌣ */}
      {showSmileMask && (
        <path
          d={[
            `M ${cx - rx - 2} ${cy - ry - 2}`,
            `L ${cx + rx + 2} ${cy - ry - 2}`,
            `Q ${cx} ${cy + ry * (0.5 - 1.5 * smileK)} ${cx - rx - 2} ${cy - ry - 2}`,
            'Z',
          ].join(' ')}
          fill={BG}
        />
      )}
    </g>
  );
}

function Mouth({
  curve, openness, width, isHappy,
}: {
  curve: number; openness: number; width: number; isHappy: boolean;
}) {
  const cx = MOUTH_CX;
  const cy = MOUTH_CY;
  const mw = MOUTH_BASE_W * width;

  // happy：弹弹的大笑弧（粉色描边显得软）
  if (isHappy || curve > 0.35) {
    const amp = 14 + openness * 16 + Math.max(0, curve) * 12;
    const d = [
      `M ${cx - mw * 0.92} ${cy - 2}`,
      `Q ${cx} ${cy + amp} ${cx + mw * 0.92} ${cy - 2}`,
    ].join(' ');
    return (
      <g>
        {/* 软光晕 */}
        <path d={d} fill="none" stroke="#FFFFFF" strokeWidth={11}
              strokeLinecap="round" opacity={0.18} />
        <path d={d} fill="none" stroke="#FFFFFF" strokeWidth={5}
              strokeLinecap="round" opacity={0.95} />
      </g>
    );
  }

  // 不开心：小倒弧
  if (curve < -0.2) {
    const amp = 10 + (-curve) * 10;
    const d = [
      `M ${cx - mw * 0.7} ${cy + amp * 0.4}`,
      `Q ${cx} ${cy - amp} ${cx + mw * 0.7} ${cy + amp * 0.4}`,
    ].join(' ');
    return (
      <path d={d} fill="none" stroke="rgba(255,255,255,0.75)" strokeWidth={4} strokeLinecap="round" />
    );
  }

  // 张嘴说话（中性 openness）：小粉嘟嘟椭圆
  if (openness > 0.15) {
    const r = 5 + openness * 16;
    return (
      <ellipse cx={cx} cy={cy} rx={r * 0.78} ry={r * 0.62}
               fill="rgba(255,180,200,0.7)" />
    );
  }

  // 闭嘴：小小 ω 一样的微笑（两个浅弧）
  const half = mw * 0.45;
  const d = [
    `M ${cx - half} ${cy} Q ${cx - half / 2} ${cy + 5} ${cx} ${cy}`,
    `Q ${cx + half / 2} ${cy + 5} ${cx + half} ${cy}`,
  ].join(' ');
  return (
    <path d={d} fill="none" stroke="rgba(255,255,255,0.55)" strokeWidth={3.5} strokeLinecap="round" />
  );
}
