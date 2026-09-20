// Unity 로더의 mock/real 선택 지점 — entities/layout/api.select.ts와 같은 패턴
// 판정은 shared/config/unity 의 IS_MOCK_WORLD 가 소유한다 (S15P21A604-466) — 예전에는 여기서
// VITE_USE_MOCK 을 직접 읽어 API mock 과 Unity mock 을 뗄 수 없었다.
import * as realLoader from './loader';
import * as mockLoader from './loader.mock';
import { IS_MOCK_WORLD } from '../../shared/config/unity';

export const loadUnityBuild = IS_MOCK_WORLD ? mockLoader.loadUnityBuild : realLoader.loadUnityBuild;
