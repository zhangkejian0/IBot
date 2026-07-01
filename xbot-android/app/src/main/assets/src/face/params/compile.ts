import type {
  EyeParams, EyeShape, ExpressionRecipe, FaceParams, MouthShape, OscillatorSpec,
} from '../types';
import { cloneNeutral } from './neutral';

function eyeShapeToPartial(shape: EyeShape): Partial<EyeParams> {
  switch (shape) {
    case 'round':       return { openness: 1.0, upperLidCurve: 0.0, lowerLidCurve: 0.0 };
    case 'curvedUp':    return { openness: 0.45, upperLidCurve: -0.75, lowerLidCurve: -0.55 };
    case 'curvedDown':  return { openness: 0.6,  upperLidCurve: 0.5,   lowerLidCurve: 0.2  };
    case 'narrow':      return { openness: 0.7,  upperLidCurve: 0.15,  lowerLidCurve: -0.1 };
    case 'halfLid':     return { openness: 0.5,  upperLidCurve: 0.0,   lowerLidCurve: 0.0, lidTilt: -0.3 };
    case 'closed':      return { openness: 0.0,  upperLidCurve: -0.3,  lowerLidCurve: 0.0 };
  }
}

function applyMouthShape(out: FaceParams, shape: MouthShape, smileAmount: number) {
  switch (shape) {
    case 'neutral':  out.mouth.curve = 0.18;          out.mouth.openness = 0.16; out.mouth.width = 0.95; out.mouth.cornerLift = 0.12; break;
    case 'smile':    out.mouth.curve = smileAmount;   out.mouth.openness = 0.08; out.mouth.width = 1.05; out.mouth.cornerLift = 0.45 * smileAmount; break;
    case 'bigSmile': out.mouth.curve = smileAmount;   out.mouth.openness = 0.32; out.mouth.width = 1.15; out.mouth.cornerLift = 0.6 * smileAmount; break;
    case 'frown':    out.mouth.curve = -smileAmount;  out.mouth.openness = 0.04; out.mouth.width = 0.85; out.mouth.cornerLift = -0.4 * smileAmount; break;
    case 'o':        out.mouth.curve = 0;             out.mouth.openness = 0.55; out.mouth.width = 0.6;  out.mouth.cornerLift = 0; break;
    case 'small':    out.mouth.curve = 0;             out.mouth.openness = 0.03; out.mouth.width = 0.7;  out.mouth.cornerLift = 0; break;
  }
}

export type CompiledExpression = {
  pose: FaceParams;
  oscillators: OscillatorSpec[];
};

export function compileRecipe(recipe: ExpressionRecipe): CompiledExpression {
  const pose = cloneNeutral();
  const eye = eyeShapeToPartial(recipe.eyeShape ?? 'round');
  pose.leftEye = { ...pose.leftEye, ...eye };
  pose.rightEye = { ...pose.rightEye, ...eye };

  if (recipe.eyebrowAsymmetry && recipe.eyebrowAsymmetry !== 0) {
    pose.leftEye.upperLidCurve += 0.35 * recipe.eyebrowAsymmetry;
    pose.leftEye.lidTilt += 0.3 * recipe.eyebrowAsymmetry;
  }

  applyMouthShape(pose, recipe.mouthShape ?? 'neutral', recipe.smileAmount ?? 0);

  pose.blush.intensity = recipe.blush ?? 0;

  if (recipe.headTilt !== undefined) pose.headTilt = recipe.headTilt;

  if (recipe.highlightDim !== undefined) {
    const k = 1 - recipe.highlightDim;
    pose.leftEye.highlightOn = k;
    pose.rightEye.highlightOn = k;
  }

  if (recipe.tearAmount !== undefined) {
    pose.leftEye.tearAmount = recipe.tearAmount;
    pose.rightEye.tearAmount = recipe.tearAmount;
  }

  return { pose, oscillators: recipe.animations ?? [] };
}
