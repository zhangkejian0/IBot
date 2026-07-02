import { useEffect, useRef, useState } from 'react';
import { faceController } from '../runtime/controller';
import {
  AMBIENT_PRESETS, getAmbientExpression, setAmbientExpression,
  type AmbientExpressionId,
} from '../render/ambientExpression';
import { AMBIENT_SKINS, getAmbientSkinId, setAmbientSkin } from '../render/ambientSkin';
import { FACE_STYLES, getFaceStyle, setFaceStyle, type FaceStyleId } from '../render/faceStyle';
import {
  DEFAULT_GAZE_MOTION, getGazeMotionConfig, resetGazeMotionConfig, setGazeMotionConfig,
  subscribeGazeMotionConfig, type GazeMotionConfig,
} from '../runtime/gazeMotionConfig';
import {
  DEFAULT_WAKE_OVERLAY_CONFIG, getWakeOverlayConfig, resetWakeOverlayConfig,
  setWakeOverlayConfig, setWakeOverlayText, subscribeWakeOverlayConfig,
  type WakeOverlayConfig,
} from '../render/wakeOverlayConfig';

export function DebugPanel({ onClose }: { onClose?: () => void }) {
  const [gazePointerEnabled, setGazePointerEnabled] = useState(true);
  const [listening, setListening] = useState(0);
  const [faceStyle, setFaceStyleState] = useState<FaceStyleId>(() => getFaceStyle());
  const [ambExpr, setAmbExpr] = useState<AmbientExpressionId>(() => getAmbientExpression());
  const [ambSkin, setAmbSkin] = useState<string>(() => getAmbientSkinId());
  const [gazeMotion, setGazeMotionState] = useState<GazeMotionConfig>(() => getGazeMotionConfig());
  const [wakeCfg, setWakeCfgState] = useState<WakeOverlayConfig>(() => getWakeOverlayConfig());
  const [wakeText, setWakeText] = useState(() => getWakeOverlayConfig().defaultText);
  const listeningRef = useRef(0);

  useEffect(() => {
    return subscribeGazeMotionConfig(setGazeMotionState);
  }, []);

  useEffect(() => {
    return subscribeWakeOverlayConfig((cfg) => {
      setWakeCfgState(cfg);
      setWakeText(cfg.defaultText);
    });
  }, []);

  const onFaceStyle = (id: FaceStyleId) => {
    setFaceStyle(id);
    setFaceStyleState(id);
  };

  const onAmbExpr = (id: AmbientExpressionId) => {
    setAmbientExpression(id);
    setAmbExpr(id);
  };

  const onAmbSkin = (id: string) => {
    setAmbientSkin(id);
    setAmbSkin(id);
  };

  useEffect(() => {
    faceController.setGazePointerEnabled(gazePointerEnabled);
    if (!gazePointerEnabled) faceController.setGazeTarget(0, 0);
  }, [gazePointerEnabled]);

  useEffect(() => {
    listeningRef.current = listening;
    if (listening === 0) {
      faceController.setListeningLoudness(0);
      return;
    }
    let raf = 0;
    const start = performance.now();
    const loop = (t: number) => {
      const tt = (t - start) / 1000;
      const v = (Math.sin(tt * 6) * 0.5 + 0.5) * listeningRef.current;
      faceController.setListeningLoudness(v);
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => {
      cancelAnimationFrame(raf);
      faceController.setListeningLoudness(0);
    };
  }, [listening]);

  const reset = () => {
    setGazePointerEnabled(true);
    setListening(0);
    resetGazeMotionConfig();
    setGazeMotionState({ ...DEFAULT_GAZE_MOTION });
    resetWakeOverlayConfig();
    setWakeCfgState({ ...DEFAULT_WAKE_OVERLAY_CONFIG });
    setWakeText(DEFAULT_WAKE_OVERLAY_CONFIG.defaultText);
    faceController.hideWakeOverlay();
    setAmbientExpression('idle');
    setAmbExpr('idle');
  };

  return (
    <div style={s.root}>
      <Section title="风格">
        <div style={s.grid}>
          {FACE_STYLES.map((st) => (
            <button
              key={st.id}
              style={faceStyle === st.id ? s.btnActive : s.btn}
              onClick={() => onFaceStyle(st.id)}
            >
              {st.label}
            </button>
          ))}
        </div>
      </Section>

      <Section title="表情">
        <div style={s.grid}>
          {AMBIENT_PRESETS.map((p) => (
            <button
              key={p.id}
              style={ambExpr === p.id ? s.btnActive : s.btn}
              onClick={() => onAmbExpr(p.id)}
            >
              {p.label}
            </button>
          ))}
        </div>
      </Section>

      {faceStyle === 'ambient' && (
        <Section title="皮肤">
          <div style={s.grid}>
            {AMBIENT_SKINS.map((sk) => (
              <button
                key={sk.id}
                style={ambSkin === sk.id ? s.btnActive : s.btn}
                onClick={() => onAmbSkin(sk.id)}
              >
                {sk.label}
              </button>
            ))}
          </div>
        </Section>
      )}

      <Section title="唤醒光效">
        <label style={s.checkbox}>
          <input
            type="checkbox"
            checked={wakeCfg.enabled}
            onChange={(e) => setWakeOverlayConfig({ enabled: e.target.checked })}
          />
          启用底部唤醒光效
        </label>
        <input
          style={s.textInput}
          value={wakeText}
          placeholder="播报文案（模拟用户说话，实时更新）"
          onChange={(e) => {
            const v = e.target.value;
            setWakeText(v);
            setWakeOverlayConfig({ defaultText: v });
            setWakeOverlayText(v);
          }}
        />
        <SliderRow
          label="底带高度"
          value={wakeCfg.bottomBand}
          min={0.14} max={0.28} step={0.01}
          onChange={(v) => setWakeOverlayConfig({ bottomBand: v })}
          format={(v) => v.toFixed(2)}
        />
        <SliderRow
          label="底边微调"
          value={wakeCfg.bottomOffset}
          min={0} max={0.06} step={0.005}
          onChange={(v) => setWakeOverlayConfig({ bottomOffset: v })}
          format={(v) => v.toFixed(3)}
        />
        <SliderRow
          label="光晕强度"
          value={wakeCfg.glowIntensity}
          min={0.3} max={1} step={0.01}
          onChange={(v) => setWakeOverlayConfig({ glowIntensity: v })}
          format={(v) => v.toFixed(2)}
        />
        <SliderRow
          label="呼吸速度"
          value={wakeCfg.breatheSpeed}
          min={0.4} max={2.5} step={0.05}
          onChange={(v) => setWakeOverlayConfig({ breatheSpeed: v })}
          format={(v) => v.toFixed(2)}
        />
        <div style={s.row2}>
          <button
            style={s.btn}
            onClick={() => {
              faceController.showWakeOverlay();
              faceController.setWakeOverlayText(wakeText);
            }}
          >
            预览光效
          </button>
          <button style={s.btn} onClick={() => faceController.hideWakeOverlay()}>
            隐藏
          </button>
          <button
            style={s.btn}
            onClick={() => {
              faceController.setState('waking');
              setTimeout(() => faceController.setState('listening'), 1200);
            }}
          >
            模拟唤醒
          </button>
        </div>
      </Section>

      <Section title="感知信号模拟">
        <label style={s.checkbox}>
          <input
            type="checkbox"
            checked={gazePointerEnabled}
            onChange={(e) => setGazePointerEnabled(e.target.checked)}
          />
          鼠标/触摸注视跟随
        </label>
        <SliderRow
          label="倾听音量"
          value={listening}
          min={0} max={1} step={0.01}
          onChange={setListening}
          format={(v) => v.toFixed(2)}
        />
      </Section>

      <Section title="注视整体移动">
        <SliderRow
          label="左右"
          value={gazeMotion.panHorizontal}
          min={0} max={1} step={0.01}
          onChange={(v) => setGazeMotionConfig({ panHorizontal: v })}
          format={(v) => v.toFixed(2)}
        />
        <SliderRow
          label="上下"
          value={gazeMotion.panVertical}
          min={0} max={1} step={0.01}
          onChange={(v) => setGazeMotionConfig({ panVertical: v })}
          format={(v) => v.toFixed(2)}
        />
        <div style={s.hint}>
          1 = 鼠标到边缘时 SVG 脸组贴屏幕边；默认左右 {DEFAULT_GAZE_MOTION.panHorizontal} / 上下 {DEFAULT_GAZE_MOTION.panVertical}
        </div>
      </Section>

      <button style={s.resetBtn} onClick={reset}>重置</button>
      {onClose && (
        <button style={s.closeBtn} onClick={onClose}>关闭调试面板</button>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={s.section}>
      <div style={s.sectionTitle}>{title}</div>
      {children}
    </div>
  );
}

function SliderRow({
  label, value, min, max, step, onChange, format,
}: {
  label: string;
  value: number;
  min: number; max: number; step: number;
  onChange: (v: number) => void;
  format?: (v: number) => string;
}) {
  return (
    <div style={s.row}>
      <div style={s.rowLabel}>{label}</div>
      <input
        type="range"
        min={min} max={max} step={step}
        value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        style={s.range}
      />
      <div style={s.rowValue}>{format ? format(value) : String(Math.round(value))}</div>
    </div>
  );
}

const s: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', gap: 14 },
  section: { display: 'flex', flexDirection: 'column', gap: 8 },
  sectionTitle: { fontSize: 12, color: '#8a8f9f', letterSpacing: 0.5 },
  grid: { display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6 },
  btn: {
    padding: '8px 6px', borderRadius: 6, border: '1px solid #2a2e44',
    background: '#1a1d29', color: '#cfd2dc', cursor: 'pointer', fontSize: 12,
  },
  btnActive: {
    padding: '8px 6px', borderRadius: 6, border: '1px solid #7BD4FF',
    background: '#2a3a5a', color: '#fff', cursor: 'pointer', fontSize: 12,
  },
  row: { display: 'grid', gridTemplateColumns: '70px 1fr 36px', alignItems: 'center', gap: 8 },
  rowLabel: { fontSize: 12, color: '#cfd2dc' },
  rowValue: { fontSize: 11, color: '#8a8f9f', textAlign: 'right' },
  range: { width: '100%' },
  checkbox: { display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: '#cfd2dc' },
  hint: { fontSize: 11, color: '#6a7080', lineHeight: 1.45 },
  textInput: {
    width: '100%', boxSizing: 'border-box', padding: '8px 10px', borderRadius: 6,
    border: '1px solid #2a2e44', background: '#1a1d29', color: '#cfd2dc', fontSize: 12,
  },
  row2: { display: 'flex', flexWrap: 'wrap', gap: 6 },
  resetBtn: {
    padding: '8px 12px', borderRadius: 6, border: '1px solid #3a3e54',
    background: '#1a1d29', color: '#cfd2dc', cursor: 'pointer', fontSize: 12,
    alignSelf: 'flex-start',
  },
  closeBtn: {
    padding: '12px 16px', borderRadius: 8, border: 'none',
    background: '#5a3040', color: '#fff', cursor: 'pointer', fontSize: 14,
    fontWeight: 600, alignSelf: 'stretch', marginTop: 4,
    touchAction: 'manipulation',
  },
};
