// 부스 관리 상세 패널의 식별과 payload (S15P21A604-755).
//
// **화면 종류 + boothId 로 고정하지 않는다.** 다섯 화면이 오늘은 `boothId` 하나만 쓰지만, 그것을
// 공통 필드로 굳히면 파라미터가 하나라도 늘 때 모든 패널이 그 모양을 나눠 갖게 된다. 판별
// 유니온으로 두면 늘어나는 패널만 자기 자리를 넓힌다.
//
// 이 타입이 라우트 파라미터의 정본이다 — route 와 오버레이가 같은 본문 컴포넌트를 공유하고,
// 그 컴포넌트가 받는 것이 여기 payload 다.

export type ManagementPanel =
  | { kind: 'project'; boothId: number }
  | { kind: 'survey'; boothId: number }
  | { kind: 'consultation'; boothId: number }
  | { kind: 'ai-agent'; boothId: number }
  | { kind: 'studio'; boothId: number };

export type ManagementPanelKind = ManagementPanel['kind'];

/** deep-link 경로 — 오버레이로 열든 URL 로 열든 같은 화면이 뜬다 */
export function managementPanelPath(panel: ManagementPanel): string {
  if (panel.kind === 'studio') return `/app/studio/${panel.boothId}`;
  return `/app/booths/${panel.boothId}/${panel.kind}`;
}
