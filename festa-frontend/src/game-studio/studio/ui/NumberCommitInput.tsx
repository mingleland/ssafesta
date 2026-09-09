import { useEffect, useRef, useState } from 'react';

interface NumberCommitInputProps {
  readonly value: number;
  readonly label: string;
  readonly min: number;
  readonly max: number;
  readonly onCommit: (value: number) => void;
}

// S15P21A604-554 — CommitInput과 같은 커밋-온-블러 패턴이되 숫자 전용이다. 타이핑 중에는
// 자릿수·범위 제한 없이 자유롭게 입력받고, blur(또는 Enter)에서만 정수로 정리해 min~max로
// clamp한다(완전히 비우면 min). 예전엔 매 키 입력마다 즉시 엄격한 스키마 검증
// (gameProject.ts의 integerAt)을 태워 범위를 벗어나는 순간 입력이 그 자리에서 이전 값으로
// 되돌아갔다 — 최소값이 2자리 이상인 필드(발사 간격 100, 생성 간격 250)는 처음부터 다시
// 타이핑하는 것 자체가 불가능했다.
//
// type="number" 대신 type="text" + inputMode="numeric"을 쓴다 — 이 8개 필드는 전부
// min이 1 이상(음수 무의미)이고 정수 전용(소수점 무의미)이라, onChange에서 숫자(0-9)
// 이외의 모든 문자를 걸러내는 편이 네이티브 number input의 "e"/"+"/"-"/"." 허용 동작에
// 기대는 것보다 확실하다 — 모바일 숫자 키패드는 inputMode로 그대로 유지된다.
const digitsOnly = (raw: string): string => raw.replace(/[^0-9]/g, '');

export const NumberCommitInput = ({ value, label, min, max, onCommit }: NumberCommitInputProps) => {
  const [draft, setDraft] = useState(String(value));
  useEffect(() => setDraft(String(value)), [value]);
  // Escape가 draft를 되돌리고 곧바로 blur()를 호출하는데, 그 blur는 같은 동기 실행
  // 안에서 onBlur(commit)를 중첩 호출한다 — 이 시점의 commit은 아직 리렌더 전이라
  // "되돌리기 직전"의 draft(방금 취소하려던 값)를 그대로 읽어버려 취소가 아니라 커밋이
  // 되는 문제가 있었다(실측으로 확인). 이 ref로 "다음 한 번의 commit은 건너뛴다"를
  // 표시해 막는다.
  const skipNextCommitRef = useRef(false);

  const commit = () => {
    if (skipNextCommitRef.current) {
      skipNextCommitRef.current = false;
      return;
    }
    const cleaned = digitsOnly(draft);
    const parsed = cleaned === '' ? min : Number.parseInt(cleaned, 10);
    const clamped = Number.isFinite(parsed) ? Math.max(min, Math.min(max, parsed)) : min;
    if (clamped !== value) onCommit(clamped);
    else setDraft(String(clamped));
  };

  return (
    <label className="gss-field">
      <span>{label}</span>
      <input
        inputMode="numeric"
        onBlur={commit}
        onChange={(event) => setDraft(digitsOnly(event.target.value))}
        onKeyDown={(event) => {
          if (event.key === 'Enter') event.currentTarget.blur();
          if (event.key === 'Escape') {
            skipNextCommitRef.current = true;
            setDraft(String(value));
            event.currentTarget.blur();
          }
        }}
        type="text"
        value={draft}
      />
    </label>
  );
};
