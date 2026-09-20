// mock/real 선택 지점 — facadeApi.select와 같은 상용구 (VITE_USE_MOCK)
import * as realApi from './homepageApi';
import * as mockApi from './homepageApi.mock';

export const homepageApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
