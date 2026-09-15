import { useEffect, useState } from 'react';

interface CommitInputProps {
  readonly value: string;
  readonly label: string;
  readonly multiline?: boolean;
  readonly placeholder?: string;
  // S15P21A604-529 — 기본값(false)은 기존 그대로: 빈 값으로 지우면 커밋하지 않고 이전 값
  // 으로 되돌린다(Scene 이름/대사처럼 "항상 뭔가 있어야 하는" 필드에 맞는 동작). 오브젝트
  // 이름처럼 "비워두는 것 자체가 유효한 상태"인 필드는 true로 켜서 빈 문자열도 그대로
  // onCommit에 넘긴다.
  readonly allowEmpty?: boolean;
  readonly onCommit: (value: string) => void;
  readonly onDraftChange?: (value: string) => void;
}

export const CommitInput = ({
  value,
  label,
  multiline = false,
  placeholder,
  allowEmpty = false,
  onCommit,
  onDraftChange,
}: CommitInputProps) => {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);

  const commit = () => {
    if (draft.trim() === '') {
      if (allowEmpty) {
        if (draft !== value) onCommit(draft.trim());
        return;
      }
      setDraft(value);
      onDraftChange?.(value);
      return;
    }
    if (draft !== value) onCommit(draft);
  };

  return (
    <label className="gss-field">
      <span>{label}</span>
      {multiline ? (
        <textarea
          onBlur={commit}
          onChange={(event) => {
            setDraft(event.target.value);
            onDraftChange?.(event.target.value);
          }}
          placeholder={placeholder}
          rows={4}
          value={draft}
        />
      ) : (
        <input
          onBlur={commit}
          onChange={(event) => {
            setDraft(event.target.value);
            onDraftChange?.(event.target.value);
          }}
          onKeyDown={(event) => {
            if (event.key === 'Enter') event.currentTarget.blur();
            if (event.key === 'Escape') {
              setDraft(value);
              onDraftChange?.(value);
              event.currentTarget.blur();
            }
          }}
          placeholder={placeholder}
          type="text"
          value={draft}
        />
      )}
    </label>
  );
};
