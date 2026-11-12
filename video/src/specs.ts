// 세 편의 컷 목록. 시간은 모두 초 단위이고, 내레이션 단어 시각(public/voice/*.words.json)에 맞춰 잡았다.
export type Grade = 'app' | 'data' | 'plan' | 'staged' | 'example';

export const GRADES: Record<Grade, string> = {
  app: 'Pleos Connect 에뮬레이터 실행 화면',
  data: '한국도로공사 공개 데이터 · 2026-11-10 · 경부선 1지점',
  plan: '도로 CCTV 영상 AI 분석 · 연출 영상 (AI 생성)',
  staged: '연출 장면 (AI 생성)',
  example: '연출 장면 (AI 생성) · 앱은 예시 도로 값으로 동작',
};

export type Scene = {
  from: number;
  to: number;
  kind: 'img' | 'vid' | 'app' | 'graph' | 'stat' | 'end';
  src?: string;
  grade?: Grade;
  start?: number; // 영상 시작 지점(초)
  rate?: number; // 영상 재생 속도
  zoom?: [number, number]; // 카메라 배율 시작→끝
  origin?: string; // 카메라 기준점
  overlay?: 'cctv' | 'mycar' | 'bigtext';
  text?: string;
  ticks?: boolean; // 깜빡이 소리
  sfx?: string;
};

export type Spec = {
  seconds: number;
  voice: { src: string; at: number; from: number; to: number }[];
  scenes: Scene[];
  captions: [number, number, string][];
};

const STAY = '“옮겨도 40초 차이예요.\n유지하세요.”';
// AI 컷의 가짜 글자·엠블럼을 화면 밖으로 밀어내는 확대 기준점
const DAD = { zoom: [1.5, 1.62] as [number, number], origin: '18% 12%' };

export const A: Spec = {
  seconds: 45,
  voice: [{ src: 'voice/a.mp3', at: 0, from: 0, to: 41.6 }],
  scenes: [
    { from: 0, to: 2.45, kind: 'vid', src: 'hf/v_drone.mp4', grade: 'staged', zoom: [1.02, 1.1] },
    { from: 2.45, to: 5.5, kind: 'vid', src: 'hf/v_dad.mp4', grade: 'staged', ...DAD },
    { from: 5.5, to: 8.0, kind: 'img', src: 'hf/s7.png', grade: 'staged', zoom: [1.04, 1.16] },
    { from: 8.0, to: 10.1, kind: 'img', src: 'hf/s8.png', grade: 'staged', zoom: [1.14, 1.04] },
    { from: 10.1, to: 14.5, kind: 'img', src: 'hf/s9.png', grade: 'staged', zoom: [1.05, 1.3], origin: '50% 45%', ticks: true },
    { from: 14.5, to: 18.7, kind: 'app', src: 'footage/sim.mp4', start: 9.8, grade: 'app', sfx: 'chime_stay' },
    { from: 18.7, to: 21.0, kind: 'img', src: 'hf/s5.png', grade: 'example', zoom: [1.0, 1.25], origin: '50% 48%', overlay: 'mycar' },
    { from: 21.0, to: 24.7, kind: 'graph', grade: 'data' },
    { from: 24.7, to: 29.5, kind: 'app', src: 'footage/car.mp4', start: 9.6, grade: 'app', sfx: 'chime_info' },
    { from: 29.5, to: 33.6, kind: 'app', src: 'footage/sim.mp4', start: 53.3, grade: 'app', sfx: 'pop' },
    { from: 33.6, to: 37.3, kind: 'img', src: 'hf/s12.png', grade: 'staged', zoom: [1.0, 1.12], overlay: 'bigtext', text: '덜 바꿔도,\n늦지 않습니다', sfx: 'sub_hit' },
    { from: 37.3, to: 45, kind: 'end' },
  ],
  captions: [
    [0, 2.3, '금요일 저녁,\n집으로 가는 길'],
    [2.5, 5.3, '옆 차선이 조금 더\n빨라 보입니다'],
    [5.6, 9.6, '뒷자리에는\n방금 잠든 아이가 있습니다'],
    [10.2, 13.9, '깜빡이를 켜는 순간,\n레인핏이 답합니다'],
    [14.6, 18.1, STAY],
    [18.8, 24.1, '차로마다 다른 속도를 읽어\n앞으로 10km를 비교합니다'],
    [24.8, 29.2, '정말 옮길 만할 때만\n얼마나 빠른지 알려 주고'],
    [29.6, 33.4, '가족이 타면\n더 차분한 기준으로 바꿉니다'],
  ],
};

export const B: Spec = {
  seconds: 48,
  // b.mp3는 "부드러웠는지까지"(27.78초)까지만 쓰고, 뒤 세 문장은 같은 목소리로 다시 녹음한 b2.mp3를 잇는다.
  voice: [
    { src: 'voice/b.mp3', at: 0, from: 0, to: 28.0 },
    { src: 'voice/b2.mp3', at: 28.4, from: 0, to: 18.96 },
  ],
  scenes: [
    { from: 0, to: 1.6, kind: 'img', src: 'hf/s1.png', grade: 'staged', zoom: [1.0, 2.2], sfx: 'riser' },
    { from: 1.6, to: 4.0, kind: 'vid', src: 'hf/v_drone.mp4', grade: 'staged', zoom: [1.05, 1.15] },
    { from: 4.0, to: 6.7, kind: 'img', src: 'hf/s13.png', grade: 'staged', zoom: [1.18, 1.04] },
    { from: 6.7, to: 9.8, kind: 'graph', grade: 'data' },
    { from: 9.8, to: 15.2, kind: 'stat', src: 'hf/s4.png', grade: 'data', sfx: 'sub_hit' },
    { from: 15.2, to: 19.9, kind: 'img', src: 'hf/s9.png', grade: 'staged', zoom: [1.05, 1.3], origin: '50% 45%', ticks: true },
    { from: 19.9, to: 23.4, kind: 'app', src: 'footage/sim.mp4', start: 9.8, grade: 'app', sfx: 'chime_stay' },
    { from: 23.4, to: 25.1, kind: 'app', src: 'footage/car.mp4', start: 10, grade: 'app', sfx: 'chime_info' },
    { from: 25.1, to: 28.2, kind: 'app', src: 'footage/car.mp4', start: 22.3, grade: 'app', sfx: 'count' },
    { from: 28.2, to: 33.1, kind: 'vid', src: 'hf/v_cctv.mp4', rate: 0.55, grade: 'plan', overlay: 'cctv', sfx: 'whoosh_long' },
    { from: 33.1, to: 36.8, kind: 'vid', src: 'hf/v_dad.mp4', grade: 'staged', ...DAD },
    { from: 36.8, to: 48, kind: 'end' },
  ],
  captions: [
    [0, 3.4, '지금 이 도로에서\n가장 빠른 차선은 어디일까요?'],
    [4.1, 6.2, '도로는 이미 알고 있습니다'],
    [6.8, 9.3, '실제 고속도로 자료를\n분석했습니다'],
    [9.9, 14.7, '옆 차로로 옮겨도 열 번 중 여덟 번은\n1분도 아끼지 못했습니다'],
    [15.3, 19.4, '그래서 레인핏은 깜빡이를 켜는 순간\n먼저 계산합니다'],
    [20.0, 23.1, STAY],
    [23.5, 25.0, '빠를 때는 빠르다고'],
    [25.1, 27.9, '바꾼 뒤에는\n얼마나 부드러웠는지까지'],
    [28.4, 32.9, '도로 CCTV 영상은 AI가 읽어\n차로별 흐름을 확인합니다'],
    [33.3, 36.4, '판단은 돕고,\n운전대는 사람이 잡습니다'],
  ],
};

// 30초 컷: 위성 → 드론 → 관제 → 수치 → CCTV 분석 → 내 차 → 손 → 앱
export const C: Spec = {
  seconds: 30,
  voice: [
    { src: 'voice/b.mp3', at: 0, from: 0, to: 6.2 },
    { src: 'voice/b.mp3', at: 6.4, from: 9.85, to: 14.75 },
    { src: 'voice/b.mp3', at: 19.5, from: 19.98, to: 23.2 },
    { src: 'voice/b.mp3', at: 26.2, from: 39.0, to: 42.1 },
  ],
  scenes: [
    { from: 0, to: 1.5, kind: 'img', src: 'hf/s1.png', grade: 'staged', zoom: [1.0, 2.2], sfx: 'riser' },
    { from: 1.5, to: 3.9, kind: 'vid', src: 'hf/v_drone.mp4', grade: 'staged', zoom: [1.05, 1.15] },
    { from: 3.9, to: 6.4, kind: 'img', src: 'hf/s13.png', grade: 'staged', zoom: [1.18, 1.04] },
    { from: 6.4, to: 11.4, kind: 'stat', src: 'hf/s4.png', grade: 'data', sfx: 'sub_hit' },
    { from: 11.4, to: 14.9, kind: 'vid', src: 'hf/v_cctv.mp4', rate: 1, grade: 'plan', overlay: 'cctv', sfx: 'whoosh_long' },
    { from: 14.9, to: 17.1, kind: 'img', src: 'hf/s5.png', grade: 'example', zoom: [1.0, 1.25], origin: '50% 48%', overlay: 'mycar' },
    { from: 17.1, to: 19.4, kind: 'img', src: 'hf/s9.png', grade: 'staged', zoom: [1.05, 1.25], origin: '50% 45%', ticks: true },
    { from: 19.4, to: 23.2, kind: 'app', src: 'footage/sim.mp4', start: 9.8, grade: 'app', sfx: 'chime_stay' },
    { from: 23.2, to: 26.0, kind: 'app', src: 'footage/car.mp4', start: 10, grade: 'app', sfx: 'chime_info' },
    { from: 26.0, to: 30, kind: 'end' },
  ],
  captions: [
    [0, 3.4, '지금 이 도로에서\n가장 빠른 차선은 어디일까요?'],
    [4.1, 6.2, '도로는 이미 알고 있습니다'],
    [6.5, 11.2, '옆 차로로 옮겨도 열 번 중 여덟 번은\n1분도 아끼지 못했습니다'],
    [11.6, 14.7, '도로 CCTV 영상은 AI가 읽어\n차로별 흐름을 확인합니다'],
    [15.0, 17.0, '내 차선과 옆 차선을 비교하고'],
    [17.2, 19.3, '깜빡이를 켜는 순간'],
    [19.6, 23.0, STAY],
    [23.3, 25.9, '빠를 때만, 빠르다고'],
  ],
};
