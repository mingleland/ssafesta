// 보유 코인 배지(spec 003 FR-001) — member 전용 조회. 일일 지급(50)은 서버 인터셉터가 인증 요청마다
// 자동 반영하므로 "지급 받기" 버튼이 없다. 임대 성공/실패 시 useLeaseSlot이 invalidate로 갱신한다.
import { useQuery } from '@tanstack/react-query';
import { useSession } from '../../auth/model/session';
import { walletApi } from '../../../entities/wallet/api.select';

export function WalletBadge() {
  const { kind } = useSession();
  const walletQuery = useQuery({
    queryKey: ['wallet-balance'],
    queryFn: walletApi.getWallet,
    enabled: kind === 'member', // 게스트는 403 — 요청 자체를 만들지 않는다
  });

  if (kind !== 'member' || walletQuery.data === undefined) return null;
  return <span>보유 코인: {walletQuery.data.balance}</span>;
}
