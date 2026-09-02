import { useEffect, useState } from 'react';

interface CommitInputProps {
  readonly value: string;
  readonly label: string;
  readonly multiline?: boolean;
  readonly placeholder?: string;
  readonly onCommit: (value: string) => void;
  readonly onDraftChange?: (value: string) => void;
}

export const CommitInput = ({
  value,
  label,
  multiline = false,
  placeholder,
  onCommit,
  onDraftChange,
}: CommitInputProps) => {
  const [draft, setDraft] = useState(value);
  useEffect(() => setDraft(value), [value]);

  const commit = () => {
    if (draft !== value && draft.trim() !== '') onCommit(draft);
    else if (draft.trim() === '') {
      setDraft(value);
      onDraftChange?.(value);
    }
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
