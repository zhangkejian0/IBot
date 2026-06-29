import type { FaceParams } from '../types';
import { cloneNeutral } from '../params/neutral';

const STIFFNESS = 180;
const STIFFNESS_SLOW = 45;

type Field = { key: string; getTarget: (p: FaceParams) => number; setOut: (p: FaceParams, v: number) => void };

const FIELDS: Field[] = [];

(['leftEye', 'rightEye'] as const).forEach((side) => {
  (['openness', 'upperLidCurve', 'lowerLidCurve', 'lidTilt', 'pupilX', 'pupilY', 'pupilScale', 'highlightOn', 'tearAmount'] as const).forEach((k) => {
    FIELDS.push({
      key: `${side}.${k}`,
      getTarget: (p) => p[side][k],
      setOut: (p, v) => { p[side][k] = v; },
    });
  });
});

(['curve', 'openness', 'width', 'cornerLift'] as const).forEach((k) => {
  FIELDS.push({ key: `mouth.${k}`, getTarget: (p) => p.mouth[k], setOut: (p, v) => { p.mouth[k] = v; } });
});
(['intensity', 'size'] as const).forEach((k) => {
  FIELDS.push({ key: `blush.${k}`, getTarget: (p) => p.blush[k], setOut: (p, v) => { p.blush[k] = v; } });
});
FIELDS.push({ key: 'headTilt', getTarget: (p) => p.headTilt, setOut: (p, v) => { p.headTilt = v; } });
FIELDS.push({ key: 'headBobY', getTarget: (p) => p.headBobY, setOut: (p, v) => { p.headBobY = v; } });
FIELDS.push({ key: 'headPanX', getTarget: (p) => p.headPanX, setOut: (p, v) => { p.headPanX = v; } });
FIELDS.push({ key: 'headPitch', getTarget: (p) => p.headPitch, setOut: (p, v) => { p.headPitch = v; } });

export class SpringTracker {
  private values: number[];
  private velocities: number[];
  private current: FaceParams;
  public stiffness = STIFFNESS;

  constructor() {
    this.current = cloneNeutral();
    this.values = FIELDS.map((f) => f.getTarget(this.current));
    this.velocities = FIELDS.map(() => 0);
  }

  setSlow(slow: boolean) {
    this.stiffness = slow ? STIFFNESS_SLOW : STIFFNESS;
  }

  step(dt: number, target: FaceParams): FaceParams {
    const k = this.stiffness;
    const c = 2 * Math.sqrt(k); // critical damping
    const step = Math.min(dt, 1 / 30); // clamp big dt
    for (let i = 0; i < FIELDS.length; i++) {
      const f = FIELDS[i];
      const t = f.getTarget(target);
      const x = this.values[i];
      const v = this.velocities[i];
      const a = -k * (x - t) - c * v;
      const nv = v + a * step;
      const nx = x + nv * step;
      this.velocities[i] = nv;
      this.values[i] = nx;
      f.setOut(this.current, nx);
    }
    return this.current;
  }

  snapTo(target: FaceParams) {
    for (let i = 0; i < FIELDS.length; i++) {
      const f = FIELDS[i];
      const t = f.getTarget(target);
      this.values[i] = t;
      this.velocities[i] = 0;
      f.setOut(this.current, t);
    }
  }
}
