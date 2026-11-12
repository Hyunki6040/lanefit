import React from 'react';
import { AbsoluteFill, Audio, Img, OffthreadVideo, Sequence, interpolate, spring, staticFile, useCurrentFrame, useVideoConfig } from 'remotion';
import { loadFont } from '@remotion/fonts';
import avc from './data/avc.json';
import cctv from './data/cctv_boxes.json';
import { GRADES, type Scene, type Spec } from './specs';

const FONT = 'Pretendard';
([['Medium', '500'], ['Bold', '700'], ['ExtraBold', '800']] as const).forEach(([name, weight]) => {
  loadFont({ family: FONT, url: staticFile(`fonts/Pretendard-${name}.otf`), weight }).catch((e) => console.error('font', name, e));
});

const FPS = 30;
const HALF_BEAT = 0.340909; // audio-manifest.json 의 깜빡이 반 박
const LANE_COLORS = ['#9aa3ad', '#4c9aff', '#2fd3b5', '#ffb020', '#ff5c8a'];
const clamp = { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' } as const;
const ease = (t: number) => 1 - Math.pow(1 - t, 3);

const useLocal = () => {
  const frame = useCurrentFrame();
  const { durationInFrames } = useVideoConfig();
  return { frame, t: frame / FPS, p: ease(Math.min(1, frame / durationInFrames)) };
};

const Camera: React.FC<{ scene: Scene; children: React.ReactNode }> = ({ scene, children }) => {
  const { p } = useLocal();
  const [z0, z1] = scene.zoom ?? [1.04, 1.14];
  return (
    <AbsoluteFill style={{ transform: `scale(${z0 + (z1 - z0) * p})`, transformOrigin: scene.origin ?? '50% 50%' }}>
      {children}
    </AbsoluteFill>
  );
};

const cover: React.CSSProperties = { width: '100%', height: '100%', objectFit: 'cover' };

// v_cctv.mp4(496x864)를 위쪽 12%(AI가 만든 가짜 자막)를 잘라 내고 화면에 채우는 변환
const CCTV_K = (1920 / 864) * 1.12;
const CCTV_X = (1080 - 496 * CCTV_K) / 2;
const CCTV_Y = 1920 - 864 * CCTV_K;
const CCTV_LANES = { yTop: 110, yBottom: 864, xTop: [180, 212, 250, 290, 330], xBottom: [-15, 150, 300, 455, 620] };

const CctvOverlay: React.FC<{ rate: number }> = ({ rate }) => {
  const { frame, t } = useLocal();
  const vf = Math.min(cctv.boxes.length - 1, Math.floor(t * rate * cctv.fps));
  const boxes = (cctv.boxes[vf] as number[][]).filter((b) => b[2] * b[3] > 1500);
  const fastest = Math.max(...cctv.lanes.map((l) => l.speed_px_s ?? 0));
  const scan = (frame * 14) % 1920;
  const { xTop, xBottom, yTop, yBottom } = CCTV_LANES;
  return (
    <AbsoluteFill>
      <svg width={1080} height={1920} style={{ position: 'absolute' }}>
        <g transform={`translate(${CCTV_X} ${CCTV_Y}) scale(${CCTV_K})`}>
          {cctv.lanes.map((lane, i) => {
            const on = interpolate(frame, [6 + i * 5, 16 + i * 5], [0, 1], clamp);
            return (
              <polygon key={i} points={`${xTop[i]},${yTop} ${xTop[i + 1]},${yTop} ${xBottom[i + 1]},${yBottom} ${xBottom[i]},${yBottom}`}
                fill={LANE_COLORS[i + 1]} fillOpacity={0.2 * on} stroke={LANE_COLORS[i + 1]} strokeOpacity={0.9 * on} strokeWidth={1.2} />
            );
          })}
          {boxes.map(([x, y, w, h, lane], i) => (
            <rect key={i} x={x} y={y} width={w} height={h} fill="none" stroke={LANE_COLORS[lane]} strokeWidth={2} rx={3} />
          ))}
        </g>
        <rect x={0} y={scan} width={1080} height={3} fill="#7df9ff" opacity={0.35} />
      </svg>
      <div style={{ position: 'absolute', left: 48, top: 250, display: 'flex', flexDirection: 'column', gap: 14 }}>
        {cctv.lanes.map((lane, i) => {
          const on = spring({ frame: frame - 14 - i * 5, fps: FPS, config: { damping: 200 } });
          const rel = (lane.speed_px_s ?? 0) / fastest;
          return (
            <div key={i} style={{ opacity: on, transform: `translateX(${(1 - on) * -40}px)`, display: 'flex', alignItems: 'center', gap: 14, background: 'rgba(8,12,20,0.72)', borderRadius: 14, padding: '10px 18px', width: 420 }}>
              <div style={{ width: 14, height: 14, borderRadius: 7, background: LANE_COLORS[i + 1] }} />
              <div style={{ color: '#fff', fontFamily: FONT, fontWeight: 700, fontSize: 30, width: 96 }}>{lane.lane}차로</div>
              <div style={{ flex: 1, height: 12, borderRadius: 6, background: 'rgba(255,255,255,0.15)' }}>
                <div style={{ width: `${rel * on * 100}%`, height: '100%', borderRadius: 6, background: LANE_COLORS[i + 1] }} />
              </div>
              <div style={{ color: '#fff', fontFamily: FONT, fontWeight: 500, fontSize: 24, width: 74, textAlign: 'right' }}>×{(rel * on).toFixed(2)}</div>
            </div>
          );
        })}
        <div style={{ color: 'rgba(255,255,255,0.85)', fontFamily: FONT, fontWeight: 500, fontSize: 22 }}>차로별 상대 흐름 · scripts/analyze_cctv.py 가 이 컷에서 계산</div>
      </div>
    </AbsoluteFill>
  );
};

// s5.png(1152x2048 → 1080x1920)의 흰 차 위치와 차선 x좌표
const MyCarOverlay: React.FC = () => {
  const { frame } = useLocal();
  const on = spring({ frame: frame - 8, fps: FPS, config: { damping: 14 } });
  const pulse = 1 + 0.04 * Math.sin(frame / 4);
  const tag = (left: number, top: number, text: string, color: string, delay: number) => {
    const o = spring({ frame: frame - delay, fps: FPS, config: { damping: 200 } });
    return (
      <div style={{ position: 'absolute', left, top, opacity: o, transform: `translateY(${(1 - o) * 20}px)`, background: color, color: '#06121f', fontFamily: FONT, fontWeight: 800, fontSize: 30, padding: '8px 16px', borderRadius: 12 }}>{text}</div>
    );
  };
  return (
    <AbsoluteFill>
      <div style={{ position: 'absolute', left: 0, top: 0, width: 384, height: 1920, background: 'rgba(76,154,255,0.16)', opacity: on }} />
      <div style={{ position: 'absolute', left: 384, top: 0, width: 304, height: 1920, background: 'rgba(47,211,181,0.2)', opacity: on }} />
      <div style={{ position: 'absolute', left: 430, top: 700, width: 215, height: 465, border: '5px solid #2fd3b5', borderRadius: 26, opacity: on, transform: `scale(${pulse})` }} />
      {tag(470, 1190, '내 차', '#2fd3b5', 12)}
      {tag(130, 520, '왼쪽 차로', '#4c9aff', 20)}
      {tag(130, 590, '10km에서 40초 차이', '#ffffff', 26)}
    </AbsoluteFill>
  );
};

const BigText: React.FC<{ text: string }> = ({ text }) => {
  const { frame } = useLocal();
  const on = spring({ frame: frame - 6, fps: FPS, config: { damping: 200 } });
  return (
    <AbsoluteFill style={{ justifyContent: 'center', alignItems: 'center', background: 'rgba(5,10,18,0.35)' }}>
      <div style={{ opacity: on, transform: `scale(${0.92 + 0.08 * on})`, color: '#fff', fontFamily: FONT, fontWeight: 800, fontSize: 104, lineHeight: 1.25, textAlign: 'center', whiteSpace: 'pre-line', textShadow: '0 6px 40px rgba(0,0,0,0.6)' }}>{text}</div>
    </AbsoluteFill>
  );
};

const APP_W = 1682;
const APP_H = 1210;
const Crop: React.FC<{ scene: Scene; x: number; y: number; w: number; h: number; width: number; style?: React.CSSProperties }> = ({ scene, x, y, w, h, width, style }) => {
  const k = width / w;
  return (
    <div style={{ position: 'absolute', width, height: h * k, overflow: 'hidden', borderRadius: 28, boxShadow: '0 30px 80px rgba(0,0,0,0.55)', ...style }}>
      <OffthreadVideo muted src={staticFile(scene.src!)} startFrom={Math.round((scene.start ?? 0) * FPS)}
        style={{ position: 'absolute', width: APP_W * k, height: APP_H * k, left: -x * k, top: -y * k }} />
    </div>
  );
};

// 위: 앱 전체 화면. 아래: 카드 문구를 키운 확대 창.
const AppScene: React.FC<{ scene: Scene }> = ({ scene }) => {
  const { frame, p } = useLocal();
  const on = spring({ frame, fps: FPS, config: { damping: 200 } });
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(circle at 50% 30%, #1b2a44 0%, #070b14 70%)' }}>
      <AbsoluteFill style={{ transform: `perspective(1600px) rotateX(${(1 - on) * 14}deg) translateY(${(1 - on) * 60}px) scale(${1 + 0.03 * p})`, opacity: on }}>
        <Crop scene={scene} x={0} y={0} w={APP_W} h={APP_H} width={1000} style={{ left: 40, top: 300 }} />
        <Crop scene={scene} x={40} y={540} w={760} h={250} width={1000} style={{ left: 40, top: 1060, border: '3px solid rgba(255,255,255,0.5)' }} />
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const hour = (hhmm: string) => Number(hhmm.slice(0, 2)) + Number(hhmm.slice(2)) / 60;
const GraphScene: React.FC = () => {
  const { frame } = useLocal();
  const W = 960, H = 780, X0 = 60, Y0 = 520;
  const px = (h: number) => X0 + (h / 24) * W;
  const py = (v: number) => Y0 + H - (v / 120) * H;
  const series = avc.series as unknown as Record<string, [string, number][]>;
  return (
    <AbsoluteFill style={{ background: '#070b14' }}>
      <div style={{ position: 'absolute', left: 60, top: 300, color: '#fff', fontFamily: FONT, fontWeight: 800, fontSize: 64, lineHeight: 1.25 }}>같은 지점, 같은 시각<br />차로마다 다른 속도</div>
      <svg width={1080} height={1920} style={{ position: 'absolute' }}>
        {[0, 40, 80, 120].map((v) => (
          <g key={v}>
            <line x1={X0} x2={X0 + W} y1={py(v)} y2={py(v)} stroke="rgba(255,255,255,0.14)" />
            <text x={X0} y={py(v) - 8} fill="rgba(255,255,255,0.6)" fontFamily={FONT} fontSize={24}>{v} km/h</text>
          </g>
        ))}
        {[0, 6, 12, 18, 24].map((h) => (
          <text key={h} x={px(h)} y={Y0 + H + 40} fill="rgba(255,255,255,0.6)" fontFamily={FONT} fontSize={24} textAnchor="middle">{h}시</text>
        ))}
        {Object.entries(series).map(([lane, pts], i) => {
          const reveal = interpolate(frame, [8 + i * 6, 60 + i * 6], [0, 1], clamp);
          const d = pts.map(([h, v], j) => `${j ? 'L' : 'M'}${px(hour(h)).toFixed(1)},${py(v).toFixed(1)}`).join(' ');
          return <path key={lane} d={d} fill="none" stroke={LANE_COLORS[i]} strokeWidth={lane === '1' ? 3 : 5} strokeLinejoin="round" pathLength={1} strokeDasharray={1} strokeDashoffset={1 - reveal} opacity={lane === '1' ? 0.6 : 1} />;
        })}
      </svg>
      <div style={{ position: 'absolute', left: 60, top: 1375, display: 'flex', gap: 22 }}>
        {Object.keys(series).map((lane, i) => (
          <div key={lane} style={{ display: 'flex', alignItems: 'center', gap: 8, color: '#fff', fontFamily: FONT, fontWeight: 500, fontSize: 26 }}>
            <div style={{ width: 18, height: 18, borderRadius: 9, background: LANE_COLORS[i] }} />{lane}차로{lane === '1' ? '(버스)' : ''}
          </div>
        ))}
      </div>
    </AbsoluteFill>
  );
};

const StatScene: React.FC<{ scene: Scene }> = ({ scene }) => {
  const { frame } = useLocal();
  const s = avc.summary;
  const value = interpolate(frame, [10, 55], [0, s.share_under_60s * 100], clamp);
  const on = spring({ frame: frame - 4, fps: FPS, config: { damping: 200 } });
  return (
    <AbsoluteFill>
      <Camera scene={scene}><Img src={staticFile(scene.src!)} style={cover} /></Camera>
      <AbsoluteFill style={{ background: 'rgba(5,10,18,0.72)', justifyContent: 'center', alignItems: 'center' }}>
        <div style={{ opacity: on, textAlign: 'center', fontFamily: FONT, color: '#fff', marginTop: -160 }}>
          <div style={{ fontWeight: 500, fontSize: 44, opacity: 0.85 }}>옆 차로로 옮겨도 10km에서</div>
          <div style={{ fontWeight: 800, fontSize: 250, lineHeight: 1.1, color: '#2fd3b5', fontVariantNumeric: 'tabular-nums' }}>{value.toFixed(1)}%</div>
          <div style={{ fontWeight: 800, fontSize: 64 }}>는 1분을 못 아꼈다</div>
          <div style={{ fontWeight: 500, fontSize: 30, opacity: 0.75, marginTop: 36 }}>인접 차로 이동 {s.adjacent_moves}건 · {s.route} {s.section} · 15분 집계 하루치</div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

const EndScene: React.FC = () => {
  const { frame } = useLocal();
  const line = (text: string, delay: number, style: React.CSSProperties) => {
    const on = spring({ frame: frame - delay, fps: FPS, config: { damping: 200 } });
    return <div style={{ opacity: on, transform: `translateY(${(1 - on) * 24}px)`, fontFamily: FONT, color: '#fff', ...style }}>{text}</div>;
  };
  return (
    <AbsoluteFill style={{ background: 'radial-gradient(circle at 50% 40%, #16233b 0%, #05080f 75%)', justifyContent: 'center', alignItems: 'center', gap: 26 }}>
      {line('LaneFit · 레인핏', 6, { fontWeight: 800, fontSize: 112 })}
      {line('덜 바꿔도, 늦지 않습니다', 16, { fontWeight: 700, fontSize: 50, color: '#2fd3b5' })}
      {line('현대자동차그룹의 차세대 인포테인먼트 시스템', 28, { fontWeight: 500, fontSize: 36, opacity: 0.85, marginTop: 60 })}
      {line('Pleos Connect 차선 비서', 34, { fontWeight: 700, fontSize: 46 })}
      {line('Pleos SDK로 차량과 연결됩니다', 40, { fontWeight: 500, fontSize: 36, opacity: 0.85 })}
      {line('HMG 차량용 앱 개발 공모전 출품작', 50, { fontWeight: 500, fontSize: 36, opacity: 0.8, marginTop: 40 })}
      {line('#HMG차량용앱개발공모전', 56, { fontWeight: 500, fontSize: 30, opacity: 0.6 })}
      {line('인물·도로 장면은 AI 생성 연출 · 앱 화면은 에뮬레이터 실제 녹화', 62, { fontWeight: 500, fontSize: 24, opacity: 0.55, marginTop: 80 })}
    </AbsoluteFill>
  );
};

const SceneView: React.FC<{ scene: Scene }> = ({ scene }) => {
  const { frame } = useLocal();
  const fade = interpolate(frame, [0, 5], [0, 1], clamp);
  let body: React.ReactNode;
  if (scene.kind === 'app') body = <AppScene scene={scene} />;
  else if (scene.kind === 'graph') body = <GraphScene />;
  else if (scene.kind === 'stat') body = <StatScene scene={scene} />;
  else if (scene.kind === 'end') body = <EndScene />;
  else if (scene.overlay === 'cctv') {
    body = (
      <AbsoluteFill style={{ background: '#000' }}>
        <OffthreadVideo muted src={staticFile(scene.src!)} playbackRate={scene.rate ?? 1}
          style={{ position: 'absolute', left: CCTV_X, top: CCTV_Y, width: 496 * CCTV_K, height: 864 * CCTV_K }} />
        <CctvOverlay rate={scene.rate ?? 1} />
      </AbsoluteFill>
    );
  } else {
    body = (
      <Camera scene={scene}>
        {scene.kind === 'vid'
          ? <OffthreadVideo muted src={staticFile(scene.src!)} playbackRate={scene.rate ?? 1} style={cover} />
          : <Img src={staticFile(scene.src!)} style={cover} />}
        {scene.overlay === 'mycar' ? <MyCarOverlay /> : null}
      </Camera>
    );
  }
  return (
    <AbsoluteFill style={{ opacity: fade, background: '#000' }}>
      {body}
      {scene.overlay === 'bigtext' ? <BigText text={scene.text!} /> : null}
      {scene.grade ? (
        <div style={{ position: 'absolute', left: 40, top: 150, background: 'rgba(0,0,0,0.6)', color: '#fff', fontFamily: FONT, fontWeight: 500, fontSize: 26, padding: '8px 16px', borderRadius: 10 }}>{GRADES[scene.grade]}</div>
      ) : null}
    </AbsoluteFill>
  );
};

const Caption: React.FC<{ text: string }> = ({ text }) => {
  const { frame } = useLocal();
  const on = spring({ frame, fps: FPS, config: { damping: 18, stiffness: 180 } });
  return (
    <div style={{ position: 'absolute', left: 40, right: 40, bottom: 250, textAlign: 'center', whiteSpace: 'pre-line', transform: `translateY(${(1 - on) * 30}px) scale(${0.94 + 0.06 * on})`, opacity: Math.min(1, on * 1.5),
      color: '#fff', fontFamily: FONT, fontWeight: 800, fontSize: 60, lineHeight: 1.3, textShadow: '0 0 18px rgba(0,0,0,0.95), 0 4px 10px rgba(0,0,0,0.9)' }}>{text}</div>
  );
};

const f = (seconds: number) => Math.round(seconds * FPS);
const Sfx: React.FC<{ at: number; name: string; volume?: number }> = ({ at, name, volume = 0.6 }) => (
  <Sequence from={Math.max(0, f(at))} layout="none"><Audio src={staticFile(`sfx/${name}.wav`)} volume={volume} /></Sequence>
);

export const Film: React.FC<{ spec: Spec }> = ({ spec }) => {
  const end = spec.scenes[spec.scenes.length - 1];
  return (
    <AbsoluteFill style={{ background: '#000' }}>
      {spec.scenes.map((scene, i) => (
        <Sequence key={i} from={f(scene.from)} durationInFrames={f(scene.to - scene.from) + (i < spec.scenes.length - 1 ? 5 : 0)}>
          <SceneView scene={scene} />
        </Sequence>
      ))}
      <AbsoluteFill style={{ backgroundImage: `url(${staticFile('footage/grain.png')})`, opacity: 0.05, mixBlendMode: 'overlay' }} />
      {spec.captions.map(([from, to, text], i) => (
        <Sequence key={i} from={f(from)} durationInFrames={f(to - from)} layout="none"><Caption text={text} /></Sequence>
      ))}

      <Audio src={staticFile('music/bed.wav')} volume={(fr) => interpolate(fr, [0, 15, f(spec.seconds) - 45, f(spec.seconds)], [0, 0.2, 0.2, 0], clamp)} />
      {spec.voice.map((v, i) => (
        <Sequence key={i} from={f(v.at)} durationInFrames={f(v.to - v.from)} layout="none">
          <Audio src={staticFile(v.src)} startFrom={f(v.from)} volume={1} />
        </Sequence>
      ))}
      {spec.scenes.map((scene, i) => (
        <React.Fragment key={i}>
          {i > 0 ? <Sfx at={scene.from - 0.19} name="whoosh_short" volume={0.35} /> : null}
          {scene.sfx ? <Sfx at={scene.from + 0.15} name={scene.sfx} /> : null}
          {scene.ticks ? Array.from({ length: Math.floor((scene.to - scene.from) / HALF_BEAT) }, (_, k) => (
            <Sfx key={k} at={scene.from + 0.3 + k * HALF_BEAT} name={k % 2 ? 'tock' : 'tick'} volume={0.8} />
          )) : null}
        </React.Fragment>
      ))}
      <Sequence from={f(end.from)} layout="none"><Audio src={staticFile('music/end_hit.wav')} volume={0.7} /></Sequence>
    </AbsoluteFill>
  );
};
