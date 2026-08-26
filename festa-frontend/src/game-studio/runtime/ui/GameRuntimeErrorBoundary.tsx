import { Component, type ErrorInfo, type ReactNode } from 'react';

interface GameRuntimeErrorBoundaryProps {
  readonly children: ReactNode;
  readonly onExit: () => void;
  readonly onRetry: () => void;
  readonly resetKey: string;
}

interface GameRuntimeErrorBoundaryState {
  readonly error: Error | null;
  readonly resetKey: string;
}

export class GameRuntimeErrorBoundary extends Component<
  GameRuntimeErrorBoundaryProps,
  GameRuntimeErrorBoundaryState
> {
  state: GameRuntimeErrorBoundaryState = { error: null, resetKey: this.props.resetKey };

  static getDerivedStateFromError(error: Error): Partial<GameRuntimeErrorBoundaryState> {
    return { error };
  }

  static getDerivedStateFromProps(
    props: GameRuntimeErrorBoundaryProps,
    state: GameRuntimeErrorBoundaryState,
  ): Partial<GameRuntimeErrorBoundaryState> | null {
    return props.resetKey === state.resetKey ? null : { error: null, resetKey: props.resetKey };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    console.error('[game-studio] Published Runtime 격리 오류', error, info.componentStack);
  }

  render(): ReactNode {
    if (this.state.error === null) return this.props.children;
    return (
      <main className="grp-loading" role="alert">
        <strong>게임 화면을 안전하게 종료했습니다</strong>
        <p>이 게임을 실행하는 중 문제가 발생했습니다. FESTA 월드와 다른 기능에는 영향을 주지 않습니다.</p>
        <div>
          <button onClick={this.props.onRetry} type="button">다시 불러오기</button>
          <button onClick={this.props.onExit} type="button">나가기</button>
        </div>
      </main>
    );
  }
}
