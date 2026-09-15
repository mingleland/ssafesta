// 월드 이용 안내를 **처음 들어온 사람에게만** 자동으로 한 번 띄운다 (S15P21A604-599).
//
// 판정을 FE 가 전부 갖는다 — Unity 에 "처음인가" 를 묻지 않는다. 그 값은 브라우저에 있는 것이고,
// 물어보려면 새 이벤트·새 상태가 필요한데 얻는 것이 없다.
//
// localStorage 는 읽기·쓰기 모두 던질 수 있다(사생활 보호 모드·저장 차단). 그때는 "본 적 없다" 로
// 판정한다 — 안내가 한 번 더 뜨는 것은 실패가 아니고, 예외로 월드 진입이 막히는 것이 실패다.
// screenAudio.ts 가 같은 guard 를 쓴다.
const SEEN_STORAGE_KEY = 'festa.worldGuide.seen';

export function hasSeenWorldGuide(): boolean {
  try {
    return window.localStorage.getItem(SEEN_STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

export function markWorldGuideSeen(): void {
  try {
    window.localStorage.setItem(SEEN_STORAGE_KEY, 'true');
  } catch {
    // 저장하지 못하면 다음 진입에 한 번 더 뜬다. 그 이상 할 것이 없다.
  }
}
