import { useMemo, useRef, useState } from 'react';
import type { GameProject } from '../../contracts/gameProject.ts';
import {
  assetDisplayLabel,
  findBuiltinSpriteSheet,
} from '../assets/builtinAssetCatalog.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { filterSpriteAssets, spriteAssetGroup, type SpriteAssetGroup } from '../assets/spriteAssetFilter.ts';
import { SpriteAnimationPreview } from './SpriteAnimationPreview.tsx';

const GROUP_LABELS: Readonly<Record<SpriteAssetGroup, string>> = {
  ALL: '전체',
  CHARACTER: '캐릭터',
  OBJECT: '사물·장식',
  MY: '내 이미지',
};

interface AssetPickerModalProps {
  readonly project: GameProject;
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly currentAssetId: string;
  readonly onClose: () => void;
  readonly onSelect: (assetId: string) => void;
  readonly onUpload: (file: File) => void;
}

export const AssetPickerModal = ({
  project,
  assetUrls,
  currentAssetId,
  onClose,
  onSelect,
  onUpload,
}: AssetPickerModalProps) => {
  const [group, setGroup] = useState<SpriteAssetGroup>('ALL');
  const [query, setQuery] = useState('');
  const uploadInput = useRef<HTMLInputElement>(null);
  const assets = useMemo(
    () => filterSpriteAssets(project.assets, group, query),
    [group, project.assets, query],
  );

  return (
    <div className="gss-asset-picker-backdrop" onMouseDown={onClose} role="presentation">
      <section aria-label="오브젝트 이미지 재료함" aria-modal="true" className="gss-asset-picker" onMouseDown={(event) => event.stopPropagation()} role="dialog">
        <header>
          <div>
            <span className="gss-eyebrow">MATERIAL LIBRARY</span>
            <h2>어떤 모습으로 바꿀까요?</h2>
            <p>제공 재료는 바로 사용할 수 있고, 바꾸고 싶은 오브젝트만 내 이미지를 올릴 수 있습니다.</p>
          </div>
          <button aria-label="재료함 닫기" onClick={onClose} type="button">×</button>
        </header>
        <div className="gss-asset-picker-tools">
          <nav aria-label="이미지 종류">
            {(Object.keys(GROUP_LABELS) as SpriteAssetGroup[]).map((candidate) => (
              <button className={group === candidate ? 'is-active' : ''} key={candidate} onClick={() => setGroup(candidate)} type="button">
                {GROUP_LABELS[candidate]}
              </button>
            ))}
          </nav>
          <input aria-label="재료 검색" onChange={(event) => setQuery(event.target.value)} placeholder="예: 문, 보물, NPC" type="search" value={query} />
          <button className="gss-asset-upload" onClick={() => uploadInput.current?.click()} type="button">내 이미지 추가</button>
          <input
            accept="image/png,image/jpeg,image/gif,image/webp"
            hidden
            onChange={(event) => {
              const file = event.target.files?.[0];
              event.target.value = '';
              if (file !== undefined) {
                onUpload(file);
                onClose();
              }
            }}
            ref={uploadInput}
            type="file"
          />
        </div>
        <div className="gss-asset-grid">
          {assets.map((asset) => {
            const sheet = findBuiltinSpriteSheet(asset.source);
            const visual = resolveStaticImageVisual(asset, assetUrls);
            const selected = asset.id === currentAssetId;
            return (
              <button aria-pressed={selected} className={selected ? 'is-selected' : ''} key={asset.id} onClick={() => { onSelect(asset.id); onClose(); }} type="button">
                <span className="gss-asset-grid-preview">
                  {sheet !== undefined && <SpriteAnimationPreview sheet={sheet} size={64} />}
                  {sheet === undefined && visual !== null && <i style={staticImageBackgroundStyle(visual)} />}
                </span>
                <strong>{assetDisplayLabel(asset)}</strong>
                <small>{spriteAssetGroup(asset) === 'MY' ? '내 이미지' : '바로 사용 가능'}</small>
              </button>
            );
          })}
          {assets.length === 0 && (
            <div className="gss-asset-empty"><strong>검색 결과가 없습니다</strong><p>다른 검색어를 입력하거나 내 이미지를 추가해 보세요.</p></div>
          )}
        </div>
        <footer><span>{assets.length}개 재료</span><button onClick={onClose} type="button">닫기</button></footer>
      </section>
    </div>
  );
};
