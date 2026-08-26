// Unity 로더의 mock/real 선택 지점 — entities/layout/api.select.ts와 같은 패턴
// VITE_USE_MOCK=true면 실 Unity 빌드 없이 메모리 mock으로 개발한다 (FE/research.md R-09)
import * as realLoader from './loader';
import * as mockLoader from './loader.mock';

export const loadUnityBuild =
  import.meta.env.VITE_USE_MOCK === 'true' ? mockLoader.loadUnityBuild : realLoader.loadUnityBuild;
