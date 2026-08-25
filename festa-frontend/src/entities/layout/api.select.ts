// Layout API의 mock/real 선택 지점 — 소비자 3곳(StudioPage·useLayoutMutations)이 각자 분기하던 것을 여기 하나로 모은다.
// VITE_USE_MOCK=true면 실 BE 없이 메모리 mock으로 개발한다 (FE/research.md R-09)
import * as realApi from './api';
import * as mockApi from './api.mock';

export const layoutApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
