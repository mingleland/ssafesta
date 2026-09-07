// 캔버스 하단 중앙 변형 툴바 — 선택 · 이동 · 회전 · 프레임 | 스냅
import type { TransformTool } from '../../model/studioMode';
import { IcChevron, IcCursor, IcFrame, IcMagnet, IcMove, IcRotate } from './icons';

interface Props {
  tool: TransformTool;
  snap: boolean;
  onTool: (tool: TransformTool) => void;
  onSnapToggle: () => void;
  onFrame: () => void;
}

export function TransformBar({ tool, snap, onTool, onSnapToggle, onFrame }: Props) {
  return (
    <div className="studio-transformbar" role="toolbar" aria-label="변형 도구">
      <button type="button" className="studio-tool" aria-pressed={tool === 'select'} onClick={() => onTool('select')} aria-label="선택"><IcCursor size={18} /></button>
      <button type="button" className="studio-tool" aria-pressed={tool === 'move'} onClick={() => onTool('move')} aria-label="이동"><IcMove size={18} /></button>
      <button type="button" className="studio-tool" aria-pressed={tool === 'rotate'} onClick={() => onTool('rotate')} aria-label="회전"><IcRotate size={18} /></button>
      <button type="button" className="studio-tool" onClick={onFrame} aria-label="부스 전체 보기"><IcFrame size={18} /></button>
      <span className="studio-tool-sep" />
      <button type="button" className="studio-tool" aria-pressed={snap} onClick={onSnapToggle} aria-label="스냅"><IcMagnet size={18} /></button>
      <button type="button" className="studio-tool" disabled aria-label="스냅 옵션" style={{ width: 28 }}><IcChevron size={14} /></button>
    </div>
  );
}
