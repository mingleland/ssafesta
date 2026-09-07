// 앱 전체에서 공유할 컨텍스트(react-query 등)를 등록하는 지점
import { QueryClientProvider } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { bootstrapAuth } from '../../features/auth/model/bootstrap';
import { ScreenAudioController } from '../../features/audio/ui/ScreenAudioController';
import { installImageDragGuard } from '../../shared/ui/imageDragGuard';
import { ToastHost } from '../../shared/ui/toast/ToastHost';
import { queryClient } from './queryClient';

export function AppProviders({ children }: { children: ReactNode }) {
  // 401 인터셉트 등록 + 회원 세션 조용한 복원(T012, spec 001) — 앱 시작 시 1회
  useEffect(() => {
    void bootstrapAuth();
  }, []);

  // 이미지 유령 드래그 차단 — 근거와 Game Studio 예외는 imageDragGuard 에 있다
  useEffect(() => installImageDragGuard(), []);

  return (
    <QueryClientProvider client={queryClient}>
      {/* 화면 BGM 은 라우터 밖에 산다 — / → /login → /app/world 에서 끊기지 않게 (S15P21A604-463) */}
      <ScreenAudioController />
      {children}
      {/* 알림은 레이아웃 밖 공통층이다 — 라우터 밖에 둬야 화면이 바뀌어도 같은 자리에 뜬다 (S15P21A604-465) */}
      <ToastHost />
    </QueryClientProvider>
  );
}
