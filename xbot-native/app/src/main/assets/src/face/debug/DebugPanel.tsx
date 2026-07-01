import { useEffect, useRef, useState } from 'react';
import { faceController } from '../runtime/controller';
import {
  AMBIENT_PRESETS, getAmbientExpression, setAmbientExpression,
  type AmbientExpressionId,
} from '../render/ambientExpression';
import { AMBIENT_SKINS, getAmbientSkinId, setAmbientSkin } from '../render/ambientSkin';
import { FACE_STYLES, getFaceStyle, setFaceStyle, type FaceStyleId } from '../render/faceStyle';

export function DebugPanel({ onClose }: { onClose?: () => void }) {
  const [gazeMode, setGazeMode] = useState(false);
  const [listening, setListening] = useState(0);
  const [faceStyle, setFaceStyleState] = useState<FaceStyleId>(() => getFaceStyle());
  const [ambExpr, setAmbExpr] = useState<AmbientExpressionId>(() => getAmbientExpression());
  const [ambSkin, setAmbSkin] = useState<string>(() => getAmbientSkinId());
  const listeningRef = useRef(0);

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
    if (!gazeMode) {
      faceController.setGazeTarget(0, 0);
      return;
    }
    const onMove = (e: MouseEvent) => {
      const x = (e.clientX / window.innerWidth) * 2 - 1;
      const y = (e.clientY / window.innerHeight) * 2 - 1;
      faceController.setGazeTarget(x, y);
    };
    window.addEventListener('mousemove', onMove);
    return () => window.removeEventListener('mousemove', onMove);
  }, [gazeMode]);

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
    setGazeMode(false);
    setListening(0);
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

      <Section title="感知信号模拟">
        <label style={s.checkbox}>
          <input
            type="checkbox"
            checked={gazeMode}
            onChange={(e) => setGazeMode(e.target.checked)}
          />
          注视跟随鼠标
        </label>
        <SliderRow
          label="倾听音量"
          value={listening}
          min={0} max={1} step={0.01}
          onChange={setListening}
          format={(v) => v.toFixed(2)}
        />
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
