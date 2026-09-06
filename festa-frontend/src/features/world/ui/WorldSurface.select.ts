// World Layer 구현 선택 — 기존 *.select.ts 관행과 같은 seam (S15P21A604-406).
// 판정은 shared/config/unity 의 IS_MOCK_WORLD 가 소유한다 — loader.select 와 같은 값을 봐야
// 화면과 로더가 엇갈리지 않는다 (S15P21A604-466).
// Persistent GameShell 단계에서 이 seam 이 그대로 상주 Unity 로 승격된다.
import { UnityHost } from '../../../unity/host/UnityHost';
import { StaticMockWorldSurface } from './WorldSurface';
import { IS_MOCK_WORLD } from '../../../shared/config/unity';

export { IS_MOCK_WORLD };

export const WorldSurface = IS_MOCK_WORLD ? StaticMockWorldSurface : UnityHost;
