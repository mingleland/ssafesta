// S15P21A604-477 — Game Studio 런타임 모드(서버 인증 저작 vs 브라우저 mock)를 결정하는 두
// env 플래그를 실행 시점에 읽는 accessor. S15P21A604-409에서 PlayGamePage.tsx가 이 값들을
// 모듈 최상단 상수로 "import 시점에 한 번만" 읽어서, 테스트가 vi.stubEnv를 beforeEach에서
// 아무리 불러도 반영되지 않고 로컬 .env.local 값에 좌우되는 버그가 있었다. EditGamePage.tsx에도
// 같은 패턴이 있었다(정헌 리뷰, GitLab Issue #125 코멘트). 두 값을 함수로 감싸 호출부(렌더/
// useMemo 시점)마다 새로 읽게 하면 이 클래스의 버그 자체가 구조적으로 생기지 않는다.
//
// publishedAssetResolver(PlayGamePage.tsx)처럼 "참조를 마운트 동안 고정해야 하는" 이유가
// 있는 모듈 상수는 이 accessor의 대상이 아니다 — 그건 env 값이 아니라 객체 참조 안정성
// 문제라 별개다.
export const isServerAuthoringEnabled = (): boolean => (
  import.meta.env.VITE_GAME_STUDIO_API_ENABLED === 'true'
);

export const isBrowserPublicationEnabled = (): boolean => (
  import.meta.env.VITE_USE_MOCK === 'true' && !isServerAuthoringEnabled()
);
