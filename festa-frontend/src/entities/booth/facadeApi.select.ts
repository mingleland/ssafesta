// Facade API의 mock/real 선택 지점 — FacadePanel이 직접 분기하던 것을 여기 하나로 모은다.
// VITE_USE_MOCK=true면 실 BE 없이 메모리 mock으로 개발한다 (FE/research.md R-09)
import * as realApi from './facadeApi';
import * as mockApi from './facadeApi.mock';

export const facadeApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
