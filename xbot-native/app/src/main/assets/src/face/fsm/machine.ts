import type { FaceState } from '../types';

type Listener = (state: FaceState) => void;

export class FaceMachine {
  private state: FaceState = 'idle';
  private listeners = new Set<Listener>();

  get(): FaceState {
    return this.state;
  }

  set(next: FaceState) {
    if (next === this.state) return;
    this.state = next;
    this.listeners.forEach((l) => l(next));
  }

  subscribe(l: Listener): () => void {
    this.listeners.add(l);
    return () => this.listeners.delete(l);
  }
}

export const faceMachine = new FaceMachine();
