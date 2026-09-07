// 앱 전역 react-query 클라이언트 — 재시도 정책의 정본.
//
// 컴포넌트 파일에서 분리한 이유는 둘이다: fast-refresh 가 컴포넌트만 export 하는 파일을 요구하고,
// 정책을 테스트가 직접 잡을 수 있어야 한다. 프로덕션 인스턴스를 그대로 검사하지 않으면 재시도
// 동작을 아무도 못 본다 — 테스트들이 각자 `retry: false` 클라이언트를 새로 만들어 쓰기 때문이다.
import { QueryClient } from '@tanstack/react-query';
import { isDeterministicRejection } from '../../shared/api/client';

// react-query 기본값은 브라우저에서 retry 3회다 — 4xx 까지 포함해서. 서버가 정확히 거절한 것은
// 다시 물어도 답이 같으므로 요청 폭풍만 남는다(GitLab #139, S15P21A604-458). 여기서 한 번 끊어
// 두면 같은 부류가 다른 endpoint 에서 재발하지 않는다. 재시도 횟수 자체는 기본값을 유지한다.
const MAX_RETRIES = 3;

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failureCount, error) => !isDeterministicRejection(error) && failureCount < MAX_RETRIES,
    },
  },
});
