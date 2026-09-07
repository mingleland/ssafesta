// Unity WebGL Host의 조정 가능한 상수 — 값 변경이 여기 한 파일로 끝나게 한다
// 출처: Issue #31, specs/002-world-session/spec.md FR-013·FR-014, GitLab #128 §3 (S15P21A604-426)

// Unity boot watchdog(ms) — createUnityInstance 시작부터 인스턴스가 설 때까지, 진행률 콜백이 이 시간 동안
// 한 번도 오지 않으면 boot 실패로 본다. 진행률이 올 때마다 다시 잰다(느린 회선에서 239MB 다운로드가 60초를
// 넘겨도 진행 중이면 실패가 아니다). 인스턴스가 서면 해제한다 — 로비 체류·입장 게이트 대기에는 타임아웃이
// 없다. 게이트 쪽 상한은 Unity WorldEntryGate 의 30초 강제 개방(FR-014)이 갖고 있고 개방 시 onWorldGateReady
// 를 보내므로 호스트가 따로 재지 않는다. p95 실측치 없음 — demo 환경 후 조정, 잠정값 그대로 사용.
export const UNITY_BOOT_STALL_TIMEOUT_MS = 60_000;

// 월드 로딩 안내가 "축제장을 불러오고 있어요"에서 "잠시만 기다려 주세요"로 넘어가는 시점(ms) —
// 실측 50~84초(#128)의 절반쯤에서 한 번 더 말을 걸어 사용자가 멈춘 것으로 오인하지 않게 한다.
// 이 값은 실패 판정이 아니다. 지나도 안내 문구만 바뀌고 대기는 계속된다(-429).
export const WORLD_PREPARING_LONG_WAIT_MS = 20_000;

/**
 * World Layer 를 mock(정지 화면)으로 그릴지 — Unity 실물을 띄울지의 단일 판정.
 *
 * 원래는 `VITE_USE_MOCK` 하나가 API mock 과 Unity mock 을 함께 갈랐다. 그래서
 * "mock API 로 회원 세션을 만들고 실제 Unity 연동을 확인한다" 가 **구조적으로 불가능**했다 —
 * 로컬에서 회원이 되는 유일한 길이 mock OAuth 인데(실 Google 은 액세스 차단), 그 순간
 * Unity 가 정지 화면으로 바뀌기 때문이다.
 *
 * 그래서 Unity 쪽 판정만 떼어 낸다. `VITE_MOCK_WORLD` 를 명시하면 그 값을 쓰고, 없으면
 * 예전처럼 `VITE_USE_MOCK` 을 따른다 — **미설정 시 기존 동작이 그대로다.**
 *
 * 조합의 의미:
 *   USE_MOCK=false                      실 BE + 실 Unity          통합 실측
 *   USE_MOCK=true                       mock API + 정지 화면       화면·데이터만
 *   USE_MOCK=true, MOCK_WORLD=false     mock API + 실 Unity        회원 세션으로 Unity 연동 확인
 *
 * 세 번째 조합이 성립하는 이유: Unity 는 world-session 을 자기가 서빙된 origin 으로 요청하고
 * 개발용 정적 서버가 토큰 검사 없이 발급한다. mock 세션의 토큰이 실 BE 것이 아니어도 된다.
 *
 * 판정을 여기 한 곳에 둔다 — 예전에는 WorldSurface.select 와 loader.select 가 같은 문장을
 * 각자 적어 두고 있어서, 하나만 고치면 화면과 로더가 엇갈릴 수 있었다.
 */
export const IS_MOCK_WORLD =
  import.meta.env.VITE_MOCK_WORLD !== undefined
    ? import.meta.env.VITE_MOCK_WORLD === 'true'
    : import.meta.env.VITE_USE_MOCK === 'true';
