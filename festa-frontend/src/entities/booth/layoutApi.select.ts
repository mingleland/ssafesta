// layoutApi 의 mock/real 선택 지점 — facadeApi.select 와 같은 규칙 (VITE_USE_MOCK=true → 메모리 mock)
import * as realApi from './layoutApi';
import * as mockApi from './layoutApi.mock';

export const layoutApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
