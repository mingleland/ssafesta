// mock/real 선택 지점 — 표준 패턴 (VITE_USE_MOCK). 기본·배포 경로는 real.
import * as realApi from './api';
import * as mockApi from './api.mock';

export const catalogApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
