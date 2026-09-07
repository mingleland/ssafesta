// 하단 상태 바 — 오브젝트 수 / 저장 상태 / 조작 도움말 (Shortcut 은 팀 계약이 아니라 목업 표기)
import type { SaveStatus } from '../../model/editorReducer';
import type { TransformTool } from '../../model/studioMode';
import { IcCube } from './icons';

interface Props {
  count: number;
  max: number;
  saveStatus: SaveStatus;
  dirty: boolean;
  tool: TransformTool;
  snap: boolean;
}

function saveText(s: SaveStatus, dirty: boolean) {
  if (s === 'saving') return '저장 중';
  if (s === 'error') return '저장 실패';
  if (dirty) return '저장되지 않은 변경';
  if (s === 'saved') return '방금 저장됨';
  return '변경 없음';
}

export function StatusBar({ count, max, saveStatus, dirty, tool, snap }: Props) {
  const help =
    tool === 'rotate'
      ? ['회전 핸들을 드래그하여 회전', snap ? '15° 스냅' : '자유 회전']
      : ['드래그하여 이동', snap ? '0.25m 스냅' : '스냅 해제', '클릭 : 선택'];
  return (
    <footer className="studio-panel studio-status">
      <span className="studio-status-item"><IcCube size={16} /> <strong>{count}</strong> / {max}</span>
      <span className="studio-status-item"><span className="studio-status-dot" data-state={saveStatus === 'saving' ? 'saving' : saveStatus === 'error' ? 'error' : dirty ? 'dirty' : 'saved'} />{saveText(saveStatus, dirty)}</span>
      <span className="studio-status-help">{help.map((h) => <span key={h}>{h}</span>)}</span>
    </footer>
  );
}
