// 앱 전체에서 공유할 컨텍스트(react-query 등)를 등록하는 지점
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { bootstrapAuth } from '../../features/auth/model/bootstrap';

const queryClient = new QueryClient();

export function AppProviders({ children }: { children: ReactNode }) {
  // 401 인터셉트 등록 + 회원 세션 조용한 복원(T012, spec 001) — 앱 시작 시 1회
  useEffect(() => {
    void bootstrapAuth();
  }, []);

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
