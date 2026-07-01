/**
 * 氛围风格（AmbientFace）整体皮肤。
 * 改背景 + 眼白/瞳孔渐变 + 腮红 + 高光色。与「表情预设」正交。
 * 选择会持久化到 localStorage。
 */

export type AmbientSkin = {
  id: string;
  label: string;
  bg: string;
  glow: string;                          // 屏幕环境光色（整体光效）
  eyeStops: [string, string, string];   // 眼白珍珠渐变 0% / 55% / 100%
  pupilStops: [string, string, string]; // 瞳孔渐变 0% / 55% / 100%
  blush: string;                         // 腮红色
  highlight: string;                     // 高光/笑线色
};

export const AMBIENT_SKINS: AmbientSkin[] = [
  {
    id: 'midnight', label: '暗夜紫',
    bg: '#0A0A14', glow: '#7A6CF0',
    eyeStops: ['#FFFFFF', '#F4F0FA', '#D6CFE6'],
    pupilStops: ['#3C3870', '#15102E', '#02000A'],
    blush: '#FF9DC4', highlight: '#FFFFFF',
  },
  {
    id: 'sakura', label: '樱花粉',
    bg: '#1A0E16', glow: '#FF8FC0',
    eyeStops: ['#FFFFFF', '#FFF0F5', '#F2D6E2'],
    pupilStops: ['#7A4A5A', '#4A2030', '#1A0810'],
    blush: '#FF8FB8', highlight: '#FFF6FA',
  },
  {
    id: 'mint', label: '薄荷青',
    bg: '#07140F', glow: '#56E0B8',
    eyeStops: ['#FFFFFF', '#EAFBF4', '#CFE9DF'],
    pupilStops: ['#2A6A5A', '#103A30', '#02120C'],
    blush: '#8FE0C8', highlight: '#F0FFFA',
  },
  {
    id: 'sunset', label: '暖阳橙',
    bg: '#160B06', glow: '#FF9E5A',
    eyeStops: ['#FFFDF6', '#FFF0DC', '#EAD2B0'],
    pupilStops: ['#7A4A20', '#4A2810', '#160A02'],
    blush: '#FFB07A', highlight: '#FFF8EC',
  },
  {
    id: 'aurora', label: '极光蓝',
    bg: '#06101A', glow: '#5AC8FF',
    eyeStops: ['#FFFFFF', '#E6F6FF', '#C8E2F0'],
    pupilStops: ['#2A5A78', '#103048', '#02101C'],
    blush: '#8FD0FF', highlight: '#EEFAFF',
  },
  {
    id: 'gold', label: '暗金',
    bg: '#100C04', glow: '#E8B65A',
    eyeStops: ['#FFFDF0', '#FBF2D8', '#E6D6A8'],
    pupilStops: ['#7A6020', '#4A3810', '#160E02'],
    blush: '#F0D080', highlight: '#FFFBE8',
  },
];

const STORAGE_KEY = 'ambient.skin';

function readInitial(): string {
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved && AMBIENT_SKINS.some((s) => s.id === saved)) return saved;
  } catch { /* ignore */ }
  return AMBIENT_SKINS[0].id;
}

let currentId = readInitial();
const subs = new Set<(skin: AmbientSkin) => void>();

export function getAmbientSkin(): AmbientSkin {
  return AMBIENT_SKINS.find((s) => s.id === currentId) ?? AMBIENT_SKINS[0];
}
export function getAmbientSkinId() {
  return currentId;
}
export function setAmbientSkin(id: string) {
  if (!AMBIENT_SKINS.some((s) => s.id === id)) return;
  currentId = id;
  try { localStorage.setItem(STORAGE_KEY, id); } catch { /* ignore */ }
  const skin = getAmbientSkin();
  subs.forEach((f) => f(skin));
}
export function subscribeAmbientSkin(f: (skin: AmbientSkin) => void) {
  subs.add(f);
  return () => subs.delete(f);
}
