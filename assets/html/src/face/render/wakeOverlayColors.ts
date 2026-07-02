import type { AmbientSkin } from './ambientSkin';

/** 由氛围皮肤派生的唤醒光效配色 */
export type WakeOverlayColors = {
  glowColor: string;
  accentColor: string;
  textColor: string;
};

export function wakeColorsFromSkin(skin: AmbientSkin): WakeOverlayColors {
  return {
    glowColor: skin.glow,
    accentColor: skin.blush,
    textColor: skin.highlight,
  };
}
