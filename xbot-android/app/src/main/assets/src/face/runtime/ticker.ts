export type FrameCallback = (dt: number, nowMs: number) => void;

export function startTicker(cb: FrameCallback): () => void {
  let last = performance.now();
  let raf = 0;
  let running = true;
  const loop = (now: number) => {
    if (!running) return;
    const dt = (now - last) / 1000;
    last = now;
    cb(dt, now);
    raf = requestAnimationFrame(loop);
  };
  raf = requestAnimationFrame(loop);
  return () => {
    running = false;
    cancelAnimationFrame(raf);
  };
}
