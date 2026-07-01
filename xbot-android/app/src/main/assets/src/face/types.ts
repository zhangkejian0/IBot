export type EyeParams = {
  openness: number;
  upperLidCurve: number;
  lowerLidCurve: number;
  lidTilt: number;
  pupilX: number;
  pupilY: number;
  pupilScale: number;
  highlightOn: number;
  tearAmount: number;
};

export type MouthParams = {
  curve: number;
  openness: number;
  width: number;
  cornerLift: number;
};

export type BlushParams = {
  intensity: number;
  size: number;
};

export type FaceParams = {
  leftEye: EyeParams;
  rightEye: EyeParams;
  mouth: MouthParams;
  blush: BlushParams;
  headTilt: number;
  headBobY: number;
};

export type DeepPartial<T> = { [K in keyof T]?: T[K] extends object ? DeepPartial<T[K]> : T[K] };

export type EyeShape = 'round' | 'curvedUp' | 'curvedDown' | 'narrow' | 'halfLid' | 'closed';
export type MouthShape = 'neutral' | 'smile' | 'bigSmile' | 'frown' | 'o' | 'small';

export type OscillatorKind = 'sine' | 'pulse' | 'bounce' | 'wiggle' | 'sway' | 'breathDecay';

export type OscillatorTarget =
  | 'leftEye.openness' | 'leftEye.pupilX' | 'leftEye.pupilY' | 'leftEye.pupilScale' | 'leftEye.upperLidCurve' | 'leftEye.tearAmount'
  | 'rightEye.openness' | 'rightEye.pupilX' | 'rightEye.pupilY' | 'rightEye.pupilScale' | 'rightEye.upperLidCurve' | 'rightEye.tearAmount'
  | 'mouth.curve' | 'mouth.openness' | 'mouth.cornerLift' | 'mouth.width'
  | 'blush.intensity'
  | 'headTilt' | 'headBobY'
  | 'pupilScale';

export type OscillatorSpec = {
  kind: OscillatorKind;
  target: OscillatorTarget;
  amp: number;
  freq: number;
  phase?: number;
};

export type ExpressionRecipe = {
  eyeShape?: EyeShape;
  mouthShape?: MouthShape;
  smileAmount?: number;
  blush?: number;
  headTilt?: number;
  headSway?: number;
  eyebrowAsymmetry?: number;
  highlightDim?: number;
  tearAmount?: number;
  animations?: OscillatorSpec[];
};

export type ExpressionName =
  | 'neutral' | 'happy' | 'confused' | 'sleepy' | 'sleeping' | 'thinking'
  | 'listening' | 'gazing' | 'waking';

export type FaceState =
  | 'idle' | 'gazing' | 'listening' | 'thinking'
  | 'happy' | 'confused' | 'angry' | 'sleepy' | 'sleeping' | 'waking';

export type MicroMotionProfile = {
  blinkPeriodMs: number;
  blinkDurationMs: number;
  breathAmp: number;
  saccadeAmp: number;
};

export type ExpressionOverlay = {
  expression: ExpressionName;
  intensity: number;
};
