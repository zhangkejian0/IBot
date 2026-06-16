import type { ExpressionName, FaceState, MicroMotionProfile } from '../types';
import {
  PROFILE_CONFUSED, PROFILE_HAPPY, PROFILE_IDLE, PROFILE_LISTENING,
  PROFILE_SLEEPING, PROFILE_SLEEPY, PROFILE_THINKING, PROFILE_WAKING,
} from '../runtime/microMotion';

export type StateSpec = {
  baseExpression: ExpressionName;
  baseIntensity: number;
  microMotionProfile: MicroMotionProfile;
  slowSpring?: boolean;
};

export const STATE_SPECS: Record<FaceState, StateSpec> = {
  idle:      { baseExpression: 'neutral',   baseIntensity: 100, microMotionProfile: PROFILE_IDLE },
  gazing:    { baseExpression: 'gazing',    baseIntensity: 100, microMotionProfile: PROFILE_IDLE },
  listening: { baseExpression: 'listening', baseIntensity: 100, microMotionProfile: PROFILE_LISTENING },
  thinking:  { baseExpression: 'thinking',  baseIntensity: 100, microMotionProfile: PROFILE_THINKING },
  happy:     { baseExpression: 'happy',     baseIntensity: 100, microMotionProfile: PROFILE_HAPPY },
  confused:  { baseExpression: 'confused',  baseIntensity: 100, microMotionProfile: PROFILE_CONFUSED },
  sleepy:    { baseExpression: 'sleepy',    baseIntensity: 100, microMotionProfile: PROFILE_SLEEPY, slowSpring: true },
  sleeping:  { baseExpression: 'sleeping',  baseIntensity: 100, microMotionProfile: PROFILE_SLEEPING, slowSpring: true },
  waking:    { baseExpression: 'waking',    baseIntensity: 100, microMotionProfile: PROFILE_WAKING, slowSpring: true },
};
