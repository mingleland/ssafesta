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
//
// GitLab #258 — 서버 저작은 이제 이 파일이 소유하는 제품 기본값이다. 이전에는 값이 없으면 OFF 였고,
// 배포 빌드만 `ci/build` 가 `VITE_GAME_STUDIO_API_ENABLED=true` 를 억지로 끼워 넣어 ON 을 만들었다(#245).
// 그러면 Game Studio 제품 기능의 on/off 를 CI 스크립트가 쥐게 되고, 그 스크립트 변경은 shared-ci 로 분류돼
// Front build/deploy 를 직접 유발하지도 않는다. 주지 않으면 꺼지는 기본값이 사고의 원인이었으므로 뒤집는다 —
// 값이 없으면 ON 이고, 끄려면 `false` 를 명시해야 한다(브라우저 mock 으로 오프라인 작업할 때).
export const isServerAuthoringEnabled = (): boolean => (
  import.meta.env.VITE_GAME_STUDIO_API_ENABLED !== 'false'
);

export const isBrowserPublicationEnabled = (): boolean => (
  import.meta.env.VITE_USE_MOCK === 'true' && !isServerAuthoringEnabled()
);
