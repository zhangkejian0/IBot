import type { EyeParams } from '../types';
import { refScleraPath } from '../../preview/referenceEyePaths';
import {
  FACE_EYE_CY, FACE_LEFT_EYE_CX, FACE_RIGHT_EYE_CX,
} from './faceLayout';
import {
  ReferenceBlushHatch, ReferenceEyeGraphic,
  ReferenceEyebrow,
} from './ReferenceEyeGraphic';

type Props = {
  leftEye: EyeParams;
  rightEye: EyeParams;
  blush: number;
  clipPrefix?: string;
};

/** 眼睛纵向拉伸 — 1.35 让 sclera 变成圆角矩形，配合下面虹膜的反向预补偿，瞳孔最终视觉是正圆 */
const EYE_Y_STRETCH = 1.35;

function stretchY(cy: number, sy: number) {
  return `translate(0 ${cy}) scale(1 ${sy}) translate(0 ${-cy})`;
}

/** 眼睛 + 眉毛 + 腮红（无额头刘海） */
export function ReferenceFaceScene({
  leftEye, rightEye, blush, clipPrefix = 'face',
}: Props) {
  const leftClip = `${clipPrefix}-left`;
  const rightClip = `${clipPrefix}-right`;
  const cy = FACE_EYE_CY;

  return (
    <>
      <ReferenceEyebrow cx={FACE_LEFT_EYE_CX} cy={cy} isLeft />
      <ReferenceEyebrow cx={FACE_RIGHT_EYE_CX} cy={cy} isLeft={false} />
      <g transform={stretchY(cy, EYE_Y_STRETCH)}>
        <ReferenceEyeGraphic
          cx={FACE_LEFT_EYE_CX} cy={cy} isLeft
          eye={leftEye} clipId={leftClip}
        />
        <ReferenceEyeGraphic
          cx={FACE_RIGHT_EYE_CX} cy={cy} isLeft={false}
          eye={rightEye} clipId={rightClip}
        />
      </g>
      <ReferenceBlushHatch cx={FACE_LEFT_EYE_CX} cy={cy} isLeft intensity={blush} />
      <ReferenceBlushHatch cx={FACE_RIGHT_EYE_CX} cy={cy} isLeft={false} intensity={blush} />
    </>
  );
}

export function ReferenceFaceSceneClips({ clipPrefix = 'face' }: { clipPrefix?: string }) {
  const cy = FACE_EYE_CY;
  // clipPath geometry stays in unstretched local coords;
  // the parent <g transform="scale(1, EYE_Y_STRETCH)"> in ReferenceFaceScene stretches
  // both the eye render and (effectively) the clip result together.
  return (
    <>
      <clipPath id={`${clipPrefix}-left`}>
        <path d={refScleraPath(FACE_LEFT_EYE_CX, cy, true)} />
      </clipPath>
      <clipPath id={`${clipPrefix}-right`}>
        <path d={refScleraPath(FACE_RIGHT_EYE_CX, cy, false)} />
      </clipPath>
    </>
  );
}
