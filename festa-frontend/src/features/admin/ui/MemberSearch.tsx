// 회원 검색 — 회원 관리와 지갑 관리가 같은 검색으로 대상을 고른다.
// BE 검색 API 는 아직 없다([FE contract]) — real 에서는 404 로 떨어져 오류 배너가 뜨고, mock 에서는 닉네임·번호로 찾는다.
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import { Empty, ErrorBanner, Loading, Pager, StatusChip } from './common';
import { Tooltip } from '../../../shared/ui/tooltip/Tooltip';

const PAGE_SIZE = 10;

export function MemberSearch({ selectedUserId, onSelect }: { selectedUserId: number | null; onSelect: (userId: number) => void }) {
  const [draft, setDraft] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const result = useQuery({
    queryKey: ['admin', 'members', query, page],
    queryFn: () => adminApi.searchMembers(query, page, PAGE_SIZE),
  });

  return (
    <section className="sc-card ad-work">
      <form
        className="ad-toolbar"
        role="search"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setQuery(draft.trim());
        }}
      >
        <input aria-label="회원 검색" placeholder="닉네임 또는 회원 번호" value={draft} onChange={(e) => setDraft(e.target.value)} />
        <button type="submit" className="sc-btn sc-btn-primary">검색</button>
        <Tooltip content="BE 검색 API 미도달 — FE 계약 기준">
          <span className="ad-badge-fe">FE 계약</span>
        </Tooltip>
      </form>
      {result.isPending && <Loading label="회원을 찾는 중..." />}
      {result.isError && <ErrorBanner error={result.error} onRetry={() => void result.refetch()} />}
      {result.isSuccess && result.data.content.length === 0 && <Empty title="검색 결과가 없습니다" hint="닉네임 일부나 회원 번호로 다시 찾아 보세요." />}
      {result.isSuccess && result.data.content.length > 0 && (
        <>
          <table className="ad-table">
            <thead><tr><th>번호</th><th>닉네임</th><th>상태</th><th>권한</th></tr></thead>
            <tbody>
              {result.data.content.map((m) => (
                <tr key={m.userId} data-selectable="" aria-selected={m.userId === selectedUserId} onClick={() => onSelect(m.userId)}>
                  <td className="num">{m.userId}</td>
                  <td>{m.nickname}</td>
                  <td><StatusChip status={m.status} /></td>
                  <td>{m.master ? <span className="ad-chip ad-chip-master">마스터</span> : m.admin ? <span className="ad-chip ad-chip-gold">관리자</span> : <span className="ad-muted">회원</span>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager page={result.data.page} totalPages={result.data.totalPages} onChange={setPage} />
        </>
      )}
    </section>
  );
}

