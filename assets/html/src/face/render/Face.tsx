import { useEffect, useState } from 'react';
import { faceController } from '../runtime/controller';
import { cloneNeutral } from '../params/neutral';
import { buildMouthPath } from './paths';
import {
  FACE_VIEW_W, FACE_VIEW_H, FACE_MOUTH_CX, FACE_MOUTH_CY, faceDisplayViewBox,
} from './faceLayout';
import { ReferenceEyeDefs } from './ReferenceEyeGraphic';
import { ReferenceFaceScene, ReferenceFaceSceneClips } from './ReferenceFaceScene';

const MOUTH_OUTLINE = '#2A1014';
const MOUTH_FILL = '#E04A6E';

export function Face() {
  const [params, setParams] = useState(() => cloneNeutral());

  useEffect(() => {
    faceController.setRenderCallback((p) => setParams(p));
    return () => faceController.stop();
  }, []);

  const mouthD = buildMouthPath(params.mouth, FACE_MOUTH_CX, FACE_MOUTH_CY);

  const cx = FACE_VIEW_W / 2;
  const cy = FACE_VIEW_H / 2;
  const headTransform =
    `translate(${cx} ${cy + params.headBobY}) rotate(${params.headTilt}) translate(${-cx} ${-cy})`;

  return (
    <svg
      viewBox={faceDisplayViewBox()}
      preserveAspectRatio="xMidYMid meet"
      style={{ width: '100%', height: '100%', display: 'block' }}
    >
      <defs>
        <ReferenceEyeDefs />
        <ReferenceFaceSceneClips />
      </defs>
      <g transform={headTransform}>
        <ReferenceFaceScene
          leftEye={params.leftEye}
          rightEye={params.rightEye}
          blush={params.blush.intensity}
        />
        <path
          d={mouthD}
          fill={MOUTH_FILL}
          stroke={MOUTH_OUTLINE}
          strokeWidth={3.5}
          strokeLinejoin="round"
          strokeLinecap="round"
        />
      </g>
    </svg>
  );
}
