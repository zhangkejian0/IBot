/**
 * 氛围风格（AmbientFace）专用「表情预设」层。
 * 表情库 —— 由主页/调试面板点选，渲染器订阅后应用。
 * 'idle' = 默认中性表情（环境光跟随皮肤）。
 */

export type EyeStyle =
  | 'normal'       // 圆瞳大眼
  | 'star'         // 星星眼 ★
  | 'wide'         // 惊讶：瞪大 + 小瞳孔
  | 'angry'        // 生气：上挑斜眼
  | 'sleepyLid'    // 半眯/低头
  | 'wink'         // 单眼眨（左闭右睁）
  | 'closedSmile'  // 弯弯笑眼 ^_^
  | 'shy'          // 害羞：眼帘下压 + 上看
  | 'dead'         // ✕ ✕ 晕倒/错误
  | 'dizzy'        // @ @ 漩涡晕眩
  | 'blank';       // 石化：空白竖线眼

export type MouthKind =
  | 'auto' | 'smile' | 'bigSmile' | 'open' | 'flat'
  | 'frown' | 'pout' | 'cat' | 'wavy' | 'small' | 'hidden';

export type PropKind =
  | 'sweat'    // 流汗水滴
  | 'cup'      // 饮料杯
  | 'food'     // 饭团
  | 'camera'   // 相机 + 闪光
  | 'moon'     // 夜间模式月亮
  | 'magic'    // 环绕魔法星
  | 'question' // 疑惑问号
  | 'anger'    // 怒气青筋
  | 'crack'    // 石化裂纹
  | 'glass';   // 放大镜观察

export type AmbientDescriptor = {
  aura: string;
  eye: EyeStyle;
  mouth: MouthKind;
  blush: number;            // 0..1
  prop?: PropKind;
  headTilt?: number;        // 额外固定头倾角
  shake?: number;           // 左右抖动幅度(px)
  spin?: boolean;           // 晕眩缓摆
  desaturate?: boolean;     // 石化去色
  glowBoost?: number;       // 整体辉光增强 0..1
  useSkinGlow?: boolean;    // 中性：环境/情绪光跟随皮肤
  label?: string;
};

export type AmbientExpressionId =
  | 'idle'
  | 'calm' | 'surprised' | 'doubt' | 'angry' | 'faint' | 'shy'
  | 'wink' | 'nervous' | 'dizzy' | 'petrified' | 'squint'
  | 'drinking' | 'eating' | 'photo' | 'glow' | 'magic' | 'observe';

export const AMBIENT_PRESETS: {
  id: AmbientExpressionId;
  label: string;
  desc: AmbientDescriptor;
}[] = [
  { id: 'idle',      label: '默认',       desc: { aura: '#FF9978', eye: 'normal',      mouth: 'auto',  blush: 0.5, useSkinGlow: true } },

  { id: 'calm',      label: '平静/微笑',   desc: { aura: '#FFB27D', eye: 'closedSmile', mouth: 'smile', blush: 0.32 } },
  { id: 'surprised', label: '惊讶/专注',   desc: { aura: '#7BC0FF', eye: 'wide',        mouth: 'open',  blush: 0.18, label: '！' } },
  { id: 'doubt',     label: '疑惑/困惑',   desc: { aura: '#74DCAA', eye: 'normal',      mouth: 'small', blush: 0.1, prop: 'question', headTilt: -9 } },
  { id: 'angry',     label: '生气/愤怒',   desc: { aura: '#FF6B6B', eye: 'angry',       mouth: 'frown', blush: 0.0, prop: 'anger', shake: 3 } },
  { id: 'faint',     label: '晕倒/错误',   desc: { aura: '#8A8FA0', eye: 'dead',        mouth: 'wavy',  blush: 0.0, desaturate: true, label: 'ERROR' } },
  { id: 'shy',       label: '害羞/脸红',   desc: { aura: '#FF9DC4', eye: 'shy',         mouth: 'small', blush: 1.0 } },
  { id: 'wink',      label: '眨眼/不对称', desc: { aura: '#FFCC55', eye: 'wink',        mouth: 'smile', blush: 0.5 } },
  { id: 'nervous',   label: '紧张/流汗',   desc: { aura: '#A6D8C0', eye: 'normal',      mouth: 'wavy',  blush: 0.15, prop: 'sweat' } },
  { id: 'dizzy',     label: '晕眩/旋转',   desc: { aura: '#C0A0FF', eye: 'dizzy',       mouth: 'wavy',  blush: 0.2, spin: true } },
  { id: 'petrified', label: '石化/僵硬',   desc: { aura: '#9098A4', eye: 'blank',       mouth: 'flat',  blush: 0.0, prop: 'crack', desaturate: true } },
  { id: 'squint',    label: '斜视/低头',   desc: { aura: '#7090D0', eye: 'sleepyLid',   mouth: 'small', blush: 0.0, headTilt: 6 } },

  { id: 'drinking',  label: '喝饮料',      desc: { aura: '#FFB27D', eye: 'closedSmile', mouth: 'hidden', blush: 0.4, prop: 'cup' } },
  { id: 'eating',    label: '吃东西',      desc: { aura: '#FFC07A', eye: 'normal',      mouth: 'open',  blush: 0.4, prop: 'food' } },
  { id: 'photo',     label: '拍照/记录',   desc: { aura: '#7BC0FF', eye: 'normal',      mouth: 'smile', blush: 0.25, prop: 'camera' } },
  { id: 'glow',      label: '发光/夜间',   desc: { aura: '#9DB4FF', eye: 'normal',      mouth: 'small', blush: 0.2, prop: 'moon', glowBoost: 1, label: '夜间模式' } },
  { id: 'magic',     label: '魔法/施法',   desc: { aura: '#C77BFF', eye: 'star',        mouth: 'smile', blush: 0.35, prop: 'magic', glowBoost: 0.6 } },
  { id: 'observe',   label: '观察/发现',   desc: { aura: '#56C8FF', eye: 'normal',      mouth: 'small', blush: 0.1, prop: 'glass' } },
];

let current: AmbientExpressionId = 'idle';
const subs = new Set<(id: AmbientExpressionId) => void>();

export function setAmbientExpression(id: AmbientExpressionId) {
  current = id;
  subs.forEach((f) => f(id));
}
export function getAmbientExpression() {
  return current;
}
export function subscribeAmbientExpression(f: (id: AmbientExpressionId) => void) {
  subs.add(f);
  return () => subs.delete(f);
}
