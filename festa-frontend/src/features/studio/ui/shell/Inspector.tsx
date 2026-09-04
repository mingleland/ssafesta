// 우측 Inspector 골격 — 헤더(선택 대상) + 스크롤 본문. 본문은 모드가 채운다.
import type { ReactNode } from 'react';
import type { ThumbKind } from '../../model/visualAssets';
import { IcLock } from './icons';

interface Props {
  title: string;
  thumb?: ThumbKind;
  locked?: boolean;
  children: ReactNode;
}

export function Inspector({ title, thumb, locked, children }: Props) {
  return (
    <aside className="studio-panel studio-inspector" aria-label="속성 패널">
      <div className="studio-inspector-head">
        {thumb && <span className="studio-thumb-art" data-kind={thumb} />}
        <span>{title}</span>
        <span className="studio-inspector-head-spacer" />
        {locked && <IcLock size={14} />}
      </div>
      <div className="studio-inspector-scroll">{children}</div>
    </aside>
  );
}

export function InspectorEmpty({ children }: { children: ReactNode }) {
  return <p className="studio-inspector-empty">{children}</p>;
}
