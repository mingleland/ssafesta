// 부스 방문 경계 추적 — Unity 가 보내는 "부스 안/밖" 관측을 입장·퇴장 edge 로 바꾸는 자리다
// (S15P21A604-690, GitLab #186).
//
// **이 모듈은 여전히 서버를 부르지 않는다.** 경계만 판정하고 발신은 sink 가 한다
// (`features/world/model/boothVisitReporter.ts`). 그 분리 덕분에 경계 규칙을 네트워크 없이
// 테스트할 수 있다.
//
// **`visitId` 를 만들지 않는다.** 그 값은 서버만 발급한다. sink 가 입장 응답에서 받아
// `attachVisitId` 로 얹고, 퇴장 edge 가 그것을 실어 내보낸다 — 로컬 임시값을 두면 나중에 진짜
// id 와 섞여 어느 쪽이 서버 것인지 알 수 없게 된다.
//
// **판정을 `insideBooth` 불리언 하나로 하지 않는다.** `(insideBooth, boothId)` 쌍의 전이로 본다 —
// 밖으로 나가지 않고 A→B 로 바뀌면 불리언은 true 그대로라 새 방문을 통째로 놓친다.
import { getSessionSnapshot } from '../../auth/model/session';
import { getWorldContext, subscribeWorldContext, type WorldContext } from './worldContext';

export interface BoothVisitEdge {
  kind: 'enter' | 'exit';
  /** 어느 부스였나. Unity 가 번호를 안 실어 보낸 경우 null 이다 */
  boothId: number | null;
  at: string;
  /**
   * 이 경계가 속한 방문의 식별 토큰 — 방문마다 하나씩 올라간다.
   *
   * **시각으로 방문을 구분하지 않는다.** 들어갔다 바로 나갔다 다시 들어오면 세 전이가 같은
   * 밀리초에 일어나 `enteredAt` 이 전부 같아진다. 그러면 늦게 온 입장 응답의 `visitId` 가
   * 다음 방문에 붙는다(테스트로 잡았다).
   */
  token: number;
  /**
   * 퇴장 edge 에만 실린다 — 닫을 방문의 서버 발급 id.
   *
   * `closeVisit` 이 pending 을 비운 뒤에 emit 하므로 sink 가 나중에 읽을 수 없다. 그래서 값을
   * edge 에 실어 보낸다. 입장이 실패해 id 가 없으면 `null` 이고, 그때는 닫을 것도 없다.
   */
  visitId: string | null;
  /**
   * 서버로 보낼 수 있는 경계인가.
   *
   * 입장은 게스트도 보낸다 — `visitor_user_id` 가 nullable 인 것이 그 뜻이다. 퇴장은 서버의 소유
   * 판정이 `visitorUserId != null` 을 요구해 게스트가 부르면 항상 403 이라, 기록만 하고 보내지
   * 않는다.
   */
  sendable: boolean;
}

export interface PendingVisit {
  boothId: number | null;
  enteredAt: string;
  /** 입장 응답이 도착하면 sink 가 채운다. 도착 전·실패면 null 이다 */
  visitId: string | null;
  /** 이 방문의 식별 토큰. `attachVisitId` 가 같은 방문인지 가리는 값이다 */
  token: number;
}

const OUTSIDE: WorldContext = { insideBooth: false, boothId: null };

let previous: WorldContext = OUTSIDE;
let pending: PendingVisit | null = null;
let sink: ((edge: BoothVisitEdge) => void) | null = null;
let visitSeq = 0;

/**
 * 경계가 나가는 유일한 출구 — BE 가 도착하면 여기에 방문 API 어댑터를 붙인다.
 *
 * 지금은 등록된 것이 없어 경계가 모듈 안에서 끝난다. 그것이 의도다: 부를 수 없는 API 를 향해
 * 반쯤 만든 요청을 두느니 경계만 정확히 잡아 두고 나중에 붙이는 편이 낫다.
 */
export function setBoothVisitSink(next: ((edge: BoothVisitEdge) => void) | null): void {
  sink = next;
}

/** 아직 닫히지 않은 방문. BE 도달 후 `visitId` 가 얹힐 자리이기도 하다 */
export function getPendingVisit(): PendingVisit | null {
  return pending;
}

function emit(
  kind: 'enter' | 'exit',
  boothId: number | null,
  at: string,
  visitId: string | null,
  token: number,
): void {
  const sendable = kind === 'enter' || getSessionSnapshot().kind === 'member';
  sink?.({ kind, boothId, at, visitId, token, sendable });
}

function openVisit(boothId: number | null, at: string): void {
  visitSeq += 1;
  pending = { boothId, enteredAt: at, visitId: null, token: visitSeq };
  emit('enter', boothId, at, null, visitSeq);
}

function closeVisit(at: string): void {
  const boothId = pending?.boothId ?? previous.boothId;
  const visitId = pending?.visitId ?? null;
  const token = pending?.token ?? visitSeq;
  pending = null;
  emit('exit', boothId, at, visitId, token);
}

/**
 * 입장 응답의 `visitId` 를 열려 있는 방문에 얹는다. sink 만 부른다.
 *
 * `token` 으로 같은 방문인지 확인하는 것이 핵심이다 — 응답이 늦게 도착하는 사이 사용자가
 * 이미 나갔다 다시 들어왔을 수 있고, 그때 무턱대고 쓰면 **옛 방문의 id 가 새 방문에 붙는다.**
 */
export function attachVisitId(token: number, visitId: string): void {
  if (pending === null || pending.token !== token) return;
  pending = { ...pending, visitId };
}

/**
 * 관측 하나를 경계로 바꾼다. `subscribeWorldContext` 가 부르는 것이 정상 경로이고, 테스트는 이
 * 함수를 직접 부른다.
 */
export function applyWorldContextChange(
  next: WorldContext,
  now: () => string = () => new Date().toISOString(),
): void {
  const prev = previous;
  previous = next;

  // 같은 값 재전송 — Unity 가 0.35초 스윕으로 같은 상태를 반복해 보낸다. worldContext 가 이미
  // 걸러 주지만 이 함수를 직접 부르는 경로도 있으니 여기서도 막는다.
  if (prev.insideBooth === next.insideBooth && prev.boothId === next.boothId) return;

  const at = now();

  if (!prev.insideBooth && next.insideBooth) {
    if (next.boothId === null) {
      // 계약상 안에 있으면 번호가 온다. 없으면 방문은 잡되 어느 부스인지 모르는 채로 둔다 —
      // 0 같은 값을 지어내면 언젠가 "0번 부스" 로 읽힌다.
      console.warn('[boothVisit] insideBooth=true 인데 boothId 가 없다 — 방문을 번호 없이 연다');
    }
    openVisit(next.boothId, at);
    return;
  }

  if (prev.insideBooth && !next.insideBooth) {
    closeVisit(at);
    return;
  }

  // true/A → true/B. Unity 송신 계약(unity/bridge/events.ts)은 "안↔밖이 바뀔 때만" 보낸다고 못
  // 박고 있어 나오지 않아야 한다. 그래도 조용히 버리지 않는다 — 버리면 방문 하나가 통째로
  // 사라지고, 실패를 삼켜서 난 사고가 이미 있다(T-24). 처리하고 드러낸다.
  console.warn(
    '[boothVisit] 부스 밖을 거치지 않은 전환 — ' +
      String(prev.boothId) +
      ' → ' +
      String(next.boothId) +
      '. Unity 송신 계약 위반이다. A 퇴장 + B 진입으로 처리한다',
  );
  closeVisit(at);
  openVisit(next.boothId, at);
}

/**
 * Unity 인스턴스가 새로 뜰 때 부른다 — 옛 방문이 새 인스턴스로 이어지면 안 된다.
 *
 * `previous` 까지 되돌리는 것이 핵심이다. `resetWorldContext()` 가 뒤이어 OUTSIDE 를 흘리는데,
 * 여기서 `previous` 를 안 비우면 그 신호가 "퇴장" 으로 읽혀 있지도 않은 퇴장이 하나 생긴다.
 */
export function discardPendingVisit(): void {
  pending = null;
  previous = OUTSIDE;
}

/** 월드 화면이 사는 동안 관측을 구독한다. 반환값은 해제 함수다 */
export function startBoothVisitTracking(): () => void {
  previous = getWorldContext();
  return subscribeWorldContext(() => applyWorldContextChange(getWorldContext()));
}

// 테스트 전용
export function __resetBoothVisitTrackerForTests(): void {
  previous = OUTSIDE;
  pending = null;
  sink = null;
  visitSeq = 0;
}
