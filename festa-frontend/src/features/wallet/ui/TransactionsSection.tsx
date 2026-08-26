// 코인 거래 내역(spec 003 US3, P1) — offset page 탐색. 모르는 reasonType도 원문 코드로
// 표시한다(숨기면 SC-005 위반). 정렬은 서버 고정이라 정렬 UI가 없다.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { walletApi } from '../../../entities/wallet/api.select';
import { labelForReason } from '../../../entities/wallet/types';

export function TransactionsSection() {
  const [page, setPage] = useState(0);
  const txQuery = useQuery({
    queryKey: ['wallet-transactions', page],
    queryFn: () => walletApi.getTransactions(page),
  });

  if (txQuery.isLoading) return <p>불러오는 중...</p>;
  if (txQuery.isError || !txQuery.data) return <p>거래 내역을 불러오지 못했습니다.</p>;

  const { content, totalPages } = txQuery.data;

  return (
    <div>
      <table>
        <thead>
          <tr>
            <th>일시</th>
            <th>내역</th>
            <th>금액</th>
            <th>잔액</th>
          </tr>
        </thead>
        <tbody>
          {content.map((t) => (
            <tr key={t.id}>
              <td>
                {new Intl.DateTimeFormat('ko-KR', {
                  timeZone: 'Asia/Seoul',
                  dateStyle: 'short',
                  timeStyle: 'short',
                }).format(new Date(t.createdAt))}
              </td>
              <td>{labelForReason(t.reasonType)}</td>
              <td>{t.amount > 0 ? `+${t.amount}` : t.amount}</td>
              <td>{t.balanceAfter}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <button type="button" onClick={() => setPage((p) => p - 1)} disabled={page === 0}>
        이전
      </button>
      <button
        type="button"
        onClick={() => setPage((p) => p + 1)}
        disabled={page >= totalPages - 1}
      >
        다음
      </button>
    </div>
  );
}
