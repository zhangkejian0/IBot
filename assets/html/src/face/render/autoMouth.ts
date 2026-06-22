/**
 * `mouth==='auto'` 时的统一嘴形选择。
 *
 * 三个渲染器(AmbientFace / LineFace / KawaiiFace)都把 mouth 预设的 `auto`
 * 分流到本函数，再各自把返回值映射到自己的 MouthMode / 坐标 / 颜色。
 * 阈值只维护这一处，避免三个文件不一致。
 *
 * 判断优先级与历史实现（原 AmbientFace.tsx 内联）一致：
 *   1. isHappy || curve > SMILE_ON  → bigSmile（笑弧）
 *   2. curve < SMILE_OFF            → frown（撇嘴）
 *   3. openness > OPEN_ON           → open（张嘴椭圆）
 *   4. 否则                          → cat（默认）
 */

export type AutoMouthChoice = 'bigSmile' | 'frown' | 'open' | 'cat';

/** curve 高于此值（或 isHappy）→ 笑。来自 happy recipe 的 curve≈0.9。 */
export const SMILE_ON = 0.35;
/** curve 低于此值 → 撇嘴（负面情绪）。 */
export const SMILE_OFF = -0.2;
/** openness 高于此值 → 张嘴（说话/惊讶）。 */
export const OPEN_ON = 0.15;

export function chooseAutoMouth(
  curve: number,
  openness: number,
  isHappy = false,
): AutoMouthChoice {
  if (isHappy || curve > SMILE_ON) return 'bigSmile';
  if (curve < SMILE_OFF) return 'frown';
  if (openness > OPEN_ON) return 'open';
  return 'cat';
}
