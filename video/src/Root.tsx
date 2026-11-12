import React from 'react';
import { Composition } from 'remotion';
import { Film } from './Film';
import { A, B, C, type Spec } from './specs';

const FILMS: [string, Spec][] = [['LaneFitA', A], ['LaneFitB', B], ['LaneFitC', C]];

export const Root: React.FC = () => (
  <>
    {FILMS.map(([id, spec]) => (
      <Composition key={id} id={id} component={Film} defaultProps={{ spec }} width={1080} height={1920} fps={30} durationInFrames={spec.seconds * 30} />
    ))}
  </>
);
