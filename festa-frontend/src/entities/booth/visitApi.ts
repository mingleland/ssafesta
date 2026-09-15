// 부스 방문·체류 계측 real API (S15P21A604-690, GitLab #186).
//
// **두 호출 모두 요청 본문이 없다.** 어느 월드 채널에서 들어왔는지는 서버가 정한다 —
// 클라이언트가 그 값을 가질 길이 없고, 채널 정체성을 클라이언트 주장에서 받지 않는 것이 이
// 시스템의 기존 판단이다(헌법 16조). 예전에는 `worldChannel` 이 필수였고 그것 때문에 FE 가
// 착수하지 못했다. 2026-09-14 에 BE 가 걷어냈다.
//
// 출처: backend BoothVisitController (`/api/v1/booths/{boothId}`)
import { api } from '../../shared/api/client';

export interface VisitView {
  /** 서버만 발급한다. FE 가 만들어 내지 않는다 */
  visitId: string;
  enteredAt: string;
}

// 회원의 입장이 두 번 오면 서버가 같은 visitId 로 합친다(재전송 방어). 게스트는 식별자가 없어
// 합치지 못하므로 FE 도 중복 발신하지 않는다 — boothVisitTracker 가 같은 전이를 두 번 흘리지
// 않는 것이 그 방어다.
export async function enterBooth(boothId: number): Promise<VisitView> {
  return api<VisitView>(`/api/v1/booths/${boothId}/visits`, { method: 'POST' });
}

// 204. **회원만 부를 수 있다** — 서버의 소유 판정이 `visitorUserId != null` 을 요구해 게스트가
// 부르면 항상 403 이다. 게스트 방문이 열린 채로 남는 것은 계약대로이고(서버가 openVisits 로 따로
// 세고 평균 체류에서 뺀다) FE 가 보정할 것이 아니다.
export async function exitBooth(boothId: number, visitId: string): Promise<void> {
  await api<void>(`/api/v1/booths/${boothId}/visits/${visitId}/exit`, { method: 'POST' });
}
