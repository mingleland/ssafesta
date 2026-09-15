// 이미지 유령 드래그 차단 (S15P21A604-463 회차에서 함께 정리).
//
// 이미지는 기본이 draggable 이라 배경·로고를 끌면 반투명 사본이 따라온다 — 웹 문서의
// 기본값일 뿐 게임 화면에서 원하는 동작이 아니다. CSS 의 `-webkit-user-drag` 로 Chrome
// 계열은 막히지만 Firefox 는 그 속성을 모르므로 여기서 한 번 더 막는다.
//
// 반대로 Game Studio 는 실제 drag&drop 으로 동작한다(오브젝트 배치·액션 재정렬).
// `draggable` 을 명시한 요소 안에서 시작된 드래그는 손대지 않는다 — 그것이 기능이다.

export function shouldPreventDrag(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  if (target.closest('[draggable="true"]') !== null) return false;
  return target.tagName === 'IMG';
}

/** 문서 전역에 가드를 건다. 반환값은 해제 함수다. */
export function installImageDragGuard(): () => void {
  function onDragStart(event: DragEvent) {
    if (shouldPreventDrag(event.target)) event.preventDefault();
  }
  document.addEventListener('dragstart', onDragStart);
  return () => document.removeEventListener('dragstart', onDragStart);
}
