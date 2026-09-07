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
 * 토큰은 개발용 표식이다. mock API 는 토큰을 보지 않고, 실 BE 는 어차피 거부한다 —
 * 이 진입은 mock 데이터와 함께 쓰는 것이 전제다.
 */
export function enterAsDeveloper(): void {
  const expiresAt = new Date(Date.now() + DEV_SESSION_HOURS * 60 * 60 * 1000).toISOString();
  setMemberSession('dev-entry', expiresAt);
}
