import { useEffect, useRef, useState } from 'react';
import {
  getWakeOverlayConfig, getWakeOverlayRuntime,
  subscribeWakeOverlayConfig, subscribeWakeOverlayRuntime,
  type WakeOverlayConfig, type WakeOverlayRuntime,
} from './wakeOverlayConfig';
import { getAmbientSkin, subscribeAmbientSkin, type AmbientSkin } from './ambientSkin';
import { wakeColorsFromSkin, type WakeOverlayColors } from './wakeOverlayColors';

const WAVE_W = 1000;
const WAVE_H = 130;

/**
 * 底部唤醒光效叠加层（纯 overlay，不占布局空间，不挤压表情区域）。
 * 文案叠在波浪光带上，可随 runtime / config 实时更新。
 */
export function WakeOverlay() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [config, setConfig] = useState<WakeOverlayConfig>(() => getWakeOverlayConfig());
  const [runtime, setRuntime] = useState<WakeOverlayRuntime>(() => getWakeOverlayRuntime());
  const [skin, setSkin] = useState<AmbientSkin>(() => getAmbientSkin());
  const [phase, setPhase] = useState(0);
  const rafRef = useRef(0);

  useEffect(() => {
    const offCfg = subscribeWakeOverlayConfig(setConfig);
    const offRt = subscribeWakeOverlayRuntime(setRuntime);
    const offSkin = subscribeAmbientSkin(setSkin);
    return () => { offCfg(); offRt(); offSkin(); };
  }, []);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(() => { /* 触发重绘以适配面板开合 */ });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const active = config.enabled && runtime.visible;
  const colors: WakeOverlayColors = wakeColorsFromSkin(skin);
  const displayText = runtime.text !== null ? runtime.text : config.defaultText;

  useEffect(() => {
    if (!active) {
      setPhase(0);
      return;
    }
    const start = performance.now();
    const loop = (t: number) => {
      setPhase((t - start) / 1000);
      rafRef.current = requestAnimationFrame(loop);
    };
    rafRef.current = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(rafRef.current);
  }, [active]);

  const breathe = 0.5 + 0.5 * Math.sin(phase * Math.PI * 2 * config.breatheSpeed);
  const levelBoost = runtime.level * 0.28;
  const intensity = config.glowIntensity * (0.78 + breathe * 0.22 + levelBoost);
  const flow = phase * 2.4;
  const wavePaths = buildWaveLayers(flow, intensity, runtime.level);
  const bandH = `${(config.bottomBand * 100).toFixed(1)}%`;
  const waveH = 'min(64px, 18vh)';

  return (
    <div
      ref={containerRef}
      aria-hidden
      style={{
        position: 'absolute',
        inset: 0,
        width: '100%',
        height: '100%',
        pointerEvents: 'none',
        overflow: 'hidden',
        opacity: active ? 1 : 0,
        transition: 'opacity 0.4s ease',
      }}
    >
      <div style={{
        position: 'absolute',
        left: 0,
        right: 0,
        bottom: 0,
        height: `min(${bandH}, 40vh, 200px)`,
        minHeight: 'min(88px, 34vh)',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'flex-end',
        alignItems: 'center',
        paddingBottom: `calc(max(6px, env(safe-area-inset-bottom)) + ${(config.bottomOffset * 100).toFixed(1)}%)`,
        overflow: 'hidden',
        transform: `translateY(${breathe * -1}px)`,
      }}>
        <div style={{
          position: 'absolute',
          left: '50%',
          bottom: 0,
          width: '96%',
          height: '85%',
          transform: 'translateX(-50%)',
          background: `radial-gradient(ellipse 78% 90% at 50% 100%, ${hexAlpha(colors.glowColor, 0.28 * intensity)} 0%, transparent 70%)`,
          pointerEvents: 'none',
        }} />

        {/* 波浪 + 文案叠层 */}
        <div style={{
          position: 'relative',
          width: 'min(94vw, 820px)',
          height: waveH,
          flexShrink: 1,
        }}>
          <svg
            viewBox={`0 0 ${WAVE_W} ${WAVE_H}`}
            preserveAspectRatio="none"
            style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: 0,
              width: '100%',
              height: '100%',
              opacity: intensity,
              overflow: 'visible',
            }}
          >
            <defs>
              <linearGradient id="wake-wave-grad" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor={colors.accentColor} stopOpacity={0} />
              <stop offset="10%" stopColor={colors.glowColor} stopOpacity={0.45} />
              <stop offset="32%" stopColor={colors.glowColor} stopOpacity={0.92} />
              <stop offset="50%" stopColor="#FFFFFF" stopOpacity={1} />
              <stop offset="68%" stopColor={colors.glowColor} stopOpacity={0.92} />
              <stop offset="90%" stopColor={colors.glowColor} stopOpacity={0.45} />
              <stop offset="100%" stopColor={colors.accentColor} stopOpacity={0} />
              </linearGradient>
              <filter id="wake-wave-glow" x="-12%" y="-80%" width="124%" height="260%">
                <feGaussianBlur stdDeviation="4" result="blur" />
                <feMerge>
                  <feMergeNode in="blur" />
                  <feMergeNode in="SourceGraphic" />
                </feMerge>
              </filter>
            </defs>

            {wavePaths.map((layer, i) => (
              <path
                key={i}
                d={layer.d}
                fill="none"
                stroke={layer.useGrad ? 'url(#wake-wave-grad)' : colors.glowColor}
                strokeOpacity={layer.opacity * intensity}
                strokeWidth={layer.width}
                strokeLinecap="round"
                filter={layer.glow ? 'url(#wake-wave-glow)' : undefined}
              />
            ))}

            {sparkles(flow, intensity).map((s, i) => (
              <circle
                key={`s-${i}`}
                cx={s.x}
                cy={s.y}
                r={s.r}
                fill="#FFFFFF"
                opacity={s.opacity * intensity}
              />
            ))}
          </svg>

          {displayText ? (
            <div style={{
              position: 'absolute',
              left: 0,
              right: 0,
              bottom: '52%',
              transform: 'translateY(8%)',
              zIndex: 2,
              textAlign: 'center',
              fontSize: `${config.textSize}px`,
              letterSpacing: config.letterSpacing,
              fontWeight: 400,
              lineHeight: 1,
              color: colors.textColor,
              opacity: Math.min(1, 0.85 + intensity * 0.15),
              textShadow: `0 0 10px ${hexAlpha(colors.glowColor, 0.65)}, 0 0 20px ${hexAlpha(colors.glowColor, 0.3)}`,
              whiteSpace: 'nowrap',
              pointerEvents: 'none',
            }}>
              {displayText}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}

type WaveLayer = {
  d: string;
  width: number;
  opacity: number;
  useGrad: boolean;
  glow: boolean;
};

function buildWaveLayers(flow: number, intensity: number, level: number): WaveLayer[] {
  const baseY = WAVE_H * 0.78;
  const amp = 26 + level * 10;
  return [
    { d: wavePath(baseY, amp * 1.15, flow, 2.4, 0.28), width: 1.6, opacity: 0.32, useGrad: false, glow: false },
    { d: wavePath(baseY - 4, amp * 0.9, flow + 1.2, 2.8, 0.32), width: 1.3, opacity: 0.4, useGrad: false, glow: false },
    { d: wavePath(baseY, amp, flow + 0.5, 2.2, 0.38), width: 2.6 + intensity, opacity: 0.82, useGrad: true, glow: true },
    { d: wavePath(baseY + 3, amp * 0.75, flow + 2.0, 3.0, 0.3), width: 1.5, opacity: 0.38, useGrad: false, glow: false },
    { d: wavePath(baseY - 2, amp * 0.55, flow + 2.8, 1.9, 0.42), width: 1.1, opacity: 0.28, useGrad: false, glow: false },
  ];
}

function wavePath(
  yBase: number,
  amplitude: number,
  phase: number,
  freq: number,
  taper: number,
): string {
  const steps = 100;
  let d = '';
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const x = t * WAVE_W;
    const env = Math.pow(Math.sin(t * Math.PI), taper);
    const y = yBase
      + Math.sin(t * Math.PI * freq + phase) * amplitude * env
      + Math.sin(t * Math.PI * freq * 1.65 + phase * 1.25) * amplitude * 0.35 * env
      + Math.sin(t * Math.PI * freq * 0.55 - phase * 0.7) * amplitude * 0.18 * env;
    d += `${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${y.toFixed(2)}`;
  }
  return d;
}

function sparkles(flow: number, intensity: number) {
  const seeds = [
    { x: 180, y: 42, phase: 0.2 },
    { x: 360, y: 34, phase: 1.1 },
    { x: 500, y: 38, phase: 0.5 },
    { x: 640, y: 32, phase: 0.9 },
    { x: 820, y: 40, phase: 1.6 },
  ];
  return seeds.map((s) => ({
    x: s.x,
    y: s.y + Math.sin(flow + s.phase) * 5,
    r: 1.2 + Math.sin(flow * 1.4 + s.phase) * 0.5,
    opacity: 0.22 + Math.sin(flow * 2 + s.phase) * 0.14 * intensity,
  }));
}

function hexAlpha(hex: string, alpha: number): string {
  const a = Math.round(Math.max(0, Math.min(1, alpha)) * 255).toString(16).padStart(2, '0');
  if (hex.startsWith('#') && hex.length === 7) return `${hex}${a}`;
  return hex;
}
