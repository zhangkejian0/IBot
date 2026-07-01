import { useEffect, useMemo, useState } from 'react';
import { EXPRESSION_RECIPES } from '../params/expressions';
import { faceController, updateExpressionRecipe } from '../runtime/controller';
import type {
  ExpressionName, ExpressionRecipe, EyeShape, MouthShape,
  OscillatorKind, OscillatorSpec, OscillatorTarget,
} from '../types';

const EDITABLE_NAMES: ExpressionName[] = [
  'neutral', 'happy', 'confused', 'sleepy', 'sleeping',
  'thinking', 'listening', 'gazing', 'waking',
];

const EYE_SHAPES: EyeShape[] = ['round', 'curvedUp', 'curvedDown', 'narrow', 'halfLid', 'closed'];
const MOUTH_SHAPES: MouthShape[] = ['neutral', 'smile', 'bigSmile', 'frown', 'o', 'small'];
const OSC_KINDS: OscillatorKind[] = ['sine', 'pulse', 'bounce', 'wiggle', 'sway', 'breathDecay'];
const OSC_TARGETS: OscillatorTarget[] = [
  'leftEye.openness', 'leftEye.pupilX', 'leftEye.pupilY', 'leftEye.pupilScale', 'leftEye.upperLidCurve', 'leftEye.tearAmount',
  'rightEye.openness', 'rightEye.pupilX', 'rightEye.pupilY', 'rightEye.pupilScale', 'rightEye.upperLidCurve', 'rightEye.tearAmount',
  'mouth.curve', 'mouth.openness', 'mouth.cornerLift', 'mouth.width',
  'blush.intensity', 'headTilt', 'headBobY', 'pupilScale',
];

function deepClone<T>(v: T): T {
  return JSON.parse(JSON.stringify(v));
}

export function ExpressionEditor() {
  const [editing, setEditing] = useState<ExpressionName>('happy');
  const [recipe, setRecipe] = useState<ExpressionRecipe>(() => deepClone(EXPRESSION_RECIPES.happy));
  const [intensity, setIntensity] = useState(100);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setRecipe(deepClone(EXPRESSION_RECIPES[editing]));
  }, [editing]);

  useEffect(() => {
    faceController.setState('idle');
    return () => {
      faceController.setExpression(editing, 0);
    };
  }, [editing]);

  useEffect(() => {
    updateExpressionRecipe(editing, recipe);
    faceController.setExpression(editing, intensity);
  }, [editing, recipe, intensity]);

  const update = (patch: Partial<ExpressionRecipe>) => setRecipe((r) => ({ ...r, ...patch }));

  const setAnim = (i: number, patch: Partial<OscillatorSpec>) => {
    setRecipe((r) => {
      const anims = [...(r.animations ?? [])];
      anims[i] = { ...anims[i], ...patch };
      return { ...r, animations: anims };
    });
  };
  const addAnim = () => {
    setRecipe((r) => ({
      ...r,
      animations: [...(r.animations ?? []), { kind: 'sine' as OscillatorKind, target: 'headBobY' as OscillatorTarget, amp: 1, freq: 0.5 }],
    }));
  };
  const removeAnim = (i: number) => {
    setRecipe((r) => {
      const anims = [...(r.animations ?? [])];
      anims.splice(i, 1);
      return { ...r, animations: anims };
    });
  };

  const exported = useMemo(() => JSON.stringify(recipe, null, 2), [recipe]);

  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(`${editing}: ${exported},`);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // clipboard may be blocked; user can manually copy from textarea
    }
  };

  return (
    <div style={s.root}>
      <div style={s.section}>
        <div style={s.sectionTitle}>编辑表情</div>
        <div style={s.expList}>
          {EDITABLE_NAMES.map((n) => (
            <button
              key={n}
              style={editing === n ? s.expBtnActive : s.expBtn}
              onClick={() => setEditing(n)}
            >
              {n}
            </button>
          ))}
        </div>
      </div>

      <div style={s.section}>
        <div style={s.sectionTitle}>眼睛形状</div>
        <div style={s.chipRow}>
          {EYE_SHAPES.map((sh) => (
            <button
              key={sh}
              style={(recipe.eyeShape ?? 'round') === sh ? s.chipActive : s.chip}
              onClick={() => update({ eyeShape: sh })}
            >{sh}</button>
          ))}
        </div>
      </div>

      <div style={s.section}>
        <div style={s.sectionTitle}>嘴形</div>
        <div style={s.chipRow}>
          {MOUTH_SHAPES.map((sh) => (
            <button
              key={sh}
              style={(recipe.mouthShape ?? 'neutral') === sh ? s.chipActive : s.chip}
              onClick={() => update({ mouthShape: sh })}
            >{sh}</button>
          ))}
        </div>
      </div>

      <Slider label="smileAmount" value={recipe.smileAmount ?? 0} min={-1} max={1} step={0.01}
        onChange={(v) => update({ smileAmount: v })} fmt={(v) => v.toFixed(2)} />
      <Slider label="blush" value={recipe.blush ?? 0} min={0} max={1} step={0.01}
        onChange={(v) => update({ blush: v })} fmt={(v) => v.toFixed(2)} />
      <Slider label="headTilt" value={recipe.headTilt ?? 0} min={-15} max={15} step={0.5}
        onChange={(v) => update({ headTilt: v })} fmt={(v) => v.toFixed(1) + '°'} />
      <Slider label="eyebrowAsym" value={recipe.eyebrowAsymmetry ?? 0} min={-1} max={1} step={0.01}
        onChange={(v) => update({ eyebrowAsymmetry: v })} fmt={(v) => v.toFixed(2)} />
      <Slider label="highlightDim" value={recipe.highlightDim ?? 0} min={0} max={1} step={0.01}
        onChange={(v) => update({ highlightDim: v })} fmt={(v) => v.toFixed(2)} />
      <Slider label="tearAmount" value={recipe.tearAmount ?? 0} min={0} max={1} step={0.01}
        onChange={(v) => update({ tearAmount: v })} fmt={(v) => v.toFixed(2)} />

      <div style={s.section}>
        <div style={s.sectionTitle}>动效（oscillators）</div>
        {(recipe.animations ?? []).map((a, i) => (
          <div key={i} style={s.animRow}>
            <div style={s.animSelects}>
              <select value={a.kind} onChange={(e) => setAnim(i, { kind: e.target.value as OscillatorKind })} style={s.select}>
                {OSC_KINDS.map((k) => <option key={k} value={k}>{k}</option>)}
              </select>
              <select value={a.target} onChange={(e) => setAnim(i, { target: e.target.value as OscillatorTarget })} style={s.select}>
                {OSC_TARGETS.map((t) => <option key={t} value={t}>{t}</option>)}
              </select>
              <button style={s.removeBtn} onClick={() => removeAnim(i)}>✕</button>
            </div>
            <Slider label="amp" value={a.amp} min={0} max={5} step={0.01}
              onChange={(v) => setAnim(i, { amp: v })} fmt={(v) => v.toFixed(2)} compact />
            <Slider label="freq" value={a.freq} min={0.05} max={3} step={0.01}
              onChange={(v) => setAnim(i, { freq: v })} fmt={(v) => v.toFixed(2) + 'Hz'} compact />
          </div>
        ))}
        <button style={s.addBtn} onClick={addAnim}>+ 添加动效</button>
      </div>

      <div style={s.section}>
        <div style={s.sectionTitle}>预览强度</div>
        <Slider label="" value={intensity} min={0} max={100} step={1}
          onChange={setIntensity} fmt={(v) => String(Math.round(v))} />
      </div>

      <div style={s.section}>
        <div style={s.sectionTitle}>导出</div>
        <button style={s.copyBtn} onClick={onCopy}>
          {copied ? '已复制 ✓' : '复制 JSON 到剪贴板'}
        </button>
        <textarea readOnly value={exported} style={s.textarea} />
      </div>
    </div>
  );
}

function Slider({
  label, value, min, max, step, onChange, fmt, compact,
}: {
  label: string;
  value: number;
  min: number; max: number; step: number;
  onChange: (v: number) => void;
  fmt?: (v: number) => string;
  compact?: boolean;
}) {
  return (
    <div style={compact ? s.rowCompact : s.row}>
      {label && <div style={s.rowLabel}>{label}</div>}
      <input
        type="range" min={min} max={max} step={step} value={value}
        onChange={(e) => onChange(parseFloat(e.target.value))}
        style={s.range}
      />
      <div style={s.rowValue}>{fmt ? fmt(value) : String(value)}</div>
    </div>
  );
}

const s: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', gap: 14 },
  section: { display: 'flex', flexDirection: 'column', gap: 6 },
  sectionTitle: { fontSize: 12, color: '#8a8f9f', letterSpacing: 0.5 },
  expList: { display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 6 },
  expBtn: { padding: '6px 4px', borderRadius: 6, border: '1px solid #2a2e44', background: '#1a1d29', color: '#cfd2dc', cursor: 'pointer', fontSize: 11 },
  expBtnActive: { padding: '6px 4px', borderRadius: 6, border: '1px solid #FF9DBE', background: '#3a2030', color: '#fff', cursor: 'pointer', fontSize: 11 },
  chipRow: { display: 'flex', flexWrap: 'wrap', gap: 4 },
  chip: { padding: '4px 8px', borderRadius: 12, border: '1px solid #2a2e44', background: '#1a1d29', color: '#cfd2dc', cursor: 'pointer', fontSize: 11 },
  chipActive: { padding: '4px 8px', borderRadius: 12, border: '1px solid #7BD4FF', background: '#2a3a5a', color: '#fff', cursor: 'pointer', fontSize: 11 },
  row: { display: 'grid', gridTemplateColumns: '90px 1fr 50px', alignItems: 'center', gap: 8 },
  rowCompact: { display: 'grid', gridTemplateColumns: '40px 1fr 50px', alignItems: 'center', gap: 6 },
  rowLabel: { fontSize: 11, color: '#cfd2dc' },
  rowValue: { fontSize: 10, color: '#8a8f9f', textAlign: 'right', fontVariantNumeric: 'tabular-nums' },
  range: { width: '100%' },
  animRow: { padding: 8, borderRadius: 6, background: '#1a1d29', border: '1px solid #232636', display: 'flex', flexDirection: 'column', gap: 4 },
  animSelects: { display: 'flex', gap: 4, alignItems: 'center' },
  select: { flex: 1, background: '#0d0f17', color: '#cfd2dc', border: '1px solid #2a2e44', borderRadius: 4, padding: '3px 4px', fontSize: 11 },
  removeBtn: { background: 'transparent', color: '#8a8f9f', border: '1px solid #2a2e44', borderRadius: 4, padding: '2px 6px', cursor: 'pointer', fontSize: 11 },
  addBtn: { padding: '6px 10px', borderRadius: 6, border: '1px dashed #3a3e54', background: 'transparent', color: '#7BD4FF', cursor: 'pointer', fontSize: 11 },
  copyBtn: { padding: '8px 12px', borderRadius: 6, border: 'none', background: '#7BD4FF', color: '#0b0d12', cursor: 'pointer', fontSize: 12, fontWeight: 600 },
  textarea: { width: '100%', minHeight: 120, fontFamily: 'ui-monospace, Menlo, monospace', fontSize: 11, padding: 8, borderRadius: 6, background: '#0d0f17', color: '#cfd2dc', border: '1px solid #2a2e44', resize: 'vertical' },
};
