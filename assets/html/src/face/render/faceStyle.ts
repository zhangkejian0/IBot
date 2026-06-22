/** 当前显示风格（氛围 / 线条），持久化到 localStorage。 */

export type FaceStyleId = 'ambient' | 'line' | 'kawaii';

export const FACE_STYLES: { id: FaceStyleId; label: string }[] = [
  { id: 'ambient', label: '氛围' },
  { id: 'line', label: '线条' },
  { id: 'kawaii', label: '卡哇伊' },
];

const STORAGE_KEY = 'face.style';

function readInitial(): FaceStyleId {
  try {
    const v = localStorage.getItem(STORAGE_KEY);
    if (v === 'ambient' || v === 'line' || v === 'kawaii') return v;
  } catch { /* ignore */ }
  return 'ambient';
}

let current: FaceStyleId = readInitial();
const subs = new Set<(id: FaceStyleId) => void>();

export function getFaceStyle() {
  return current;
}
export function setFaceStyle(id: FaceStyleId) {
  current = id;
  try { localStorage.setItem(STORAGE_KEY, id); } catch { /* ignore */ }
  subs.forEach((f) => f(id));
}
export function subscribeFaceStyle(f: (id: FaceStyleId) => void) {
  subs.add(f);
  return () => subs.delete(f);
}
