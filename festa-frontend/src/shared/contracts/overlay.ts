// Overlay 공통 계약 정본 — Shared Contract Freeze Candidate v0.1 (2026-09-01).
// Feature Track 소유는 타입·intent·payload 경계까지. OverlayFrame 의 layout·CSS·시각 상태는 UI Track 소유.
import type { OverlayType } from '../types/overlay';

/** UI Track 이 구현할 프레임이 Feature 로부터 받는 전부 */
export interface OverlayFrameVM {
  id: OverlayType;
  title: string;
  close: () => void;
}

/** game-portal-bridge.md OnOverlayStateChanged 계약의 FE 측 어휘 — 송신 구현은 계약 확정 후 */
export type OverlayLifecycleState = 'OPENED' | 'CLOSED' | 'FAILED';
