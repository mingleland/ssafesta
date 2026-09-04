// World Layer 구현 선택 — 기존 *.select.ts 관행과 같은 seam (S15P21A604-406).
// VITE_USE_MOCK=true 면 최신 Unity 캡처를 깐 정지 화면, 아니면 실제 UnityHost.
// Persistent GameShell 단계에서 이 seam 이 그대로 상주 Unity 로 승격된다.
import { UnityHost } from '../../../unity/host/UnityHost';
import { StaticMockWorldSurface } from './WorldSurface';

export const IS_MOCK_WORLD = import.meta.env.VITE_USE_MOCK === 'true';

export const WorldSurface = IS_MOCK_WORLD ? StaticMockWorldSurface : UnityHost;
