import { useState } from 'react';
import { NEUTRAL_PARAMS } from '../face/params/neutral';
import type { EyeParams } from '../face/types';
import { FACE_SKIN, FACE_VIEW_H, FACE_VIEW_W, faceViewBox } from '../face/render/faceLayout';
import { ReferenceFaceScene, ReferenceFaceSceneClips } from '../face/render/ReferenceFaceScene';
import { ReferenceEyeDefs } from '../face/render/ReferenceEyeGraphic';

function makeEye(overrides: Partial<EyeParams>): EyeParams {
  return { ...NEUTRAL_PARAMS.leftEye, ...overrides };
}

export function AnimeEyePreview() {
  const [tear, setTear] = useState(0.85);
  const [openness, setOpenness] = useState(1);
  const [blush, setBlush] = useState(0.55);
  const [showRef, setShowRef] = useState(true);
  const [darkBg, setDarkBg] = useState(true);

  const eye = makeEye({ tearAmount: tear, openness, highlightOn: 1 });

  return (
    <div style={styles.root}>
      <div style={styles.header}>
        <div style={styles.title}>参考图级动漫眼 · 预览 v3</div>
        <div style={styles.sub}>ReferenceFaceScene · 与主 Face 共用渲染</div>
      </div>

      <div style={styles.compareRow}>
        {showRef && (
          <div style={styles.refPanel}>
            <div style={styles.panelLabel}>参考图</div>
            <img
              src="/assets/reference-eye.png"
              alt="reference"
              style={styles.refImg}
              onError={(e) => { (e.target as HTMLImageElement).style.display = 'none'; }}
            />
          </div>
        )}

        <div style={styles.svgPanel}>
          <div style={styles.panelLabel}>当前绘制</div>
          <svg viewBox={faceViewBox()} style={styles.svg}>
            <defs>
              <ReferenceEyeDefs />
              <ReferenceFaceSceneClips clipPrefix="ref" />
            </defs>
            <rect width={FACE_VIEW_W} height={FACE_VIEW_H} fill={darkBg ? '#111' : FACE_SKIN} />
            <ReferenceFaceScene
              leftEye={eye} rightEye={eye} blush={blush} clipPrefix="ref"
            />
          </svg>
        </div>
      </div>

      <div style={styles.controls}>
        <label style={styles.label}>
          tearAmount
          <input type="range" min={0} max={1} step={0.01} value={tear} onChange={(e) => setTear(+e.target.value)} style={styles.range} />
          <span style={styles.val}>{tear.toFixed(2)}</span>
        </label>
        <label style={styles.label}>
          openness
          <input type="range" min={0} max={1} step={0.01} value={openness} onChange={(e) => setOpenness(+e.target.value)} style={styles.range} />
          <span style={styles.val}>{openness.toFixed(2)}</span>
        </label>
        <label style={styles.label}>
          blush
          <input type="range" min={0} max={1} step={0.01} value={blush} onChange={(e) => setBlush(+e.target.value)} style={styles.range} />
          <span style={styles.val}>{blush.toFixed(2)}</span>
        </label>
        <label style={styles.checkLabel}>
          <input type="checkbox" checked={showRef} onChange={(e) => setShowRef(e.target.checked)} />
          显示参考图对比
        </label>
        <label style={styles.checkLabel}>
          <input type="checkbox" checked={darkBg} onChange={(e) => setDarkBg(e.target.checked)} />
          黑色背景
        </label>
      </div>

      <div style={styles.footer}>
        预览地址：<code style={styles.code}>http://localhost:5173/?preview=eye</code>
      </div>
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  root: {
    minHeight: '100vh', background: '#111', color: '#ccc',
    display: 'flex', flexDirection: 'column', alignItems: 'center',
    padding: '20px 16px 36px', fontFamily: 'system-ui, sans-serif',
  },
  header: { textAlign: 'center', marginBottom: 16 },
  title: { fontSize: 18, fontWeight: 600, color: '#fff' },
  sub: { fontSize: 13, color: '#888', marginTop: 4 },
  compareRow: { display: 'flex', gap: 20, alignItems: 'flex-start', flexWrap: 'wrap', justifyContent: 'center' },
  refPanel: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 },
  svgPanel: { display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8 },
  panelLabel: { fontSize: 12, color: '#666' },
  refImg: { width: 420, borderRadius: 8, border: '1px solid #333' },
  svg: { width: 'min(520px, 92vw)', height: 'auto', display: 'block', borderRadius: 8 },
  controls: { width: 'min(420px, 92vw)', marginTop: 20, display: 'flex', flexDirection: 'column', gap: 12 },
  label: { display: 'grid', gridTemplateColumns: '90px 1fr 40px', alignItems: 'center', gap: 10, fontSize: 13 },
  checkLabel: { fontSize: 13, display: 'flex', alignItems: 'center', gap: 8 },
  range: { width: '100%', accentColor: '#F09828' },
  val: { fontSize: 12, color: '#888', fontVariantNumeric: 'tabular-nums' },
  footer: { marginTop: 20, fontSize: 12, color: '#666' },
  code: { background: '#222', padding: '2px 6px', borderRadius: 4, color: '#F09828' },
};
