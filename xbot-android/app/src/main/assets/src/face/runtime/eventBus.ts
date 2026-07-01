import type { FaceParams, ExpressionName, FaceState } from '../types';

export type FaceEvents = {
  'expression:change': { expression: ExpressionName; intensity: number };
  'state:change': { state: FaceState };
  'frame': { headTilt: number; headBobY: number; mouthOpenness: number };
};

type Handler<T> = (payload: T) => void;

class EventBus {
  private handlers: Record<string, Set<Handler<unknown>>> = {};

  on<K extends keyof FaceEvents>(event: K, h: Handler<FaceEvents[K]>): () => void {
    const set = (this.handlers[event] ??= new Set());
    set.add(h as Handler<unknown>);
    return () => set.delete(h as Handler<unknown>);
  }

  emit<K extends keyof FaceEvents>(event: K, payload: FaceEvents[K]) {
    const set = this.handlers[event];
    if (!set) return;
    set.forEach((h) => (h as Handler<FaceEvents[K]>)(payload));
  }
}

export const eventBus = new EventBus();
