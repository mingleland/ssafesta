// Overlay 개폐를 Unity 에 알리는 seam — 현재 no-op.
// 계약(specs/019/contracts/game-portal-bridge.md OnOverlayStateChanged)은 Draft 이고 수신
// GameObject 명(receiverObjectName)이 미확정(#56)이라 SendMessage 를 아직 보내지 않는다.
// 계약 확정 시 이 함수 본문만 SendMessage 구현으로 바뀐다 — 호출부(overlay open/close)는 불변.
import type { OverlayType } from '../../shared/types/overlay';
import type { OverlayLifecycleState } from '../../shared/contracts/overlay';

export function notifyOverlayState(_state: OverlayLifecycleState, _overlay: OverlayType): void {
  // no-op: Unity 수신부·receiverObjectName 미확정 (decision-queue #4, backlog #56)
}
