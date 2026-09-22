// 앱 최외곽 오류 경계 — 라우터가 잡을 수 없는 자리에서 난 오류의 최종 수용선.
//
// `RouterProvider` 의 errorElement 는 라우트 트리 **안** 만 덮는다. `AppProviders`·
// `PersistentWorld`·`ToastHost`·RouterProvider 자신이 던지면 React 가 트리를 통째로 언마운트해
// 흰 화면만 남는다. 그 구멍을 막는 것이 이 파일의 존재 이유 하나다.
//
// **아무것에도 기대지 않는다.** router·provider·toast·Unity·앱 상태·lazy 컴포넌트·CSS 파일 전부
// 쓰지 않고 inline style 로 그린다. 여기가 뜨는 상황은 그것들 중 하나가 이미 깨진 상황이라,
// fallback 이 깨진 것을 다시 import 하면 fallback 도 같이 죽는다.
//
// 조작도 브라우저 기본 동작만 쓴다 — navigate 는 라우터가 필요하다.
import { Component, type ErrorInfo, type ReactNode } from 'react';

const screen = {
  position: 'fixed',
  inset: 0,
  display: 'flex',
  flexDirection: 'column',
  alignItems: 'center',
  justifyContent: 'center',
  gap: '12px',
  padding: '24px',
  background: '#0f1430',
  color: '#eaeefb',
  fontFamily: 'system-ui, sans-serif',
  textAlign: 'center',
} as const;

const button = {
  height: '36px',
  padding: '0 15px',
  border: '1px solid rgba(255, 255, 255, 0.2)',
  borderRadius: '10px',
  background: 'rgba(255, 255, 255, 0.06)',
  color: '#eaeefb',
  fontFamily: 'inherit',
  fontSize: '13.5px',
  fontWeight: 600,
  cursor: 'pointer',
} as const;

export class RootFatalErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };

  static getDerivedStateFromError(): { failed: boolean } {
    return { failed: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // 화면에는 싣지 않는다 — 사용자가 할 수 있는 일이 없고 내부 구조만 드러난다.
    console.error('[root-fatal] 앱 최상위에서 복구하지 못한 오류 —', error, info.componentStack);
  }

  render(): ReactNode {
    if (!this.state.failed) return this.props.children;
    return (
      <div style={screen} role="alert">
        <strong style={{ fontSize: '16px' }}>화면을 표시하지 못했습니다</strong>
        <span style={{ color: '#98a0c0' }}>페이지를 새로 불러오면 대부분 해결됩니다.</span>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button type="button" style={button} onClick={() => window.location.reload()}>
            새로 불러오기
          </button>
          <button type="button" style={button} onClick={() => window.location.assign('/')}>
            처음 화면으로
          </button>
        </div>
      </div>
    );
  }
}
