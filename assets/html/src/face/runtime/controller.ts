import type { ExpressionName, ExpressionOverlay, FaceParams, FaceState, ExpressionRecipe } from '../types';
import { EXPRESSION_RECIPES } from '../params/expressions';
import { compileRecipe, type CompiledExpression } from '../params/compile';
import { blendParams } from '../params/blend';
import { cloneNeutral, NEUTRAL_PARAMS } from '../params/neutral';
import { SpringTracker } from './springs';
import { applyOscillators, type ActiveOscillator } from './oscillators';
import { applyMicroMotion } from './microMotion';
import { startTicker } from './ticker';
import { faceMachine } from '../fsm/machine';
import { STATE_SPECS } from '../fsm/stateToExpression';
import { eventBus } from './eventBus';

const RECIPE_CACHE = new Map<ExpressionName, CompiledExpression>();
function getCompiled(name: ExpressionName): CompiledExpression {
  let c = RECIPE_CACHE.get(name);
  if (!c) {
    c = compileRecipe(EXPRESSION_RECIPES[name]);
    RECIPE_CACHE.set(name, c);
  }
  return c;
}

export function invalidateExpressionCache(name?: ExpressionName) {
  if (name) RECIPE_CACHE.delete(name);
  else RECIPE_CACHE.clear();
}

export function updateExpressionRecipe(name: ExpressionName, recipe: ExpressionRecipe) {
  EXPRESSION_RECIPES[name] = recipe;
  RECIPE_CACHE.delete(name);
}

export type RenderCallback = (params: FaceParams) => void;

class FaceController {
  private spring = new SpringTracker();
  private overlays = new Map<ExpressionName, number>();
  private gazeX = 0;
  private gazeY = 0;
  private listeningLoudness = 0;
  private renderCb: RenderCallback | null = null;
  private stopTicker: (() => void) | null = null;
  private lastEmitMs = 0;

  setRenderCallback(cb: RenderCallback) {
    this.renderCb = cb;
    if (!this.stopTicker) this.stopTicker = startTicker(this.tick);
  }

  stop() {
    this.stopTicker?.();
    this.stopTicker = null;
  }

  setState(state: FaceState) {
    faceMachine.set(state);
    const spec = STATE_SPECS[state];
    this.spring.setSlow(!!spec.slowSpring);
    eventBus.emit('state:change', { state });
  }

  getState(): FaceState {
    return faceMachine.get();
  }

  setExpression(name: ExpressionName, intensity: number) {
    if (intensity <= 0) {
      this.overlays.delete(name);
    } else {
      this.overlays.set(name, Math.min(100, intensity));
    }
    eventBus.emit('expression:change', { expression: name, intensity });
  }

  getOverlays(): ExpressionOverlay[] {
    return Array.from(this.overlays.entries()).map(([expression, intensity]) => ({ expression, intensity }));
  }

  setGazeTarget(x: number, y: number) {
    this.gazeX = Math.max(-1, Math.min(1, x));
    this.gazeY = Math.max(-1, Math.min(1, y));
  }

  setListeningLoudness(v: number) {
    this.listeningLoudness = Math.max(0, Math.min(1, v));
  }

  private buildTarget(): { target: FaceParams; activeOsc: ActiveOscillator[] } {
    const state = faceMachine.get();
    const spec = STATE_SPECS[state];

    const layers: { pose: FaceParams; weight: number }[] = [];
    const activeOsc: ActiveOscillator[] = [];

    const baseW = spec.baseIntensity / 100;
    if (baseW > 0) {
      const base = getCompiled(spec.baseExpression);
      layers.push({ pose: base.pose, weight: baseW });
      base.oscillators.forEach((spec) => activeOsc.push({ spec, weight: baseW }));
    }

    for (const [name, intensity] of this.overlays) {
      const w = intensity / 100;
      if (w === 0) continue;
      const c = getCompiled(name);
      layers.push({ pose: c.pose, weight: w });
      c.oscillators.forEach((spec) => activeOsc.push({ spec, weight: w }));
    }

    const target = blendParams(layers.length ? layers : [{ pose: NEUTRAL_PARAMS, weight: 1 }]);

    // Gaze direction overrides pupil
    target.leftEye.pupilX = this.gazeX;
    target.rightEye.pupilX = this.gazeX;
    target.leftEye.pupilY = this.gazeY;
    target.rightEye.pupilY = this.gazeY;

    return { target, activeOsc };
  }

  private tick = (dt: number, nowMs: number) => {
    if (!this.renderCb) return;
    const { target, activeOsc } = this.buildTarget();
    const smoothed = this.spring.step(dt, target);

    const out: FaceParams = {
      leftEye: { ...smoothed.leftEye },
      rightEye: { ...smoothed.rightEye },
      mouth: { ...smoothed.mouth },
      blush: { ...smoothed.blush },
      headTilt: smoothed.headTilt,
      headBobY: smoothed.headBobY,
    };

    applyOscillators(out, activeOsc, nowMs / 1000);
    applyMicroMotion(out, nowMs, STATE_SPECS[faceMachine.get()].microMotionProfile);

    // 注视(gaze)绕过弹簧：直接用目标值覆盖，避免弹簧过渡造成"看人慢半拍"。
    // 眉/嘴/脸等其余参数仍走弹簧，保持表情过渡的质感。
    out.leftEye.pupilX = this.gazeX;
    out.rightEye.pupilX = this.gazeX;
    out.leftEye.pupilY = this.gazeY;
    out.rightEye.pupilY = this.gazeY;

    // Listening loudness drives extra mouth open
    if (this.listeningLoudness > 0) {
      const wobble = (Math.sin(nowMs / 50) * 0.5 + 0.5) * this.listeningLoudness * 0.35;
      out.mouth.openness = Math.min(1, out.mouth.openness + wobble);
    }

    out.leftEye.openness = Math.max(0, out.leftEye.openness);
    out.rightEye.openness = Math.max(0, out.rightEye.openness);

    this.renderCb(out);

    if (nowMs - this.lastEmitMs > 33) {
      this.lastEmitMs = nowMs;
      eventBus.emit('frame', {
        headTilt: out.headTilt,
        headBobY: out.headBobY,
        mouthOpenness: out.mouth.openness,
      });
    }
  };
}

export const faceController = new FaceController();

if (typeof window !== 'undefined') {
  (window as unknown as { __face: FaceController }).__face = faceController;
}
