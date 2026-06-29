import { useEffect, useRef, useState } from 'react';
import { AmbientFace } from './face/render/AmbientFace';
import { LineFace } from './face/render/LineFace';
import { KawaiiFace } from './face/render/KawaiiFace';
import { DebugPanel } from './face/debug/DebugPanel';
import { ExpressionEditor } from './face/debug/ExpressionEditor';
import { readInitialDebugFlag, setDebugEnabled } from './face/debug/debugFlag';
import { FACE_BG } from './face/render/faceLayout';
import { getFaceStyle, subscribeFaceStyle } from './face/render/faceStyle';
import { bindGazePointer } from './face/runtime/gazeInput';

const SECRET_TAP_COUNT = 5;
const SECRET_TAP_WINDOW_MS = 2000;

export function App() {
  const [faceStyle, setFaceStyleState] = useState(() => getFaceStyle());
  const [tab, setTab] = useState<'states' | 'editor'>('states');
  const [debugAvailable, setDebugAvailable] = useState<boolean>(() => readInitialDebugFlag());
  const [panelOpen, setPanelOpen] = useState(() => readInitialDebugFlag());
  const tapsRef = useRef<number[]>([]);
  const faceAreaRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = faceAreaRef.current;
    if (!el) return;
    return bindGazePointer(el);
  }, []);

  useEffect(() => {
    const off = subscribeFaceStyle((id) => setFaceStyleState(id));
    return () => { off(); };
  }, []);

  const FaceComponent =
    faceStyle === 'line' ? LineFace
    : faceStyle === 'kawaii' ? KawaiiFace
    : AmbientFace;

  const openDebug = () => { setDebugEnabled(true); setDebugAvailable(true); setPanelOpen(true); };
  const closeDebug = () => { setDebugEnabled(false); setDebugAvailable(false); setPanelOpen(false); };
  const hidePanel = () => setPanelOpen(false);
  const toggleDebug = () => { if (debugAvailable) closeDebug(); else openDebug(); };

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && debugAvailable && panelOpen) { e.preventDefault(); hidePanel(); return; }
      if (e.key === '`' || e.key === '~') { if (debugAvailable) setPanelOpen((v) => !v); }
      if ((e.ctrlKey || e.metaKey) && e.shiftKey && (e.key === 'D' || e.key === 'd')) {
        e.preventDefault(); toggleDebug();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [debugAvailable, panelOpen]);

  // 左上角连点 5 次开关调试
  const onSecretTap = () => {
    const now = performance.now();
    tapsRef.current = tapsRef.current.filter((t) => now - t < SECRET_TAP_WINDOW_MS);
    tapsRef.current.push(now);
    if (tapsRef.current.length >= SECRET_TAP_COUNT) { tapsRef.current = []; toggleDebug(); }
  };

  return (
    <div style={styles.root}>
      <div ref={faceAreaRef} style={styles.faceArea} className="face-area">
        <FaceComponent />
      </div>

      {!panelOpen && (
        <div
          style={styles.cornerTrigger}
          onClick={onSecretTap}
          onTouchStart={(e) => { e.preventDefault(); onSecretTap(); }}
          aria-hidden
        />
      )}

      {debugAvailable && panelOpen && (
        <div style={styles.panel}>
          <div style={styles.tabBar}>
            <button style={tab === 'states' ? styles.tabActive : styles.tab} onClick={() => setTab('states')}>控制台</button>
            <button style={tab === 'editor' ? styles.tabActive : styles.tab} onClick={() => setTab('editor')}>表情编辑器</button>
            <button style={styles.hideBtn} onClick={hidePanel} title="收起面板（Esc）">−</button>
            <button style={styles.closeBtn} onClick={closeDebug} title="完全关闭">关闭</button>
          </div>
          <div style={styles.panelBody}>
            {tab === 'states' ? <DebugPanel onClose={closeDebug} /> : <ExpressionEditor />}
          </div>
        </div>
      )}

      {debugAvailable && !panelOpen && (
        <button type="button" style={styles.reopenBtn} onClick={() => setPanelOpen(true)}>面板</button>
      )}
    </div>
  );
}

const styles: Record<string, React.CSSProperties> = {
  root: {
    position: 'fixed', inset: 0, background: FACE_BG,
    display: 'flex', flexDirection: 'row', overflow: 'hidden',
  },
  faceArea: {
    flex: 1, display: 'flex', alignItems: 'stretch', justifyContent: 'center',
    minWidth: 0, minHeight: 0, background: 'transparent',
    padding:
      'max(6px, env(safe-area-inset-top)) max(6px, env(safe-area-inset-right)) max(6px, env(safe-area-inset-bottom)) max(6px, env(safe-area-inset-left))',
  },
  cornerTrigger: {
    position: 'fixed', top: 0, left: 0, width: 72, height: 72, zIndex: 1000,
    background: 'transparent', cursor: 'default',
    WebkitTapHighlightColor: 'transparent', touchAction: 'manipulation',
  },
  panel: {
    width: 'min(360px, 80vw)', background: '#15171f', borderLeft: '1px solid #232636',
    display: 'flex', flexDirection: 'column', overflow: 'hidden', fontSize: 13, color: '#cfd2dc', zIndex: 900,
  },
  tabBar: { display: 'flex', alignItems: 'center', padding: '8px 8px', borderBottom: '1px solid #232636', gap: 6 },
  tab: { background: 'transparent', color: '#8a8f9f', border: 'none', padding: '8px 12px', borderRadius: 6, cursor: 'pointer', fontSize: 13, touchAction: 'manipulation' },
  tabActive: { background: '#2a2e44', color: '#fff', border: 'none', padding: '8px 12px', borderRadius: 6, cursor: 'pointer', fontSize: 13, touchAction: 'manipulation' },
  hideBtn: { marginLeft: 'auto', minWidth: 36, height: 36, borderRadius: 6, background: 'transparent', color: '#8a8f9f', border: '1px solid #232636', cursor: 'pointer', fontSize: 18, lineHeight: 1, touchAction: 'manipulation' },
  closeBtn: { minWidth: 52, height: 36, padding: '0 10px', borderRadius: 6, background: '#3a2030', color: '#ffb8cc', border: '1px solid #5a3040', cursor: 'pointer', fontSize: 13, touchAction: 'manipulation' },
  panelBody: { flex: 1, overflowY: 'auto', padding: 12, WebkitOverflowScrolling: 'touch' as any },
  reopenBtn: { position: 'fixed', right: 12, bottom: 12, zIndex: 800, padding: '10px 14px', borderRadius: 8, background: 'rgba(21, 23, 31, 0.88)', color: '#cfd2dc', border: '1px solid #232636', cursor: 'pointer', fontSize: 13, touchAction: 'manipulation' },
};
