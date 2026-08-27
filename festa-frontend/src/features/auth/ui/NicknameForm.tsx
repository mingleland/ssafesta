// 닉네임 입력 UI(T008) — NICKNAME_REQUIRED 분기에서만 CallbackPage가 이 컴포넌트를 연다.
// 서버 판정이 최종이다(FR-007e) — FE 사전 검사는 공백/빈 값만 한다. 길이 제한은 nickname-policy.md에
// 없어 추측 확정하지 않는다(plan.md §미결, 헌법 30조).
import { useState, type FormEvent } from 'react';
import { isApiError } from '../../../shared/api/client';
import { authApi } from '../../../entities/auth/api.select';

function isBlank(value: string): boolean {
  return value.trim() === '';
}

// 이유 비특정 일반 안내만 노출한다(nickname-policy.md 검사 규칙 4 — 금칙어 종류·일치 단어 비노출)
const GENERIC_REJECTION_MESSAGE = '사용할 수 없는 닉네임입니다. 다른 닉네임을 입력해 주세요.';

export function NicknameForm({
  onAuthenticated,
  onHandoffExpired,
}: {
  onAuthenticated: (accessToken: string, expiresAt: string) => void;
  onHandoffExpired: () => void;
}) {
  const [nickname, setNickname] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    if (isBlank(nickname)) {
      setNotice('닉네임을 입력해 주세요.');
      return;
    }
    setSubmitting(true);
    setNotice(null);
    try {
      const result = await authApi.complete({ nickname });
      if (result.status === 'AUTHENTICATED') {
        onAuthenticated(result.accessToken, result.expiresAt);
        return;
      }
      // 계약상 두 번째 호출은 AUTHENTICATED 아니면 오류다 — 방어적으로만 대비
      setNotice(GENERIC_REJECTION_MESSAGE);
    } catch (err) {
      // handoff 소멸(400/410 상당)은 이 폼의 책임이 아니다 — 상위(CallbackPage)가 재시작 흐름으로 처리(FE 의무 3)
      if (isApiError(err) && err.code.startsWith('OAUTH_HANDOFF')) {
        onHandoffExpired();
        return;
      }
      setNotice(GENERIC_REJECTION_MESSAGE);
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form onSubmit={handleSubmit}>
      <label htmlFor="nickname">닉네임</label>
      <input
        id="nickname"
        value={nickname}
        onChange={(e) => setNickname(e.target.value)}
        disabled={submitting}
        autoComplete="off"
      />
      {notice && <p role="alert">{notice}</p>}
      <button type="submit" disabled={submitting}>
        확인
      </button>
    </form>
  );
}
