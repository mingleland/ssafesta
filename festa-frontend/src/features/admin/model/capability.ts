// 내가 관리자인가 — 콘솔 진입 가드와 진입점 링크가 같은 질의를 공유한다.
// 답은 UX 보조다: 최종 판정은 BE AdminGuard 가 매 요청 DB 를 보고 한다. 여기서 admin=true 라도 서버가
// 403 을 주면 그것이 정답이고, 화면은 FORBIDDEN 안내로 내려간다.
import { useQuery } from '@tanstack/react-query';
import { adminApi } from '../../../entities/admin/api.select';
import type { AdminCapability } from '../../../entities/admin/types';
import { useSession } from '../../auth/model/session';

export const ADMIN_CAPABILITY_KEY = ['admin', 'capability'] as const;

export function useAdminCapability() {
  const { kind, bootstrapped } = useSession();
  return useQuery<AdminCapability>({
    queryKey: ADMIN_CAPABILITY_KEY,
    queryFn: () => adminApi.getCapability(),
    // 회원이 아니면 묻지 않는다 — 게스트·비로그인은 /users/me 자체가 403·401 이다
    enabled: bootstrapped && kind === 'member',
    staleTime: 60_000,
  });
}

