import type { FaceParams } from '../types';

export type HeadTransformExtra = {
  tilt?: number;
  shakeX?: number;
  scale?: number;
  scaleX?: number;
  scaleY?: number;
  /** 俯仰/偏航枢轴：相对脸心的 Y 偏移 */
  neckYOffset?: number;
};

/**
 * 头部变换：先平移到目标位置，再绕「随头移动的」颈部枢轴旋转。
 * 旋转必须用相对坐标 (0, neckY-cy)，不可用绝对 cx/neckY，否则对角线会错位。
 */
export function buildHeadTransform(
  cx: number,
  cy: number,
  head: Pick<FaceParams, 'headPanX' | 'headBobY' | 'headTilt' | 'headPitch'>,
  extra: HeadTransformExtra = {},
): string {
  const pivotDy = (extra.neckYOffset ?? 72);
  const px = cx + (extra.shakeX ?? 0) + head.headPanX;
  const py = cy + head.headBobY;
  const yaw = head.headTilt + (extra.tilt ?? 0);
  const sx = extra.scaleX ?? extra.scale ?? 1;
  const sy = extra.scaleY ?? extra.scale ?? 1;

  return [
    `translate(${px.toFixed(2)} ${py.toFixed(2)})`,
    `rotate(${yaw.toFixed(3)} 0 ${pivotDy.toFixed(2)})`,
    `rotate(${head.headPitch.toFixed(3)} 0 ${pivotDy.toFixed(2)})`,
    `scale(${sx.toFixed(4)} ${sy.toFixed(4)})`,
    `translate(${-cx.toFixed(2)} ${-cy.toFixed(2)})`,
  ].join(' ');
}
