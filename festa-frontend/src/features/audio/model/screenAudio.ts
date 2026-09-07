// Landing·Login 화면의 BGM (S15P21A604-463).
//
// **인게임 BGM 이 아니다.** World 진입 지점은 11F 이고, Unity WorldBgm.ZoneWeights 기준 11층
// 방(z < -118)은 Algorithmic Morning 100% / Midnight Circus 0% 다. 두 곡은 같은 곡의 변주가
// 아니므로 곡 연속성이나 긴 crossfade 를 전제하지 않는다 — 화면 공간에서 11F 공간으로
// **오디오 소유권을 넘기는 전환**으로 다룬다.
//
// 라우트 안에 <audio> 를 두지 않는다. / → /login → /app/world 는 서로 다른 route element 라
// 화면이 바뀌는 순간 음악이 끊긴다. 이 모듈이 element 를 소유하고 라우터 밖에서 산다 —
// Overlay Bus(shared/types/overlay.ts)·gameClientUi 와 같은 module-level store 관례를 따른다.
import { useSyncExternalStore } from 'react';
import trackUrl from '../../../assets/festa/audio/midnight-circus-soft-login.mp3';

/**
 * mute 선호 저장 키. 지금은 화면 BGM 하나만 쓰지만 이름은 **설정 항목**으로 지어 둔다 —
 * ESC Client Settings 의 Music 설정이 생기면 이 값을 그대로 승계해 같은 키를 읽고 쓴다.
 * 화면 전용 키(`festa.landing.mute` 류)로 지으면 그때 마이그레이션이 필요해진다.
 */
export const MUSIC_MUTED_STORAGE_KEY = 'festa.settings.music.muted';

/** 이관 fade 길이(ms). 로딩 씬 진입과 함께 이 시간 동안 줄어 완전히 멈춘다. */
export const SCREEN_AUDIO_FADE_MS = 1_500;

const FADE_STEP_MS = 50;

/**
 * 화면 BGM 볼륨. 임의값이 아니라 **이관 도착점인 11F 방의 실효 출력에 맞춘 값**이다.
 *
 * 실측(게이팅 RMS, 상위 70% 블록 평균):
 *   BGM_AlgorithmicMorning  -16.83 dBFS   11F 방에서 씬 설정 ×0.1  → -36.8 dBFS
 *   BGM_MidnightCircus      -17.24 dBFS   축제 부지에서    ×0.55 → -22.4 dBFS
 *   이 트랙                  -17.73 dBFS
 * 세 파일의 자체 라우드니스는 사실상 같고 차이는 전부 배수에서 온다. main.unity 의
 * `_morningVolume` 이 코드 기본값(0.5)이 아니라 **0.1** 로 오버라이드돼 있어 11F 가 매우
 * 조용하다 — 0.45 로 두면 도착점 대비 +12.1 dB(약 4배)라 전환에서 소리가 뚝 떨어진다.
 *
 * Unity 는 AudioMixer 도 AudioListener.volume 조작도 없고 전역 m_Volume 이 1 이라 다른
 * 감쇠 경로가 없다. 문제는 Unity 자신의 두 구역이 14.4 dB 나 벌어져 있다는 것이다 —
 * 11F 에 정확히 맞추면(0.11) 화면에서 들리지 않을 만큼 작고, 축제 부지에 맞추면(0.58)
 * 도착 순간이 다시 시끄럽다. 두 구역의 중점 -29.6 dBFS 를 목표로 잡는다:
 *   10^((-29.6 + 17.73)/20) = 0.255
 * 도착점 대비 +7.2 dB 로, 처음의 +12.1 dB 에서 절반 이하로 줄어든다. 그 남은 낙차는
 * 로딩 진입과 함께 시작되는 fade 가 흡수한다 — 두 소리가 맞붙는 순간이 없기 때문이다.
 */
export const TRACK_VOLUME = 0.25;

export interface ScreenAudioState {
  muted: boolean;
  playing: boolean;
  /** 자동재생이 거부돼 사용자 제스처를 기다리는 중 — 실패를 삼키지 않기 위한 표면이다 */
  pendingGesture: boolean;
  /** onWorldLoadStart 를 받은 시점부터 이관이 끝날 때까지 */
  transitionPending: boolean;
  /**
   * 오디오 소유권을 Unity 에 넘긴 뒤. **되돌아가지 않는 상태다** — 화면으로 실제로 복귀할
   * 때만 풀린다. 이게 없으면 fade 가 끝나 transitionPending 이 내려간 순간 모든 가드가
   * 사라져, 월드 안의 클릭 한 번이 화면 음악을 되살린다(엘리베이터에서 잠깐 들리던 버그).
   * 컨트롤러의 자동재생 재시도 리스너는 라우터 밖에 살아 정확히 그 경로로 들어온다.
   */
  handedOff: boolean;
}

const initialState: ScreenAudioState = {
  muted: false,
  playing: false,
  pendingGesture: false,
  transitionPending: false,
  handedOff: false,
};

let state: ScreenAudioState = { ...initialState, muted: readMutedPreference() };
let element: HTMLAudioElement | null = null;
let fadeTimer: ReturnType<typeof setInterval> | null = null;

const listeners = new Set<() => void>();

function emit(): void {
  for (const listener of listeners) listener();
}

function setState(patch: Partial<ScreenAudioState>): void {
  const next = { ...state, ...patch };
  if (
    next.muted === state.muted &&
    next.playing === state.playing &&
    next.pendingGesture === state.pendingGesture &&
    next.transitionPending === state.transitionPending &&
    next.handedOff === state.handedOff
  ) {
    return;
  }
  state = next;
  emit();
}

function readMutedPreference(): boolean {
  try {
    return window.localStorage.getItem(MUSIC_MUTED_STORAGE_KEY) === 'true';
  } catch {
    // 프라이빗 모드·저장소 차단에서는 저장소가 던진다. 선호가 없는 것으로 보고 기본값(켜짐)으로 간다.
    return false;
  }
}

function writeMutedPreference(muted: boolean): void {
  try {
    window.localStorage.setItem(MUSIC_MUTED_STORAGE_KEY, String(muted));
  } catch {
    // 저장에 실패해도 이번 세션의 음소거는 그대로 동작한다 — 다음 방문에 기억되지 않을 뿐이다.
  }
}

// element 는 첫 재생 시도까지 만들지 않는다 — 그때까지 4.5MB 를 받을 이유가 없고, Landing 의
// 첫 페인트·Unity warm-up 과 회선을 다투지 않는다. preload 세부(auto/metadata/none)는 실브라우저
// 경합 실측 뒤 조정한다.
function ensureElement(): HTMLAudioElement | null {
  if (element !== null) return element;
  if (typeof Audio !== 'function') return null; // 테스트 node 환경 등
  const audio = new Audio(trackUrl);
  audio.loop = true;
  audio.preload = 'auto';
  audio.volume = TRACK_VOLUME;
  element = audio;
  return audio;
}

function clearFade(): void {
  if (fadeTimer === null) return;
  clearInterval(fadeTimer);
  fadeTimer = null;
}

/**
 * 사용자 제스처 지점에서 부른다 — Landing 첫 클릭, Login 진입, 그리고 자동재생이 거부된 뒤의
 * 첫 상호작용. 이미 울리고 있거나 음소거·이관 중이면 아무 것도 하지 않는다.
 */
export function unlockAndPlay(): void {
  if (state.muted || state.transitionPending || state.handedOff || state.playing) return;
  const audio = ensureElement();
  if (audio === null) return;
  clearFade();
  audio.volume = TRACK_VOLUME;
  const started = audio.play();
  if (started === undefined) {
    // play() 가 Promise 를 돌려주지 않는 구형 구현
    setState({ playing: true, pendingGesture: false });
    return;
  }
  void started.then(
    () => setState({ playing: true, pendingGesture: false }),
    (err: unknown) => {
      // 자동재생 거부를 조용히 삼키지 않는다 — 대기 상태로 드러내고 다음 제스처에서 다시 시도한다(T-24 정신).
      console.warn('[screen-audio] 재생이 거부됐다 — 다음 사용자 상호작용에서 다시 시도한다', err);
      setState({ playing: false, pendingGesture: true });
    },
  );
}

export function setMuted(muted: boolean): void {
  writeMutedPreference(muted);
  setState({ muted });
  if (muted) {
    clearFade();
    element?.pause();
    setState({ playing: false, pendingGesture: false });
    return;
  }
  // 음소거 해제 자체가 사용자 제스처다 — 그 자리에서 다시 켠다.
  unlockAndPlay();
}

function fadeOutAndStop(): void {
  const audio = element;
  if (audio === null || !state.playing) {
    setState({ playing: false, pendingGesture: false, transitionPending: false, handedOff: true });
    return;
  }
  clearFade();
  const steps = Math.max(1, Math.round(SCREEN_AUDIO_FADE_MS / FADE_STEP_MS));
  const decrement = audio.volume / steps;
  fadeTimer = setInterval(() => {
    const next = audio.volume - decrement;
    if (next > 0) {
      audio.volume = next;
      return;
    }
    clearFade();
    audio.pause();
    audio.currentTime = 0;
    audio.volume = TRACK_VOLUME;
    setState({ playing: false, pendingGesture: false, transitionPending: false, handedOff: true });
  }, FADE_STEP_MS);
}

/**
 * onWorldLoadStart — 사용자가 월드 입장을 눌러 엘리베이터 로딩 씬으로 들어가는 순간.
 * 여기서 바로 줄인다. 로딩 씬은 그 자체가 하나의 장면이라 화면 음악이 그 위로 계속
 * 울리면 아직 로그인 화면에 있는 것처럼 들린다 — 화면을 떠나는 시점에 음악도 떠난다.
 */
export function beginWorldTransition(): void {
  setState({ transitionPending: true });
  fadeOutAndStop();
}

/**
 * onWorldGateReady — 안전망이다. 정상 경로에서는 loadStart 에서 이미 멈춰 no-op 이고,
 * Unity 가 loadStart 를 보내지 않는 빌드에서만 여기서 처음 이관이 일어난다.
 */
export function handOffToWorld(): void {
  fadeOutAndStop();
}

/**
 * 화면(Landing·Login)에 들어왔다 — 이관 상태를 풀고 음악을 켠다. ScreenControls 가
 * 마운트될 때 부른다. 그 컨트롤이 보이는 화면이 곧 화면 음악이 있어야 하는 화면이라
 * 판정을 한 곳에 둘 수 있다.
 *
 * 화면마다 각자 켜게 두면 빠뜨린다 — 실제로 Landing 만 이 호출이 없어서, 진입해도
 * 음악이 시작되지 않는데 컨트롤은 음소거가 아니니 "켜짐" 으로 표시하는 상태가 됐다.
 * 자동재생이 거부되면 pendingGesture 로 남아 컨트롤이 꺼진 것으로 그리고, 첫 상호작용에서
 * 다시 시도된다.
 */
export function enterScreen(): void {
  setState({ handedOff: false, transitionPending: false });
  unlockAndPlay();
}

export function subscribeScreenAudio(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getScreenAudioSnapshot(): ScreenAudioState {
  return state;
}

export function useScreenAudio(): ScreenAudioState {
  return useSyncExternalStore(subscribeScreenAudio, getScreenAudioSnapshot, getScreenAudioSnapshot);
}

// 테스트 전용
export function __resetScreenAudioForTests(): void {
  clearFade();
  element = null;
  // 모듈이 처음 로드될 때와 같은 자리에서 출발한다 — 저장된 음소거 선호도 그때 읽힌다
  state = { ...initialState, muted: readMutedPreference() };
  listeners.clear();
}
