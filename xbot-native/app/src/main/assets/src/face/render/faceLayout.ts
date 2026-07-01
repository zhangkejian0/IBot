/** 与 AnimeEyePreview / debug 预览完全一致的脸部布局 */
export const FACE_VIEW_W = 1000;
export const FACE_VIEW_H = 620;
export const FACE_EYE_CY = 310;
export const FACE_LEFT_EYE_CX = 280;
export const FACE_RIGHT_EYE_CX = 720;
export const FACE_CENTER_X = FACE_VIEW_W / 2;
export const FACE_CENTER_Y = FACE_EYE_CY;
export const FACE_MOUTH_CX = 500;
export const FACE_MOUTH_CY = 500;

/** 全黑底 */
export const FACE_BG = '#0A0A0A';
/** @deprecated 预览页肤色对比用 */
export const FACE_SKIN = '#F5D8C8';

/** 横屏手机显示用裁切框 — 聚焦眉眼嘴，减少留白 */
export const FACE_DISPLAY_BOX = { x: 60, y: 200, w: 880, h: 340 };

export function faceViewBox() {
  return `0 0 ${FACE_VIEW_W} ${FACE_VIEW_H}`;
}

export function faceDisplayViewBox() {
  const { x, y, w, h } = FACE_DISPLAY_BOX;
  return `${x} ${y} ${w} ${h}`;
}
