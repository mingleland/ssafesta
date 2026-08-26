// Auth API의 mock/real 선택 지점 — entities/layout/api.select.ts 패턴 복제(T004)
// VITE_USE_MOCK=true면 실 BE 없이 메모리 mock으로 개발한다
import * as realApi from './api';
import * as mockApi from './api.mock';

export const authApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;
