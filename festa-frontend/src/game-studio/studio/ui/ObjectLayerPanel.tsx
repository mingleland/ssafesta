import { useMemo, useState } from 'react';
import type { GameObject, WorldScene } from '../../contracts/gameProject.ts';
import { findPresetDefinition } from '../model/authoringRegistry.ts';

interface ObjectLayerPanelProps {
  readonly scene: WorldScene;
  readonly selectedObjectId: string | null;
  readonly hiddenObjectIds: ReadonlySet<string>;
  readonly lockedObjectIds: ReadonlySet<string>;
  readonly onClose: () => void;
  readonly onSelect: (objectId: string) => void;
  readonly onToggleHidden: (objectId: string) => void;
  readonly onToggleLocked: (objectId: string) => void;
  readonly onChangeZIndex: (object: GameObject, zIndex: number) => void;
}

const objectZIndex = (object: GameObject): number => {
  const sprite = object.components.find((component) => component.type === 'SPRITE');
  return sprite?.type === 'SPRITE' ? sprite.zIndex ?? 2 : 2;
};

export const ObjectLayerPanel = ({
  scene,
  selectedObjectId,
  hiddenObjectIds,
  lockedObjectIds,
  onClose,
  onSelect,
  onToggleHidden,
  onToggleLocked,
  onChangeZIndex,
}: ObjectLayerPanelProps) => {
  const [search, setSearch] = useState('');
  const objects = useMemo(() => {
    const query = search.trim().toLowerCase();
    return scene.objects
      .filter((object) => {
        const definition = findPresetDefinition(object.preset);
        return query === '' || `${object.id} ${definition.label}`.toLowerCase().includes(query);
      })
      .map((object, index) => ({ object, index, zIndex: objectZIndex(object) }))
      .sort((left, right) => right.zIndex - left.zIndex || right.index - left.index);
  }, [scene.objects, search]);

  return (
    <aside aria-label="오브젝트 레이어" className="gss-layer-panel">
      <header>
        <div><span>OBJECT LAYERS</span><strong>{scene.objects.length}개 오브젝트</strong></div>
        <button aria-label="레이어 패널 닫기" onClick={onClose} type="button">×</button>
      </header>
      <input
        aria-label="배치된 오브젝트 검색"
        onChange={(event) => setSearch(event.target.value)}
        placeholder="ID 또는 종류로 찾기"
        value={search}
      />
      <p>눈은 편집 화면에서만 숨기고, 자물쇠는 실수로 움직이지 않게 합니다. 숫자는 게임 화면의 앞뒤 순서입니다.</p>
      <div className="gss-layer-list">
        {objects.map(({ object, zIndex }) => {
          const definition = findPresetDefinition(object.preset);
          const hidden = hiddenObjectIds.has(object.id);
          const locked = lockedObjectIds.has(object.id);
          const hasSprite = object.components.some((component) => component.type === 'SPRITE');
          return (
            <article className={`${selectedObjectId === object.id ? 'is-selected' : ''}${hidden ? ' is-hidden' : ''}`} key={object.id}>
              <button
                aria-label={`${definition.label} ${object.id} 선택`}
                className="gss-layer-select"
                onClick={() => onSelect(object.id)}
                type="button"
              ><span aria-hidden="true">{definition.icon}</span><div><strong>{definition.label}</strong><small>{object.id}</small></div></button>
              <div className="gss-layer-order" aria-label={`${object.id} 표시 순서 ${zIndex}`}>
                <button aria-label={`${object.id} 뒤로 보내기`} disabled={!hasSprite || zIndex <= 0} onClick={() => onChangeZIndex(object, zIndex - 1)} title="뒤로" type="button">−</button>
                <b>{zIndex}</b>
                <button aria-label={`${object.id} 앞으로 가져오기`} disabled={!hasSprite || zIndex >= 20} onClick={() => onChangeZIndex(object, zIndex + 1)} title="앞으로" type="button">＋</button>
              </div>
              <button
                aria-label={`${object.id} ${locked ? '잠금 해제' : '편집 잠금'}`}
                className={locked ? 'is-active' : ''}
                onClick={() => onToggleLocked(object.id)}
                title={locked ? '잠금 해제' : '편집 잠금'}
                type="button"
              >{locked ? '▣' : '▢'}</button>
              <button
                aria-label={`${object.id} ${hidden ? '편집 화면에 표시' : '편집 화면에서 숨기기'}`}
                className={hidden ? 'is-active' : ''}
                onClick={() => onToggleHidden(object.id)}
                title={hidden ? '편집 화면에 표시' : '편집 화면에서 숨기기'}
                type="button"
              >{hidden ? '○' : '●'}</button>
            </article>
          );
        })}
        {objects.length === 0 && <div className="gss-empty-inline">검색 결과가 없습니다.</div>}
      </div>
    </aside>
  );
};
