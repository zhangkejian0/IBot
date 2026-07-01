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
import {
  setAmbientExpression,
  getAmbientExpression,
  type AmbientExpressionId,
} from '../render/ambientExpression';

/**
 * FaceState → 氛围表情预设。让 setState 自动驱动渲染器的眼形/嘴形/光晕/道具，
 * Flutter 只需调 setState 即可，无需额外调 setAmbientExpression。
 *
 * 设计取舍：
 * - listening（聆听用户说话）用 doubt（问号），表现"在听/疑问"的专注感；
 * - thinking/happy/sleeping 保持 calm（微笑），避免等回复/说话/睡眠时表情
 *   过于激动；
 * - confused（人脸检测到的惊讶/厌恶/恐惧）也用 doubt，与 listening 同预设
 *   （场景不同不会混淆，用户已确认接受）。
 */
const STATE_TO_AMBIENT: Record<FaceState, AmbientExpressionId> = {
  idle: 'idle',
  gazing: 'idle',
  listening: 'doubt',
  thinking: 'calm',
  happy: 'calm',
  confused: 'doubt',
  angry: 'angry',
  sleepy: 'squint',
  sleeping: 'calm',
  waking: 'squint',
};

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
    setAmbientExpression(STATE_TO_AMBIENT[state]);
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

  /**
   * 氛围表情预设（驱动 AmbientFace/LineFace/KawaiiFace 的眼形/嘴形/光晕色/道具）。
   * setState 只改底层 FaceParams（眨眼/呼吸/注视/嘴部 curve），不影响渲染器
   * 实际读取的「表情语义」—— 那一层由 ambientExpression store 控制。
   * 通过 window.__face 暴露给 Flutter，由宿主自行决定调哪个预设。
   */
  getAmbientExpression(): AmbientExpressionId {
    return getAmbientExpression();
  }
  setAmbientExpression(id: AmbientExpressionId) {
    setAmbientExpression(id);
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
