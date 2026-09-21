// 부스 방문 경계를 서버로 보내는 어댑터 (S15P21A604-690, GitLab #186).
//
// `boothVisitTracker` 가 비워 둔 sink 자리가 여기다. 경계 판정과 발신을 가른 이유는, 판정 규칙을
// 네트워크 없이 테스트하기 위해서다.
//
// **실패가 월드를 막지 않는다.** 이것은 집계이지 기능이 아니다. 방문 하나를 못 세는 것과 사용자가
// 부스에서 못 나가는 것은 비교할 값이 아니다. 그래서 오류를 삼키되 **조용히 버리지는 않는다** —
// 콘솔에 남긴다. 실패를 아무 데도 안 적어서 난 사고가 이미 있다(T-24).
//
// **경로에 넣을 번호는 Unity 가 준 그대로가 아니다.** Unity 가 `WORLD_BOOTH_CONTEXT` 에 싣는 값은
// 씬의 `Interior_NN` 에서 읽은 **슬롯 번호(1~12)** 이고, 방문 API 의 경로는 `boothId` 를 요구한다.
// 둘은 1:1 이 아니다(`entities/booth/types.ts` 의 SlotView 주석 — 혼용 금지). 그대로 보내면 남의
// 부스에 방문이 적히거나 404 로 사라지고, 미션은 영영 오르지 않는다. 같은 혼동이 간판에서 이미
// 한 번 났다(S15P21A604-658).
import { attachVisitId, setBoothVisitSink, type BoothVisitEdge } from './boothVisitTracker';
import { enterBooth, exitBooth } from '../../../entities/booth/visitApi';
import { leaseApi } from '../../../entities/booth/leaseApi.select';
import { queryClient } from '../../../app/providers/queryClient';

/**
 * 슬롯 번호를 진짜 `boothId` 로 옮긴다. 정본은 `GET /booth-slots` 이고, 임대 화면이 이미 쓰는
 * `['booth-slots']` 캐시를 그대로 빌린다 — 임대·반납이 그 키를 무효화하므로 여기에 따로 캐시를
 * 두면 그 무효화를 한 번 더 구현하게 된다.
 *
 * 못 찾으면 `null` 이다: 빈 칸이거나 목록이 낡았다는 뜻이고, 번호를 지어내느니 방문 하나를
 * 포기하는 편이 낫다.
 */
async function resolveBoothId(slotId: number): Promise<number | null> {
  const slots = await queryClient.fetchQuery({ queryKey: ['booth-slots'], queryFn: leaseApi.getSlots });
  return slots.find((slot) => slot.slotId === slotId)?.boothId ?? null;
}

async function report(edge: BoothVisitEdge): Promise<void> {
  // 슬롯 번호가 없다. 계약상 안에 있으면 번호가 오므로 여기 오면 Unity 쪽 문제이고,
  // tracker 가 이미 경고를 남겼다.
  if (edge.boothId === null) return;

  if (edge.kind === 'enter') {
    const boothId = await resolveBoothId(edge.boothId);
    if (boothId === null) return warnEmptySlot(edge);
    const view = await enterBooth(boothId);
    attachVisitId(edge.token, view.visitId);
    return;
  }

  // 게스트 퇴장은 서버가 403 으로 막는다 — 보내지 않는 것이 계약이다(sendable).
  // 입장이 실패했으면 id 가 없고, 그러면 서버에 닫을 행도 없다. 둘 다 조회보다 먼저 본다 —
  // 쓰지 않을 값을 받으러 나가지 않는다.
  if (!edge.sendable || edge.visitId === null) return;
  const boothId = await resolveBoothId(edge.boothId);
  if (boothId === null) return warnEmptySlot(edge);
  await exitBooth(boothId, edge.visitId);
}

// 빈 칸이거나 슬롯 목록이 낡았다. 방문 하나를 잃을 뿐이지만 조용히 버리지는 않는다(T-24).
function warnEmptySlot(edge: BoothVisitEdge): void {
  console.warn('[boothVisit] 슬롯에 부스가 없다 — 방문을 세지 못한다', edge.kind, edge.boothId);
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
