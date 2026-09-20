// mock/real 선택 지점 — facadeApi.select와 같은 상용구 (VITE_USE_MOCK)
import * as realApi from './leaseApi';
import * as mockApi from './leaseApi.mock';

export const leaseApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
