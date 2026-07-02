import {
  FACE_DISPLAY_BOX,
  FACE_GROUP_BOUNDS,
} from '../render/faceLayout';

export type GazePanLimits = {
  maxPanLeft: number;
  maxPanRight: number;
  maxPanUp: number;
  maxPanDown: number;
};

let viewportW = 360;
let viewportH = 640;

export function setGazeViewport(width: number, height: number) {
  viewportW = Math.max(1, width);
  viewportH = Math.max(1, height);
}

/**
 * 根据容器尺寸 + SVG meet 缩放，计算 `<g>` 贴屏幕边缘时的最大平移（viewBox 单位）。
 */
export function computeGazePanLimits(
  vpW = viewportW,
  vpH = viewportH,
): GazePanLimits {
  const vb = FACE_DISPLAY_BOX;
  const scale = Math.min(vpW / vb.w, vpH / vb.h);
  const offsetX = (vpW - vb.w * scale) / 2;
  const offsetY = (vpH - vb.h * scale) / 2;

  return {
    maxPanLeft: (FACE_GROUP_BOUNDS.left - vb.x) + offsetX / scale,
    maxPanRight: (vb.x + vb.w - FACE_GROUP_BOUNDS.right) + offsetX / scale,
    maxPanUp: (FACE_GROUP_BOUNDS.top - vb.y) + offsetY / scale,
    maxPanDown: (vb.y + vb.h - FACE_GROUP_BOUNDS.bottom) + offsetY / scale,
  };
}

export function getGazePanLimits(): GazePanLimits {
  return computeGazePanLimits();
}
