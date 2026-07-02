import { faceController } from './controller';
import { setGazeViewport } from './gazeViewport';

const GAZE_CLAMP = 1;

function clampGaze(v: number): number {
  return Math.max(-GAZE_CLAMP, Math.min(GAZE_CLAMP, v));
}

/** 将指针坐标转为相对画面中心的注视向量 (-1..1) */
export function pointerToGaze(clientX: number, clientY: number, root: HTMLElement): { x: number; y: number } {
  const rect = root.getBoundingClientRect();
  if (rect.width < 1 || rect.height < 1) return { x: 0, y: 0 };
  const cx = rect.left + rect.width * 0.5;
  const cy = rect.top + rect.height * 0.5;
  const halfW = rect.width * 0.5;
  const halfH = rect.height * 0.5;
  return {
    x: clampGaze((clientX - cx) / halfW),
    y: clampGaze((clientY - cy) / halfH),
  };
}

/**
 * 绑定鼠标/触摸注视输入。相对脸区中心计算方向，比整窗映射更自然。
 * 返回清理函数。
 */
export function bindGazePointer(root: HTMLElement): () => void {
  let externalLock = false;

  const syncViewport = () => {
    const rect = root.getBoundingClientRect();
    setGazeViewport(rect.width, rect.height);
  };

  syncViewport();
  const ro = typeof ResizeObserver !== 'undefined'
    ? new ResizeObserver(syncViewport)
    : null;
  ro?.observe(root);
  window.addEventListener('resize', syncViewport);

  const push = (clientX: number, clientY: number) => {
    if (externalLock || !faceController.isGazeInputEnabled()) return;
    const { x, y } = pointerToGaze(clientX, clientY, root);
    faceController.setGazeTarget(x, y, 'pointer');
  };

  const onMove = (e: MouseEvent) => push(e.clientX, e.clientY);
  const onTouch = (e: TouchEvent) => {
    const t = e.touches[0];
    if (t) push(t.clientX, t.clientY);
  };
  const onLeave = () => {
    if (!externalLock) faceController.setGazeTarget(0, 0);
  };

  const offExternal = faceController.onGazeExternalChange((active) => {
    externalLock = active;
    if (active) faceController.setGazeTarget(0, 0);
  });

  root.addEventListener('mousemove', onMove);
  root.addEventListener('mouseleave', onLeave);
  root.addEventListener('touchmove', onTouch, { passive: true });
  root.addEventListener('touchend', onLeave);

  return () => {
    offExternal();
    ro?.disconnect();
    window.removeEventListener('resize', syncViewport);
    root.removeEventListener('mousemove', onMove);
    root.removeEventListener('mouseleave', onLeave);
    root.removeEventListener('touchmove', onTouch);
    root.removeEventListener('touchend', onLeave);
  };
}
