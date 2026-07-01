/**
 * 将像素级注视偏移拆成「眼白微移 + 瞳孔偏移」，
 * 模拟眼球在眼眶内滚动（桌面宠物 / EMO 类常见做法）。
 */
export function splitEyeGaze(px: number, py: number, rollShare = 0.22) {
  const pupilShare = 1 - rollShare;
  return {
    rollX: px * rollShare,
    rollY: py * rollShare,
    pupilX: px * pupilShare,
    pupilY: py * pupilShare,
  };
}
