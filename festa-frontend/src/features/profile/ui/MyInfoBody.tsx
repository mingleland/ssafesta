// 내 정보 본문 — ProfilePage(/app/profile)와 MyInfoOverlay(ESC 내 정보) 가 공유한다 (S15P21A604-798).
// PageShell·OverlayFrame 같은 바깥 껍데기는 포함하지 않는다 — 호출부가 각자의 틀로 감싼다.
import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import {
  beginWithdrawal,
  cancelWithdrawal,
  confirmWithdrawal,
  loadProfile,
  submitNickname,
  useProfile,
} from '../model/profile';
import { walletApi } from '../../../entities/wallet/api.select';
import { useSession } from '../../auth/model/session';
import { TransactionsSection } from '../../wallet/ui/TransactionsSection';
import { ScreenError, ScreenLoading } from '../../shell/ui/PageShell';
import { AdminConsoleLink } from '../../admin/ui/AdminConsoleLink';
import '../../../pages/profile/profilePage.css';

const PROVIDER_LABEL: Record<string, string> = { google: 'Google', kakao: 'Kakao', ssafy: 'SSAFY', guest: '게스트' };

const NICKNAME_ERROR: Record<string, string> = {
  duplicate: '이미 사용 중인 닉네임입니다.',
  forbidden: '사용할 수 없는 닉네임입니다.',
  format: '닉네임 형식이 올바르지 않습니다.',
  unknown: '닉네임을 변경하지 못했습니다.',
};

export function MyInfoBody() {
  const state = useProfile();
  const { kind } = useSession();
  const navigate = useNavigate();
  const [draft, setDraft] = useState('');
  const [editing, setEditing] = useState(false);

  const walletQuery = useQuery({
    queryKey: ['wallet-balance'],
    queryFn: walletApi.getWallet,
    enabled: kind === 'member',
  });

  useEffect(() => {
    void loadProfile();
  }, []);

  useEffect(() => {
    if (state.account) setDraft(state.account.nickname);
  }, [state.account?.nickname]);

  useEffect(() => {
    if (state.nicknameEdit.phase === 'success') setEditing(false);
  }, [state.nicknameEdit.phase]);

  if (state.status === 'loading' || state.status === 'idle') {
    return <ScreenLoading label="계정을 불러오는 중..." />;
  }
  if (state.status === 'error' || state.account === null) {
    return (
      <ScreenError title="계정을 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void loadProfile()} />
    );
  }

  const account = state.account;
  const submitting = state.nicknameEdit.phase === 'submitting';

  return (
    <>
      <div className="pf-grid">
        <section className="sc-card pf-identity">
          <span className="pf-avatar">{account.nickname.slice(0, 1)}</span>
          <div className="pf-identity-body">
            {editing ? (
              <form
                className="pf-nickname-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  void submitNickname(draft);
                }}
              >
                <input className="pf-input" value={draft} maxLength={20} onChange={(e) => setDraft(e.target.value)} aria-label="닉네임" />
                <button type="submit" className="sc-btn sc-btn-primary sc-btn-sm" disabled={submitting || draft.trim() === ''}>
                  {submitting ? '변경 중...' : '저장'}
                </button>
                <button type="button" className="sc-btn sc-btn-sm" onClick={() => setEditing(false)}>
                  취소
                </button>
              </form>
            ) : (
              <div className="pf-nickname-row">
                <strong className="pf-nickname">{account.nickname}</strong>
                <button type="button" className="sc-btn sc-btn-sm" onClick={() => setEditing(true)}>
                  이름 변경
                </button>
              </div>
            )}
            <div className="pf-providers">
              {account.providers.map((p) => (
                <span key={p} className="sc-chip">
                  {PROVIDER_LABEL[p] ?? p}
                </span>
              ))}
            </div>
            {state.nicknameEdit.phase === 'error' && (
              <p className="sc-alert" role="alert">
                {NICKNAME_ERROR[state.nicknameEdit.errorKind ?? 'unknown'] ?? NICKNAME_ERROR.unknown}
              </p>
            )}
          </div>
        </section>

        <section className="sc-card pf-wallet">
          <h2 className="sc-section-title">보유 코인</h2>
          {kind !== 'member' ? (
            <p className="sc-note">게스트는 코인을 보유하지 않습니다.</p>
          ) : walletQuery.isLoading ? (
            <p className="sc-note">불러오는 중...</p>
          ) : walletQuery.isError ? (
            <p className="sc-alert">잔액을 불러오지 못했습니다.</p>
          ) : (
            <>
              <p className="pf-balance">{walletQuery.data?.balance.toLocaleString()} <span>코인</span></p>
              <p className="sc-note">매일 접속하면 자동으로 지급됩니다.</p>
            </>
          )}
        </section>
      </div>

      {/* 관리자에게만 보인다. 코인 내역 아래로 내리면 표 10여 줄에 묻혀 스크롤해야 찾는다 */}
      <AdminConsoleLink />

      {kind === 'member' && (
        <section className="sc-card pf-tx">
          <h2 className="sc-section-title">코인 사용 내역</h2>
          <TransactionsSection />
        </section>
      )}

      <section className="sc-card pf-danger">
        <h2 className="sc-section-title">계정</h2>
        {state.withdrawal.phase === 'error' && <p className="sc-alert">탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>}
        {(state.withdrawal.phase === 'idle' || state.withdrawal.phase === 'error') && (
          <button type="button" className="sc-btn" onClick={beginWithdrawal}>
            탈퇴하기
          </button>
        )}
        {state.withdrawal.phase === 'confirming' && (
          <div className="pf-confirm">
            <p className="sc-note">탈퇴하면 부스·프로젝트·보유 코인이 모두 사라지고 되돌릴 수 없습니다.</p>
            <div className="pf-confirm-actions">
              <button type="button" className="sc-btn" onClick={cancelWithdrawal}>
                취소
              </button>
              <button
                type="button"
                className="sc-btn pf-btn-danger"
                onClick={() => {
                  void confirmWithdrawal().then((ok) => {
                    if (ok) navigate('/login', { replace: true });
                  });
                }}
              >
                탈퇴 확인
              </button>
            </div>
          </div>
        )}
        {state.withdrawal.phase === 'submitting' && <p className="sc-note">탈퇴 처리 중...</p>}
      </section>
    </>
  );
}
