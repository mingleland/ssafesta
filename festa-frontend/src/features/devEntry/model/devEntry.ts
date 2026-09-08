// 개발자 전용 진입 (S15P21A604-467).
//
// 왜 별도 개념인가. 로컬에서 회원이 되는 유일한 길이 mock OAuth 라(실 Google 은 액세스 차단)
// 개발 진입이 **제품 버튼을 빌려 쓰고** 있었다. 그러면 두 가지가 무너진다 — 제품 동선이
// 개발 편의를 겸하게 되고, 지금 보는 화면이 실제 로그인인지 개발 진입인지 구분되지 않는다.
//
// 그래서 진입을 이름 있는 플래그로 뗀다. 역할이 셋으로 갈린다:
//   VITE_USE_MOCK    mock 데이터 소스
//   VITE_MOCK_WORLD  Unity World mock 여부 (S15P21A604-466)
//   VITE_DEV_ENTRY   개발자 전용 진입 여부 (이 파일)
import * as authApi from '../../../entities/auth/api';
import { setMemberSession } from '../../auth/model/session';

/**
 * 개발자 진입을 켤지. **`import.meta.env.DEV` 와 AND 로 묶는다** —
 * `IS_DEV_INTERACTION_BAR`(gameClientUi.ts:19)와 같은 관례이고, 프로덕션 빌드에서는
 * 상수 `false` 가 되어 이 갈래가 통째로 번들에서 사라진다(Vite 상수 치환 + tree-shaking).
 * 플래그만으로는 켜지지 않는다는 것이 요점이다.
 */
export const IS_DEV_ENTRY = import.meta.env.DEV && import.meta.env.VITE_DEV_ENTRY === 'true';

/** 개발 진입 세션의 수명 — 한 번 켜면 그 세션 동안 다시 묻지 않을 만큼만 */
const DEV_SESSION_HOURS = 12;

/**
 * 회원 세션을 그 자리에서 만든다. **회원 고정이다** — 게스트 확인은 제품 경로
 * ("게스트로 둘러보기")가 이미 있고, 개발 진입까지 종류를 고르게 하면 두 경로가 겹친다.
 *
 * OAuth 왕복도 닉네임 등록도 거치지 않는다. 목적이 "회원 전용 화면을 열어 보는 것" 이라
 * 그 앞의 절차는 확인 대상이 아니다 — 그 절차 자체를 보려면 제품 버튼을 쓰면 된다.
 *
 * 토큰을 어디서 얻는지가 mock 이냐 실 BE 냐로 갈린다.
 *
 *   mock    `'dev-entry'` 표식. mock API 는 토큰을 보지 않는다
 *   실 BE   `POST /auth/refresh` — `refresh_token` 쿠키로 **진짜 회원 세션**을 받는다
 *
 * 예전에는 실 BE 에서도 표식을 그대로 넣었다. 그러면 세션은 만들어지는데 첫 요청이 401 이 되고,
 * 401 인터셉트가 세션을 지워 **로그인 화면으로 조용히 되돌아간다** — 무엇이 틀렸는지 아무 데도
 * 남지 않는 실패였다. 실 BE 에서는 받아 낼 수 있을 때만 들어가고, 못 받으면 던져서 드러낸다
 * (헌법: 실패를 조용히 기본값으로 되돌리지 않는다).
 */
export async function enterAsDeveloper(): Promise<void> {
  // 로컬 실 Spring 우회 — OAuth 왕복을 이 환경 네트워크가 완주할 수 없어(§상단 주석) refresh_token
  // 쿠키를 발급받을 정상 경로가 없다. VITE_DEV_LOCAL_ACCESS_TOKEN에 로컬 JWT_SECRET으로 미리 만든
  // access token을 넣어 두면 그걸 그대로 세션에 심는다 — 로컬 전용, 값이 없으면 이 분기는 안 탄다.
  // MODE !== 'test' 가드: vitest도 .env를 읽어(vite 공용 설정) 이 값이 남아 있으면 authApi.refresh
  // mock을 검증하는 기존 테스트를 조용히 우회해 버린다 — 테스트 모드에서는 이 분기 자체를 끈다.
  const localToken = import.meta.env.VITE_DEV_LOCAL_ACCESS_TOKEN;
  if (import.meta.env.MODE !== 'test' && typeof localToken === 'string' && localToken !== '') {
    const expiresAt = new Date(Date.now() + DEV_SESSION_HOURS * 60 * 60 * 1000).toISOString();
    setMemberSession(localToken, expiresAt);
    return;
  }
  if (import.meta.env.VITE_USE_MOCK === 'true') {
    const expiresAt = new Date(Date.now() + DEV_SESSION_HOURS * 60 * 60 * 1000).toISOString();
    setMemberSession('dev-entry', expiresAt);
    return;
  }
  const session = await authApi.refresh();
  setMemberSession(session.accessToken, session.expiresAt);
}
