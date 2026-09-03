// My Info — /app/profile (S15P21A604-406). ESC Game Menu 의 Profile Summary 에서 진입한다.
// Profile(닉네임·provider·탈퇴)과 Wallet(잔액·거래내역)을 하나의 개인 Context 로 묶는다(D-08) —
// Wallet 은 독립 최상위 화면이 아니고, 전체 거래내역이 있는 유일한 자리다.
// 데이터는 features/profile/model/profile 상태 기계(-367)와 wallet 쿼리를 그대로 소비한다.
// 관리 콘솔이 아니라 "게임 속 내 정보"다 — 통계·대시보드를 만들지 않는다.
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
} from '../../features/profile/model/profile';
import { walletApi } from '../../entities/wallet/api.select';
import { useSession } from '../../features/auth/model/session';
import { TransactionsSection } from '../../features/wallet/ui/TransactionsSection';
import { PageShell, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import './profilePage.css';

const PROVIDER_LABEL: Record<string, string> = { google: 'Google', kakao: 'Kakao', ssafy: 'SSAFY', guest: '게스트' };

const NICKNAME_ERROR: Record<string, string> = {
  duplicate: '이미 사용 중인 닉네임입니다.',
  forbidden: '사용할 수 없는 닉네임입니다.',
  format: '닉네임 형식이 올바르지 않습니다.',
  unknown: '닉네임을 변경하지 못했습니다.',
};

export function ProfilePage() {
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
    return (
      <PageShell title="내 정보" backTo="/app/world">
        <ScreenLoading label="계정을 불러오는 중..." />
      </PageShell>
    );
  }
  if (state.status === 'error' || state.account === null) {
    return (
      <PageShell title="내 정보" backTo="/app/world">
        <ScreenError title="계정을 불러오지 못했습니다" message="잠시 후 다시 시도해 주세요." onRetry={() => void loadProfile()} />
      </PageShell>
    );
  }

  const account = state.account;
  const submitting = state.nicknameEdit.phase === 'submitting';

  return (
    <PageShell title="내 정보" subtitle="축제에서 쓰는 내 프로필과 보유 자산" backTo="/app/world">
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

      {kind === 'member' && (
        <section className="sc-card pf-tx">
          <h2 className="sc-section-title">코인 사용 내역</h2>
          <TransactionsSection />
        </section>
      )}

      <section className="sc-card pf-danger">
        <h2 className="sc-section-title">계정</h2>
        {state.withdrawal.phase === 'idle' && (
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
                  void confirmWithdrawal().then(() => navigate('/login', { replace: true }));
                }}
              >
                탈퇴 확인
              </button>
            </div>
          </div>
        )}
        {state.withdrawal.phase === 'submitting' && <p className="sc-note">탈퇴 처리 중...</p>}
        {state.withdrawal.phase === 'error' && <p className="sc-alert">탈퇴하지 못했습니다. 잠시 후 다시 시도해 주세요.</p>}
      </section>
    </PageShell>
  );
}
