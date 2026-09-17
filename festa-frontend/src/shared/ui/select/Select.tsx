// 테마를 따르는 선택 입력 — 네이티브 `<select>` 를 대신한다.
//
// 네이티브 팝업은 OS 가 그려서 우리 다크 테마를 타지 않는다. 배경색·글자색을 억지로 맞춰도
// 모서리·간격·강조색·스크롤 막대가 앱과 따로 논다. 그래서 목록을 직접 그린다 — 닫힌 모습은
// 기존 입력칸과 같은 토큰을 쓰고, 열린 목록은 오버레이·메뉴와 같은 표면을 쓴다.
//
// 접근성은 listbox 규약을 그대로 따른다: 버튼이 `aria-haspopup="listbox"`, 목록이 `role="listbox"`,
// 각 줄이 `role="option"` 이고 선택 상태는 `aria-selected` 가 말한다.
import { useEffect, useId, useRef, useState } from 'react';
import './select.css';

export interface SelectOption<T extends string> {
  value: T;
  label: string;
}

interface Props<T extends string> {
  value: T;
  options: readonly SelectOption<T>[];
  onChange: (value: T) => void;
  /** 라벨 요소가 따로 없을 때 쓴다 */
  'aria-label'?: string;
  /** `<label htmlFor>` 로 묶을 때 쓴다 */
  id?: string;
  className?: string;
  disabled?: boolean;
}

export function Select<T extends string>({
  value,
  options,
  onChange,
  id,
  className,
  disabled = false,
  'aria-label': ariaLabel,
}: Props<T>) {
  const [open, setOpen] = useState(false);
  // 열린 동안의 이동 위치. 선택값과 다르다 — 화살표로 훑는 중에는 아직 고른 것이 아니다.
  const [cursor, setCursor] = useState(0);
  const rootRef = useRef<HTMLDivElement>(null);
  const listId = useId();

  const selected = options.findIndex((option) => option.value === value);
  const label = selected >= 0 ? options[selected].label : '';

  function openList(): void {
    if (disabled) return;
    setCursor(selected >= 0 ? selected : 0);
    setOpen(true);
  }

  function choose(index: number): void {
    const option = options[index];
    if (option !== undefined) onChange(option.value);
    setOpen(false);
  }

  // 바깥을 누르면 닫는다. 목록이 떠 있는 동안만 듣는다 — 평상시 문서 전체에 리스너를 걸지 않는다.
  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent) {
      const root = rootRef.current;
      if (root !== null && !root.contains(event.target as Node)) setOpen(false);
    }
    document.addEventListener('pointerdown', onPointerDown);
    return () => document.removeEventListener('pointerdown', onPointerDown);
  }, [open]);

  return (
    <div ref={rootRef} className={className === undefined ? 'fs-select' : 'fs-select ' + className}>
      <button
        type="button"
        id={id}
        className="fs-select-button"
        disabled={disabled}
        aria-label={ariaLabel}
        aria-haspopup="listbox"
        aria-expanded={open}
        aria-controls={open ? listId : undefined}
        onClick={() => (open ? setOpen(false) : openList())}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            if (!open) { openList(); return; }
            setCursor((n) => Math.min(options.length - 1, Math.max(0, n + (event.key === 'ArrowDown' ? 1 : -1))));
            return;
          }
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            if (open) choose(cursor);
            else openList();
            return;
          }
          if (event.key === 'Escape' && open) {
            // 상위 <dialog>·오버레이가 같은 ESC 로 함께 닫히지 않게 여기서 멈춘다
            event.preventDefault();
            event.stopPropagation();
            setOpen(false);
          }
        }}
      >
        <span className="fs-select-value">{label}</span>
        <svg className="fs-select-arrow" viewBox="0 0 12 12" aria-hidden="true" focusable="false">
          <path d="M2.5 4.5 6 8l3.5-3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      </button>

      {open && (
        <ul className="fs-select-list" id={listId} role="listbox" aria-label={ariaLabel} tabIndex={-1}>
          {options.map((option, index) => (
            <li
              key={option.value}
              role="option"
              aria-selected={option.value === value}
              className={
                'fs-select-option' +
                (option.value === value ? ' is-selected' : '') +
                (index === cursor ? ' is-cursor' : '')
              }
              onPointerEnter={() => setCursor(index)}
              onClick={() => choose(index)}
            >
              {option.label}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
