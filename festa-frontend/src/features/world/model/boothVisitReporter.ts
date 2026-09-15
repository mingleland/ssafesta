// 부스 방문 경계를 서버로 보내는 어댑터 (S15P21A604-690, GitLab #186).
//
// `boothVisitTracker` 가 비워 둔 sink 자리가 여기다. 경계 판정과 발신을 가른 이유는, 판정 규칙을
// 네트워크 없이 테스트하기 위해서다.
//
// **실패가 월드를 막지 않는다.** 이것은 집계이지 기능이 아니다. 방문 하나를 못 세는 것과 사용자가
// 부스에서 못 나가는 것은 비교할 값이 아니다. 그래서 오류를 삼키되 **조용히 버리지는 않는다** —
// 콘솔에 남긴다. 실패를 아무 데도 안 적어서 난 사고가 이미 있다(T-24).
import { attachVisitId, setBoothVisitSink, type BoothVisitEdge } from './boothVisitTracker';
import { enterBooth, exitBooth } from '../../../entities/booth/visitApi';

async function report(edge: BoothVisitEdge): Promise<void> {
  // 경로에 넣을 번호가 없다. 계약상 안에 있으면 번호가 오므로 여기 오면 Unity 쪽 문제이고,
  // tracker 가 이미 경고를 남겼다.
  if (edge.boothId === null) return;

  if (edge.kind === 'enter') {
    const view = await enterBooth(edge.boothId);
    attachVisitId(edge.token, view.visitId);
    return;
  }

  // 게스트 퇴장은 서버가 403 으로 막는다 — 보내지 않는 것이 계약이다(sendable).
  // 입장이 실패했으면 id 가 없고, 그러면 서버에 닫을 행도 없다.
  if (!edge.sendable || edge.visitId === null) return;
  await exitBooth(edge.boothId, edge.visitId);
}

/** 월드 화면이 사는 동안 경계를 서버로 흘린다. 반환값은 해제 함수다 */
export function startBoothVisitReporting(): () => void {
  setBoothVisitSink((edge) => {
    void report(edge).catch((error: unknown) => {
      // 404 BOOTH_NOT_FOUND · 404 LAYOUT_NOT_PUBLISHED · 409 BOOTH_LEASE_EXPIRED 가 여기로 온다.
      // 전부 "셀 수 없는 방문" 이고 사용자가 할 일이 없다.
      console.warn('[boothVisit] 계측 발신 실패 — 집계만 잃는다', edge.kind, edge.boothId, error);
    });
  });
  return () => setBoothVisitSink(null);
}
