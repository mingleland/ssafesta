// 공통 툴팁 (2026-09-17) — 브라우저 기본 `title` 을 대체한다.
//
// `title` 을 걷는 이유 셋. ① 생김새를 우리가 정할 수 없다 — 어두운 월드 위에 OS 기본 흰 말풍선이
// 뜬다. ② **키보드 focus 에서는 뜨지 않는다.** 마우스를 쓰지 않는 사람에게는 없는 설명이다.
// ③ 지연·위치를 못 고른다. 아이콘 버튼이 많은 화면에서 이 셋은 전부 드러난다.
//
// **트리거를 감싸는 DOM 을 기본으로 만들지 않는다.** HUD·툴바가 flex/grid 로 자식 간격을 재고
// 있어 span 하나만 끼어도 배치가 틀어진다. cloneElement 로 자식에 핸들러만 얹는다.
// 예외는 `disabled` 트리거 하나다 — 브라우저가 비활성 폼 컨트롤에 마우스 이벤트를 아예 보내지
// 않아 자식만으로는 hover 를 알 수 없다. 그때만 얇은 앵커를 두른다.
import {
  cloneElement,
  useCallback,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import type { FocusEvent, MouseEvent, PointerEvent, ReactElement, ReactNode } from 'react';
import { createPortal } from 'react-dom';
import './tooltip.css';

export type TooltipPlacement = 'top' | 'bottom' | 'left' | 'right';

/** 트리거와 말풍선 사이 */
const GAP = 8;
/** 뷰포트 가장자리에서 남길 여백 */
const EDGE = 8;

interface Rect {
  left: number;
  top: number;
  right: number;
  bottom: number;
  width: number;
  height: number;
}

/**
 * 말풍선 위치. 넘치면 **반대쪽으로 뒤집고**, 그래도 넘치면 축을 물려 가장자리 안에 남긴다.
 * 순수 함수로 두어 jsdom 이 재지 못하는 좌표를 테스트에서 직접 검산한다.
 */
export function computeTooltipPosition(
  anchor: Rect,
  bubble: { width: number; height: number },
  placement: TooltipPlacement,
  viewport: { width: number; height: number },
): { left: number; top: number } {
  const clamp = (value: number, max: number) => Math.max(EDGE, Math.min(value, max - EDGE));
  let left: number;
  let top: number;

  if (placement === 'top' || placement === 'bottom') {
    const above = anchor.top - bubble.height - GAP;
    const below = anchor.bottom + GAP;
    top = placement === 'top' ? above : below;
    if (top < EDGE) top = below;
    if (top + bubble.height > viewport.height - EDGE) top = above;
    left = anchor.left + anchor.width / 2 - bubble.width / 2;
  } else {
    const before = anchor.left - bubble.width - GAP;
    const after = anchor.right + GAP;
    left = placement === 'left' ? before : after;
    if (left < EDGE) left = after;
    if (left + bubble.width > viewport.width - EDGE) left = before;
    top = anchor.top + anchor.height / 2 - bubble.height / 2;
  }

  return {
    left: clamp(left, viewport.width - bubble.width),
    top: clamp(top, viewport.height - bubble.height),
  };
}

interface TriggerProps {
  onMouseEnter?: (event: MouseEvent<HTMLElement>) => void;
  onMouseLeave?: (event: MouseEvent<HTMLElement>) => void;
  onFocus?: (event: FocusEvent<HTMLElement>) => void;
  onBlur?: (event: FocusEvent<HTMLElement>) => void;
  onPointerDown?: (event: PointerEvent<HTMLElement>) => void;
  disabled?: boolean;
  'aria-describedby'?: string;
}

interface Props {
  /** 말풍선에 보일 것. 비어 있으면 툴팁을 달지 않는다 */
  content: ReactNode;
  placement?: TooltipPlacement;
  /** 뜨기까지 기다리는 시간(ms). 스치듯 지나가는 커서에 말풍선이 따라붙지 않게 한다 */
  delay?: number;
  /** 툴팁만 끈다 — 트리거의 동작은 그대로다 */
  disabled?: boolean;
  children: ReactElement<TriggerProps>;
}

export function Tooltip({ content, placement = 'top', delay = 120, disabled = false, children }: Props) {
  const id = useId();
  const [anchor, setAnchor] = useState<Rect | null>(null);
  const [pos, setPos] = useState<{ left: number; top: number } | null>(null);
  const bubbleRef = useRef<HTMLDivElement>(null);
  const timer = useRef<number | null>(null);

  const hide = useCallback(() => {
    if (timer.current !== null) {
      window.clearTimeout(timer.current);
      timer.current = null;
    }
    setAnchor(null);
    setPos(null);
  }, []);

  const show = useCallback(
    (element: HTMLElement) => {
      if (timer.current !== null) window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => {
        timer.current = null;
        setAnchor(element.getBoundingClientRect());
      }, delay);
    },
    [delay],
  );

  const off = content === null || content === undefined || content === '' || disabled;
  useEffect(() => {
    if (off) hide();
  }, [off, hide]);
  useEffect(() => hide, [hide]);

  // 떠 있는 동안만 건다. 스크롤·리사이즈에서는 따라다니게 만들지 않고 닫는다 — 툴팁은 잠깐
  // 읽고 마는 것이라, 쫓아다니게 하면 그 계산이 매 프레임 남는다.
  useEffect(() => {
    if (anchor === null) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') hide();
    };
    window.addEventListener('keydown', onKeyDown);
    window.addEventListener('scroll', hide, true);
    window.addEventListener('resize', hide);
    return () => {
      window.removeEventListener('keydown', onKeyDown);
      window.removeEventListener('scroll', hide, true);
      window.removeEventListener('resize', hide);
    };
  }, [anchor, hide]);

  // 크기를 재고 나서 위치를 정한다 — 폭을 모르면 뷰포트 밖 판정을 할 수 없다.
  useLayoutEffect(() => {
    const bubble = bubbleRef.current;
    if (anchor === null || bubble === null) return;
    const box = bubble.getBoundingClientRect();
    setPos(
      computeTooltipPosition(anchor, { width: box.width, height: box.height }, placement, {
        width: window.innerWidth,
        height: window.innerHeight,
      }),
    );
  }, [anchor, placement, content]);

  const props = children.props;
  const handlers: TriggerProps = {
    onMouseEnter: (event) => {
      props.onMouseEnter?.(event);
      if (!off) show(event.currentTarget);
    },
    onMouseLeave: (event) => {
      props.onMouseLeave?.(event);
      hide();
    },
    onFocus: (event) => {
      props.onFocus?.(event);
      if (!off) show(event.currentTarget);
    },
    onBlur: (event) => {
      props.onBlur?.(event);
      hide();
    },
    // 눌렀으면 할 말은 끝났다. 클릭 뒤에도 남아 있으면 그 자리에 뜬 다음 화면을 가린다.
    onPointerDown: (event) => {
      props.onPointerDown?.(event);
      hide();
    },
  };

  const describedBy = anchor !== null ? id : props['aria-describedby'];
  const trigger = cloneElement(
    children,
    props.disabled === true
      ? { 'aria-describedby': describedBy }
      : { ...handlers, 'aria-describedby': describedBy },
  );

  const bubble =
    anchor === null
      ? null
      : createPortal(
          <div
            ref={bubbleRef}
            id={id}
            role="tooltip"
            className="festa-tooltip"
            data-placement={placement}
            data-shown={pos !== null}
            style={pos === null ? { left: 0, top: 0 } : pos}
          >
            {content}
          </div>,
          document.body,
        );

  // 비활성 트리거는 마우스 이벤트를 받지 못한다 — 그때만 앵커를 두른다.
  if (props.disabled === true) {
    return (
      <>
        <span className="festa-tooltip-anchor" {...handlers}>
          {trigger}
        </span>
        {bubble}
      </>
    );
  }

  return (
    <>
      {trigger}
      {bubble}
    </>
  );
}
