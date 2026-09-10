// Booth Studio 상단 툴바 — Reference 상단 행 그대로: 뒤로 · 제목 · 부스명 · 편집 상태 | 저장 · 게시 | 실행취소 · 다시실행 | 줌 · 설정
import type { SaveStatus } from '../../model/editorReducer';
import { IcBack, IcChevron, IcEdit, IcGear, IcPlay, IcRedo, IcSave, IcTrash, IcUndo } from './icons';

interface Props {
  boothName: string;
  saveStatus: SaveStatus;
  dirty: boolean;
  conflict: boolean;
  leaseExpired: boolean;
  canSave: boolean;
  canPublish: boolean;
  publishing: boolean;
  publishedVersion: number | null;
  zoomPercent: number;
  canUndo: boolean;
  canRedo: boolean;
  /** 배치를 비울 수 있는가 — 비어 있거나 임대가 끝났으면 누를 것이 없다 */
  canReset: boolean;
  onBack: () => void;
  onSave: () => void;
  onPublish: () => void;
  onZoomToggle: () => void;
  onUndo: () => void;
  onRedo: () => void;
  onReset: () => void;
}

function statusChip(s: SaveStatus, dirty: boolean, conflict: boolean, leaseExpired: boolean) {
  if (leaseExpired) return <span className="studio-chip studio-chip-warn">임대 만료</span>;
  if (conflict) return <span className="studio-chip studio-chip-warn">충돌 — 새로고침 필요</span>;
  if (s === 'saving') return <span className="studio-chip studio-chip-muted">저장 중…</span>;
  if (s === 'error') return <span className="studio-chip studio-chip-warn">저장 실패</span>;
  if (dirty) return <span className="studio-chip">편집 중</span>;
  if (s === 'saved') return <span className="studio-chip studio-chip-muted">저장됨</span>;
  return <span className="studio-chip studio-chip-muted">변경 없음</span>;
}

export function TopToolbar(p: Props) {
  return (
    <header className="studio-panel studio-toolbar">
      <button type="button" className="studio-toolbar-back" onClick={p.onBack} aria-label="부스 관리로 돌아가기">
        <IcBack size={20} />
      </button>
      <span className="studio-toolbar-title">Booth Studio</span>
      <span className="studio-toolbar-booth">
        {p.boothName}
        <IcEdit size={14} />
      </span>
      {statusChip(p.saveStatus, p.dirty, p.conflict, p.leaseExpired)}
      {p.publishedVersion !== null && <span className="studio-chip studio-chip-muted">공개 {p.publishedVersion}회차</span>}

      <span className="studio-toolbar-spacer" />

      <div className="studio-toolbar-group">
        <button type="button" className="studio-btn" onClick={p.onSave} disabled={!p.canSave}>
          <IcSave size={16} /> 저장
        </button>
        <button type="button" className="studio-btn studio-btn-primary" onClick={p.onPublish} disabled={!p.canPublish}>
          <IcPlay size={14} /> {p.publishing ? '게시 중…' : '게시'}
        </button>
      </div>
      <div className="studio-toolbar-group">
        {/* 전체 초기화 (S15P21A604-603) — 확인 다이얼로그는 '빈 부스' 템플릿 경로가 띄운다 */}
        <button
          type="button"
          className="studio-btn studio-btn-ghost"
          onClick={p.onReset}
          disabled={!p.canReset}
          title={p.canReset ? '배치를 모두 비웁니다' : '비울 배치가 없습니다'}
        >
          <IcTrash size={16} /> 초기화
        </button>
      </div>
      <div className="studio-toolbar-group">
        {/* 실행취소/다시실행 (S15P21A604-605) — editorReducer 의 스냅샷 스택이 판정한다 */}
        <button
          type="button"
          className="studio-btn studio-btn-ghost studio-btn-icon"
          onClick={p.onUndo}
          disabled={!p.canUndo}
          aria-label="실행 취소"
          title="실행 취소 (Ctrl+Z)"
        >
          <IcUndo size={18} />
        </button>
        <button
          type="button"
          className="studio-btn studio-btn-ghost studio-btn-icon"
          onClick={p.onRedo}
          disabled={!p.canRedo}
          aria-label="다시 실행"
          title="다시 실행 (Ctrl+Y)"
        >
          <IcRedo size={18} />
        </button>
      </div>
      <div className="studio-toolbar-group">
        <button type="button" className="studio-btn studio-btn-ghost" onClick={p.onZoomToggle}>
          {p.zoomPercent}% <IcChevron size={14} />
        </button>
        <button type="button" className="studio-btn studio-btn-ghost studio-btn-icon" disabled aria-label="보기 옵션"><IcGear size={18} /></button>
      </div>
    </header>
  );
}
