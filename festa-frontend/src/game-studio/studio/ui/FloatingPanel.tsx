// 실험(정식 티켓 아님) — 화면 위 어디든 자유롭게 옮기고 크기도 조절할 수 있는, 편집 화면을
// 막지 않는 떠있는 창. 좌/우 패널 폭 조절(startPanelResize)이 쓰는 것과 같은 pointerdown~
// pointerup 패턴(setPointerCapture로 커서가 요소 밖으로 나가도 드래그가 끊기지 않게 함)을
// 제목 표시줄 드래그(이동)와 모서리 핸들 드래그(크기 조절) 양쪽에 그대로 적용한다.
import { useRef, useState, type PointerEvent as ReactPointerEvent, type ReactNode } from 'react';

export interface FloatingPanelPosition {
  readonly x: number;
  readonly y: number;
}

export interface FloatingPanelSize {
  readonly width: number;
  readonly height: number;
}

interface FloatingPanelProps {
  readonly title: string;
  readonly initialPosition?: FloatingPanelPosition;
  readonly initialSize?: FloatingPanelSize;
  readonly onClose: () => void;
  readonly children: ReactNode;
}

const MIN_WIDTH = 360;
const MIN_HEIGHT = 240;

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

// 창 전체가 항상 뷰포트 안에 온전히 들어오게 한다(일부만 화면 밖으로 나가는 것도 허용하지
// 않는 단순한 규칙 — 최소 구현이라 "화면 가장자리에 살짝 걸치기" 같은 건 다루지 않는다).
const clampPosition = (position: FloatingPanelPosition, size: FloatingPanelSize): FloatingPanelPosition => ({
  x: clamp(position.x, 0, Math.max(0, window.innerWidth - size.width)),
  y: clamp(position.y, 0, Math.max(0, window.innerHeight - size.height)),
});

export const FloatingPanel = ({
  title,
  initialPosition = { x: 120, y: 96 },
  initialSize = { width: 640, height: 420 },
  onClose,
  children,
}: FloatingPanelProps) => {
  const [position, setPosition] = useState<FloatingPanelPosition>(() => clampPosition(initialPosition, initialSize));
  const [size, setSize] = useState<FloatingPanelSize>(initialSize);
  const dragRef = useRef<{ startX: number; startY: number; startLeft: number; startTop: number } | null>(null);
  const resizeRef = useRef<{ startX: number; startY: number; startWidth: number; startHeight: number } | null>(null);

  const startDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    dragRef.current = { startX: event.clientX, startY: event.clientY, startLeft: position.x, startTop: position.y };
  };
  const handleDragMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (drag === null) return;
    const next = { x: drag.startLeft + (event.clientX - drag.startX), y: drag.startTop + (event.clientY - drag.startY) };
    setPosition(clampPosition(next, size));
  };
  const stopDrag = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (dragRef.current === null) return;
    dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };

  const startResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    resizeRef.current = { startX: event.clientX, startY: event.clientY, startWidth: size.width, startHeight: size.height };
  };
  const handleResizeMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    const resize = resizeRef.current;
    if (resize === null) return;
    const maxWidth = Math.max(MIN_WIDTH, window.innerWidth - position.x);
    const maxHeight = Math.max(MIN_HEIGHT, window.innerHeight - position.y);
    setSize({
      width: clamp(resize.startWidth + (event.clientX - resize.startX), MIN_WIDTH, maxWidth),
      height: clamp(resize.startHeight + (event.clientY - resize.startY), MIN_HEIGHT, maxHeight),
    });
  };
  const stopResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (resizeRef.current === null) return;
    resizeRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
  };

  return (
    <section
      aria-label={title}
      className="gss-floating-panel"
      role="dialog"
      style={{ height: size.height, left: position.x, top: position.y, width: size.width }}
    >
      <header
        className="gss-floating-panel-titlebar"
        onPointerDown={startDrag}
        onPointerMove={handleDragMove}
        onPointerUp={stopDrag}
        onPointerCancel={stopDrag}
      >
        <strong>{title}</strong>
        <button aria-label={`${title} 닫기`} onClick={onClose} type="button">×</button>
      </header>
      <div className="gss-floating-panel-body">{children}</div>
      <div
        aria-label={`${title} 크기 조절 핸들`}
        className="gss-floating-panel-resize-handle"
        onPointerDown={startResize}
        onPointerMove={handleResizeMove}
        onPointerUp={stopResize}
        onPointerCancel={stopResize}
        role="presentation"
      />
    </section>
  );
};
