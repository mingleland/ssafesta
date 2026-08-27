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
  readonly diagnosticId: string | null;
}

// 같은 원인으로 반복 실패할 때 사용자를 재시도 루프에 가두지 않기 위한 상한(S15P21A604-186).
// consecutiveFailures는 React state가 아니라 인스턴스 필드다 — 재시도(resetKey 변경) 사이에는
// 값이 유지되고, 사용자가 게임을 나갔다 다시 들어와 이 Boundary가 새로 mount되면 자연히 0으로
// 돌아간다(같은 세션에서만 누적되고 다음 시도에는 영향을 주지 않는다는 뜻).
const MAX_CONSECUTIVE_FAILURES = 3;

// 사용자 화면에는 원본 에러 메시지·스택을 절대 노출하지 않는다 — 문의용 짧은 진단 ID만 보여주고
// 실제 stack은 componentDidCatch에서 console에만 남긴다.
const createDiagnosticId = (): string => Math.random().toString(36).slice(2, 8).toUpperCase();

export class GameRuntimeErrorBoundary extends Component<
  GameRuntimeErrorBoundaryProps,
  GameRuntimeErrorBoundaryState
> {
  state: GameRuntimeErrorBoundaryState = { error: null, resetKey: this.props.resetKey, diagnosticId: null };
  private consecutiveFailures = 0;

  static getDerivedStateFromError(error: Error): Partial<GameRuntimeErrorBoundaryState> {
    return { error, diagnosticId: createDiagnosticId() };
  }

  static getDerivedStateFromProps(
    props: GameRuntimeErrorBoundaryProps,
    state: GameRuntimeErrorBoundaryState,
  ): Partial<GameRuntimeErrorBoundaryState> | null {
    return props.resetKey === state.resetKey ? null : { error: null, resetKey: props.resetKey, diagnosticId: null };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    this.consecutiveFailures += 1;
    console.error('[game-studio] Published Runtime 격리 오류', error, info.componentStack);
  }

  render(): ReactNode {
    if (this.state.error === null) return this.props.children;
    const canRetry = this.consecutiveFailures <= MAX_CONSECUTIVE_FAILURES;
    return (
      <main className="grp-loading" role="alert">
        <strong>게임 화면을 안전하게 종료했습니다</strong>
        <p>이 게임을 실행하는 중 문제가 발생했습니다. FESTA 월드와 다른 기능에는 영향을 주지 않습니다.</p>
        {this.state.diagnosticId !== null && <small>문의 코드: {this.state.diagnosticId}</small>}
        {!canRetry && <p>반복해서 같은 문제가 발생했습니다. 나가기를 이용해 주세요.</p>}
        <div>
          {canRetry && <button onClick={this.props.onRetry} type="button">다시 불러오기</button>}
          <button onClick={this.props.onExit} type="button">나가기</button>
        </div>
      </main>
    );
  }
}
