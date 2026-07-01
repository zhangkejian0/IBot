import { useEffect, useState } from 'react';
import { faceController } from '../runtime/controller';
import { eventBus } from '../runtime/eventBus';
import { cloneNeutral } from '../params/neutral';
import { faceDisplayViewBox } from './faceLayout';
import { buildHeadTransform } from './headTransform';
import type { FaceState } from '../types';

/**
 * NeonFace ─ 霓虹/LED 机器人脸。
 * 极简几何（圆角矩形眼 + 眼皮条 + 描边嘴）+ 强外辉光。
 * 颜色由 FSM 状态驱动，形状由现有 FaceParams 驱动（共用主 Face 的 controller）。
 */

const STATE_COLOR: Record<FaceState, string> = {
  idle:      '#FFD000', // 暖金
  gazing:    '#00D9FF', // 青
  listening: '#00D9FF',
  thinking:  '#C040FF', // 紫
  happy:     '#FFD000', // 金黄
  confused:  '#20FF80', // 绿
  angry:     '#FF3030', // 红
  sleepy:    '#4080FF', // 蓝
  sleeping:  '#1A3680',
  waking:    '#4080FF',
};

// faceDisplayViewBox = "60 200 880 340"，视口中心 (500, 370)
const FRAME_X = 100;
const FRAME_Y = 230;
const FRAME_W = 780;
const FRAME_H = 300;
const FRAME_RX = 36;
const FRAME_STROKE = 6;

const EYE_CY = 360;
const LEFT_EYE_CX = 360;
const RIGHT_EYE_CX = 620;
const EYE_W = 150;
const EYE_H_MAX = 150;
const EYE_RX = 38;

const LID_W = 110;
const LID_H = 22;
const LID_GAP = 14;

const MOUTH_CX = 490;
const MOUTH_CY = 480;
const MOUTH_W = 80;
const MOUTH_STROKE = 8;

export function NeonFace() {
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

  const color = STATE_COLOR[state];

  const lOp = Math.max(0.06, params.leftEye.openness);
  const rOp = Math.max(0.06, params.rightEye.openness);
  const leftH = EYE_H_MAX * lOp;
  const rightH = EYE_H_MAX * rOp;

  // 眼皮倾角（angry/严肃时眉头压下来）
  const lLidTilt = -params.leftEye.upperLidCurve * 22;
  const rLidTilt = params.rightEye.upperLidCurve * 22;

  const lLidY = EYE_CY - leftH / 2 - LID_GAP - LID_H / 2;
  const rLidY = EYE_CY - rightH / 2 - LID_GAP - LID_H / 2;

  const mw = MOUTH_W * params.mouth.width;
  const mouthD = buildMouth(params.mouth.curve, mw, params.mouth.openness);

  // 头部微动（headBobY / headTilt 来自 oscillators）
  const cx = 500;
  const cy = 370;
  const headTransform = buildHeadTransform(cx, cy, params, { neckYOffset: 80 });

  return (
    <svg
      viewBox={faceDisplayViewBox()}
      preserveAspectRatio="xMidYMid meet"
      style={{ width: '100%', height: '100%', display: 'block' }}
    >
      <defs>
        {/* 强辉光（双层 Gaussian） */}
        <filter id="neon-glow" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="10" result="b1" />
          <feGaussianBlur in="SourceGraphic" stdDeviation="22" result="b2" />
          <feMerge>
            <feMergeNode in="b2" />
            <feMergeNode in="b1" />
            <feMergeNode in="b1" />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
        {/* 柔光（用于外框） */}
        <filter id="neon-glow-soft" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="18" />
          <feMerge>
            <feMergeNode />
            <feMergeNode in="SourceGraphic" />
          </feMerge>
        </filter>
      </defs>

      <g transform={headTransform}>
        {/* 情绪色外框 */}
        <rect
          x={FRAME_X} y={FRAME_Y}
          width={FRAME_W} height={FRAME_H} rx={FRAME_RX}
          fill="none" stroke={color} strokeWidth={FRAME_STROKE}
          filter="url(#neon-glow-soft)"
          opacity={0.95}
        />

        {/* 内壁压一点暗，凸显"舱内" */}
        <rect
          x={FRAME_X + 6} y={FRAME_Y + 6}
          width={FRAME_W - 12} height={FRAME_H - 12} rx={FRAME_RX - 4}
          fill="#000"
          opacity={0.55}
        />

        {/* 眉 + 眼：用 white + color filter 让辉光是情绪色 */}
        <g style={{ color }}>
          {/* 左眼皮 */}
          <rect
            x={LEFT_EYE_CX - LID_W / 2} y={lLidY - LID_H / 2}
            width={LID_W} height={LID_H} rx={LID_H / 2}
            fill="white"
            transform={`rotate(${lLidTilt}, ${LEFT_EYE_CX}, ${lLidY})`}
            filter="url(#neon-glow)"
          />
          {/* 左眼 */}
          <rect
            x={LEFT_EYE_CX - EYE_W / 2} y={EYE_CY - leftH / 2}
            width={EYE_W} height={leftH} rx={EYE_RX}
            fill="white"
            filter="url(#neon-glow)"
          />

          {/* 右眼皮 */}
          <rect
            x={RIGHT_EYE_CX - LID_W / 2} y={rLidY - LID_H / 2}
            width={LID_W} height={LID_H} rx={LID_H / 2}
            fill="white"
            transform={`rotate(${rLidTilt}, ${RIGHT_EYE_CX}, ${rLidY})`}
            filter="url(#neon-glow)"
          />
          {/* 右眼 */}
          <rect
            x={RIGHT_EYE_CX - EYE_W / 2} y={EYE_CY - rightH / 2}
            width={EYE_W} height={rightH} rx={EYE_RX}
            fill="white"
            filter="url(#neon-glow)"
          />

          {/* 嘴 */}
          <path
            d={mouthD}
            fill="none"
            stroke="white"
            strokeWidth={MOUTH_STROKE}
            strokeLinecap="round"
            strokeLinejoin="round"
            filter="url(#neon-glow)"
          />
        </g>
      </g>
    </svg>
  );
}

/** 大笑半圆 / 短直线 / 倒弧 */
function buildMouth(curve: number, mw: number, openness: number): string {
  const cy = MOUTH_CY;
  const cx = MOUTH_CX;
  if (curve > 0.25) {
    const amp = 32 + openness * 18 + curve * 14;
    return [
      `M ${cx - mw} ${cy}`,
      `L ${cx + mw} ${cy}`,
      `Q ${cx} ${cy + amp} ${cx - mw} ${cy}`,
      `Z`,
    ].join(' ');
  }
  if (curve < -0.25) {
    const amp = 22 + (-curve) * 14;
    return [
      `M ${cx - mw} ${cy}`,
      `Q ${cx} ${cy - amp} ${cx + mw} ${cy}`,
    ].join(' ');
  }
  const half = Math.max(28, mw * 0.55);
  return `M ${cx - half} ${cy} L ${cx + half} ${cy}`;
}
