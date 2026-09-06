// 앱 전체에서 공유할 컨텍스트(react-query 등)를 등록하는 지점
import { QueryClientProvider } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { bootstrapAuth } from '../../features/auth/model/bootstrap';
import { ScreenAudioController } from '../../features/audio/ui/ScreenAudioController';
import { queryClient } from './queryClient';

export function AppProviders({ children }: { children: ReactNode }) {
  // 401 인터셉트 등록 + 회원 세션 조용한 복원(T012, spec 001) — 앱 시작 시 1회
  useEffect(() => {
    void bootstrapAuth();
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      {/* 화면 BGM 은 라우터 밖에 산다 — / → /login → /app/world 에서 끊기지 않게 (S15P21A604-463) */}
      <ScreenAudioController />
      {children}
    </QueryClientProvider>
  );
}
