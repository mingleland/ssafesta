import { useEffect, useRef, useState } from 'react';
import { GAME_PROJECT_LIMITS, type Component, type GameObject, type GameProject, type WorldScene } from '../../contracts/gameProject.ts';
import { assetDisplayLabel, findBuiltinSpriteSheet, partitionAssetsByRole } from '../assets/builtinAssetCatalog.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import {
  addComponent,
  moveObject,
  objectRemovalReason,
  removeComponent,
  removeObject,
  renameObject,
  renameScene,
  replaceComponent,
  resizeWorldScene,
  setObjectNameVisible,
  setTopDownBackground,
  setObjectVisible,
} from '../model/authoringCommands.ts';
import { COMPONENT_LABELS, describeSceneRuntimeMode, findPresetDefinition } from '../model/authoringRegistry.ts';
import { AssetPickerModal } from './AssetPickerModal.tsx';
import { CommitInput } from './CommitInput.tsx';
import { NumberCommitInput } from './NumberCommitInput.tsx';
import { SpriteAnimationInspector } from './SpriteAnimationInspector.tsx';

interface InspectorPanelProps {
  readonly project: GameProject;
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly scene: WorldScene;
  readonly selectedObject: GameObject | null;
  readonly onApply: (project: GameProject) => void;
  readonly onObjectRemoved: () => void;
  readonly onReplaceSprite: (file: File) => void;
}

const COMPONENT_TYPES: readonly Component['type'][] = [
  'SPRITE', 'COLLIDER', 'INTERACTABLE', 'PICKUP', 'DAMAGE', 'HEALTH', 'SCORE_VALUE',
  'CHECKPOINT', 'AUTO_MOVE', 'SHOOTER', 'SPAWNER',
];

export const InspectorPanel = ({
  project,
  assetUrls,
  scene,
  selectedObject,
  onApply,
  onObjectRemoved,
  onReplaceSprite,
}: InspectorPanelProps) => {
  const [componentToAdd, setComponentToAdd] = useState<Component['type']>('SPRITE');
  const [advanced, setAdvanced] = useState(false);
  const [showAssetPicker, setShowAssetPicker] = useState(false);
  const [sceneSizeDraft, setSceneSizeDraft] = useState({ width: scene.width, height: scene.height });
  const replaceSpriteInput = useRef<HTMLInputElement>(null);
  const sceneSizeCells = Math.round(sceneSizeDraft.width) * Math.round(sceneSizeDraft.height);
  const sceneSizeTooLarge = sceneSizeCells > GAME_PROJECT_LIMITS.maxTileCellsPerLayer;

  useEffect(() => {
    setAdvanced(false);
    setShowAssetPicker(false);
  }, [selectedObject?.id]);

  useEffect(() => {
    setSceneSizeDraft({ width: scene.width, height: scene.height });
  }, [scene.height, scene.id, scene.width]);

  if (selectedObject === null) {
    return (
      <div className="gss-panel-stack">
        <div className="gss-panel-heading">
          <div>
            <span className="gss-eyebrow">SCENE</span>
            <h2>{scene.name}</h2>
          </div>
          <span className="gss-type-badge">{describeSceneRuntimeMode(scene.type)}</span>
        </div>
        <CommitInput
          label="Scene 이름"
          onCommit={(name) => onApply(renameScene(project, scene.id, name))}
          value={scene.name}
        />
        <label className="gss-field">
          <span>맵 배경 (선택)</span>
          <select
            onChange={(event) => onApply(setTopDownBackground(project, scene.id, event.target.value || undefined))}
            value={scene.backgroundAssetId ?? ''}
          >
            <option value="">배경 없음 · 타일만 사용</option>
            {/* S15P21A604-570 — 큐레이션된 빌트인과 내가 올린 이미지가 구분 없이 섞여 있던
                문제(Notion QA #53)를 optgroup으로 나눠서 고친다. */}
            {(() => {
              const { builtin, custom } = partitionAssetsByRole(project.assets, 'BACKGROUND');
              return (
                <>
                  {builtin.length > 0 && (
                    <optgroup label="제공 자료">
                      {builtin.map((asset) => <option key={asset.id} value={asset.id}>{assetDisplayLabel(asset)}</option>)}
                    </optgroup>
                  )}
                  {custom.length > 0 && (
                    <optgroup label="내 자산">
                      {custom.map((asset) => <option key={asset.id} value={asset.id}>{assetDisplayLabel(asset)}</option>)}
                    </optgroup>
                  )}
                </>
              );
            })()}
          </select>
        </label>
        <div className="gss-section-title"><span>맵 크기</span><small>오브젝트와 타일은 안전하게 유지됩니다</small></div>
        <div className="gss-field-row">
          <label className="gss-field">
            <span>가로 칸</span>
            <input
              max={scene.type === 'PLATFORMER' ? 200 : 100}
              min={scene.type === 'PLATFORMER' ? 8 : 4}
              onChange={(event) => setSceneSizeDraft((current) => ({ ...current, width: Number(event.target.value) }))}
              type="number"
              value={sceneSizeDraft.width}
            />
          </label>
          <label className="gss-field">
            <span>세로 칸</span>
            <input
              max={100}
              min={scene.type === 'PLATFORMER' ? 6 : 4}
              onChange={(event) => setSceneSizeDraft((current) => ({ ...current, height: Number(event.target.value) }))}
              type="number"
              value={sceneSizeDraft.height}
            />
          </label>
        </div>
        {sceneSizeTooLarge && (
          <p className="gss-field-warning" role="alert">
            현재 {sceneSizeCells.toLocaleString('ko-KR')}칸입니다. 저장 가능한 최대 크기는 {GAME_PROJECT_LIMITS.maxTileCellsPerLayer.toLocaleString('ko-KR')}칸입니다.
          </p>
        )}
        <button
          className="gss-secondary-wide"
          disabled={!Number.isFinite(sceneSizeDraft.width) || !Number.isFinite(sceneSizeDraft.height)
            || sceneSizeTooLarge
            || (sceneSizeDraft.width === scene.width && sceneSizeDraft.height === scene.height)}
          onClick={() => onApply(resizeWorldScene(project, scene.id, sceneSizeDraft.width, sceneSizeDraft.height))}
          type="button"
        >맵 크기 적용</button>
        <div className="gss-info-grid">
          <span>Scene ID</span><strong>{scene.id}</strong>
          <span>맵 크기</span><strong>{scene.width} × {scene.height}</strong>
          <span>오브젝트</span><strong>{scene.objects.length} / 500</strong>
          <span>이벤트</span><strong>{scene.events.length} / 300</strong>
        </div>
        <div className="gss-help-card">
          <strong>오브젝트를 선택하세요</strong>
          <p>왼쪽 팔레트에서 캔버스로 드래그하거나 배치 도구를 선택해 오브젝트를 추가할 수 있습니다.</p>
        </div>
      </div>
    );
  }

  const definition = findPresetDefinition(selectedObject.preset);
  const sprite = selectedObject.components.find((component) => component.type === 'SPRITE');
  const spriteAsset = sprite?.type === 'SPRITE'
    ? project.assets.find((asset) => asset.id === sprite.assetId)
    : undefined;
  const spriteSheet = spriteAsset === undefined ? undefined : findBuiltinSpriteSheet(spriteAsset.source);
  const staticSprite = resolveStaticImageVisual(spriteAsset, assetUrls);
  const usedTypes = new Set(selectedObject.components.map((component) => component.type));
  const removableReason = objectRemovalReason(project, selectedObject.id);

  const replace = (component: Component) => onApply(
    replaceComponent(project, scene.id, selectedObject.id, component),
  );

  return (
    <div className="gss-panel-stack">
      <div className="gss-panel-heading">
        <div>
          <span className="gss-eyebrow">OBJECT</span>
          <h2>{definition.label}</h2>
        </div>
        <button className="gss-mode-toggle" onClick={() => setAdvanced((current) => !current)} type="button">
          {advanced ? '간단 설정으로' : '고급 설정'}
        </button>
      </div>
      <div className="gss-help-card gss-help-card--compact">
        <strong>{advanced ? '위치·구성 요소까지 직접 조정합니다' : '모습과 동작만 바꾸면 바로 플레이할 수 있습니다'}</strong>
        <p>{advanced ? 'ID와 좌표, 표시 순서, 구성 요소 추가·삭제를 사용할 수 있습니다.' : '세부 좌표와 기술 설정은 숨겨 두었습니다. 필요할 때만 고급 설정을 여세요.'}</p>
      </div>
      {/* S15P21A604-529 — PLAYER_SPAWN은 플레이 중 화면에 렌더링되지 않는 마커라(플레이어
          캐릭터는 캔버스 툴바/런타임에서 별도로 다룸) 이름을 지어도 보여줄 대상이 없다 —
          이 프리셋만 이름/표시 설정 UI 자체를 아예 노출하지 않는다. */}
      {selectedObject.preset !== 'PLAYER_SPAWN' && (
        <>
          <CommitInput
            allowEmpty
            label="오브젝트 이름"
            onCommit={(name) => onApply(renameObject(project, scene.id, selectedObject.id, name))}
            value={selectedObject.name ?? ''}
          />
          <label className="gss-check-row">
            <input
              checked={selectedObject.showNameInPlay === true}
              onChange={(event) => onApply(setObjectNameVisible(
                project,
                scene.id,
                selectedObject.id,
                event.target.checked,
              ))}
              type="checkbox"
            />
            플레이 중 이름 표시
          </label>
        </>
      )}
      {advanced && <div className="gss-id-chip">오브젝트 ID · {selectedObject.id}</div>}
      {advanced && <div className="gss-field-row">
        <label className="gss-field">
          <span>X</span>
          <input
            max={scene.width - 1}
            min={0}
            onChange={(event) => onApply(moveObject(
              project,
              scene.id,
              selectedObject.id,
              Number(event.target.value),
              selectedObject.position.y,
            ))}
            type="number"
            value={selectedObject.position.x}
          />
        </label>
        <label className="gss-field">
          <span>Y</span>
          <input
            max={scene.height - 1}
            min={0}
            onChange={(event) => onApply(moveObject(
              project,
              scene.id,
              selectedObject.id,
              selectedObject.position.x,
              Number(event.target.value),
            ))}
            type="number"
            value={selectedObject.position.y}
          />
        </label>
      </div>}
      <label className="gss-check-row">
        <input
          checked={selectedObject.visible}
          onChange={(event) => onApply(setObjectVisible(
            project,
            scene.id,
            selectedObject.id,
            event.target.checked,
          ))}
          type="checkbox"
        />
        게임 시작 시 표시
      </label>

      <div className="gss-section-title">
        <span>{advanced ? '구성 요소' : '모습과 동작'}</span>
        {advanced && <small>{selectedObject.components.length}/10</small>}
      </div>
      {selectedObject.components.length === 0 && (
        <div className="gss-empty-inline">고급 설정에서 동작을 추가할 수 있습니다.</div>
      )}
      {selectedObject.components.map((component) => (
        <article className="gss-component-card" key={component.type}>
          <header>
            <strong>{COMPONENT_LABELS[component.type]}</strong>
            {advanced && <button
              aria-label={`${COMPONENT_LABELS[component.type]} 구성 요소 제거`}
              className="gss-icon-button"
              onClick={() => onApply(removeComponent(project, scene.id, selectedObject.id, component.type))}
              type="button"
            >×</button>}
          </header>
          {component.type === 'SPRITE' && (
            <>
              {spriteSheet !== undefined && (
                <SpriteAnimationInspector sheet={spriteSheet} />
              )}
              {spriteSheet === undefined && staticSprite !== null && (
                <div className="gss-custom-asset-preview"><span aria-label="이미지 미리보기" role="img" style={staticImageBackgroundStyle(staticSprite)} /></div>
              )}
              <button className="gss-open-asset-picker" onClick={() => setShowAssetPicker(true)} type="button">
                <span>현재 모습</span>
                <strong>{spriteAsset === undefined ? '이미지 선택' : assetDisplayLabel(spriteAsset)}</strong>
                <em>재료함에서 바꾸기</em>
              </button>
              <div className={advanced ? 'gss-field-row' : ''}>
                <label className="gss-field">
                  <span>보이는 크기 · {component.scale ?? 100}%</span>
                  <input aria-label="오브젝트 크기"
                    max={400}
                    min={25}
                    onChange={(event) => replace({ ...component, scale: Number(event.target.value) })}
                    step={5}
                    type="range"
                    value={component.scale ?? 100}
                  />
                </label>
                {advanced && <label className="gss-field">
                  <span>표시 순서</span>
                  <input
                    max={20}
                    min={0}
                    onChange={(event) => replace({ ...component, zIndex: Number(event.target.value) })}
                    type="number"
                    value={component.zIndex ?? 2}
                  />
                </label>}
              </div>
              <button className="gss-replace-asset-button" onClick={() => replaceSpriteInput.current?.click()} type="button">내 이미지로 이 오브젝트만 바꾸기</button>
              <input
                accept="image/png,image/jpeg,image/gif,image/webp"
                hidden
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  event.target.value = '';
                  if (file !== undefined) onReplaceSprite(file);
                }}
                ref={replaceSpriteInput}
                type="file"
              />
            </>
          )}
          {component.type === 'COLLIDER' && (
            <label className="gss-check-row">
              <input
                checked={component.solid}
                onChange={(event) => replace({ type: 'COLLIDER', solid: event.target.checked })}
                type="checkbox"
              />
              플레이어 이동 막기
            </label>
          )}
          {component.type === 'INTERACTABLE' && (
            <>
              {/* S15P21A604-534 — {...component, prompt}로 합성해야 range를 같이
                  들고 있는다. {type:'INTERACTABLE', prompt}처럼 새로 만들면 이미
                  설정해 둔 range가 문구를 고칠 때마다 조용히 사라진다. */}
              <CommitInput
                label="상호작용 안내 문구"
                onCommit={(prompt) => replace({ ...component, prompt })}
                value={component.prompt}
              />
              <NumberCommitInput
                label="상호작용 거리"
                max={100}
                min={1}
                onCommit={(range) => replace({ ...component, range })}
                value={component.range ?? 1}
              />
            </>
          )}
          {component.type === 'PICKUP' && (
            <label className="gss-field">
              <span>획득 아이템</span>
              <select
                onChange={(event) => replace({ type: 'PICKUP', itemId: event.target.value })}
                value={component.itemId}
              >
                {project.items.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}
              </select>
            </label>
          )}
          {component.type === 'DAMAGE' && <NumberCommitInput label="닿을 때 피해" max={999} min={1} onCommit={(amount) => replace({ ...component, amount })} value={component.amount} />}
          {component.type === 'HEALTH' && <NumberCommitInput label="최대 체력" max={9999} min={1} onCommit={(max) => replace({ ...component, max })} value={component.max} />}
          {component.type === 'SCORE_VALUE' && <label className="gss-field"><span>획득 점수</span><input min={-999999} max={999999} onChange={(event) => replace({ ...component, value: Number(event.target.value) })} type="number" value={component.value} /></label>}
          {component.type === 'CHECKPOINT' && <div className="gss-empty-inline">플레이어가 닿으면 다시 시작할 위치로 저장합니다.</div>}
          {component.type === 'AUTO_MOVE' && (
            <div className="gss-field-row">
              <label className="gss-field"><span>이동 방향</span><select onChange={(event) => replace({ ...component, axis: event.target.value as 'HORIZONTAL' | 'VERTICAL' })} value={component.axis}><option value="HORIZONTAL">좌우</option><option value="VERTICAL">상하</option></select></label>
              <NumberCommitInput label="이동 범위" max={100} min={1} onCommit={(range) => replace({ ...component, range })} value={component.range} />
            </div>
          )}
          {component.type === 'SHOOTER' && (
            <div className="gss-field-row">
              <NumberCommitInput label="피해량" max={999} min={1} onCommit={(damage) => replace({ ...component, damage })} value={component.damage} />
              <NumberCommitInput label="발사 간격 ms" max={10000} min={100} onCommit={(cooldownMs) => replace({ ...component, cooldownMs })} value={component.cooldownMs} />
            </div>
          )}
          {component.type === 'SPAWNER' && (
            <div className="gss-field-row">
              <NumberCommitInput label="생성 간격 ms" max={60000} min={250} onCommit={(intervalMs) => replace({ ...component, intervalMs })} value={component.intervalMs} />
              <NumberCommitInput label="동시 최대" max={100} min={1} onCommit={(maxAlive) => replace({ ...component, maxAlive })} value={component.maxAlive} />
            </div>
          )}
        </article>
      ))}
      {advanced && <div className="gss-inline-actions">
        <select onChange={(event) => setComponentToAdd(event.target.value as Component['type'])} value={componentToAdd}>
          {COMPONENT_TYPES.map((type) => (
            <option disabled={usedTypes.has(type)} key={type} value={type}>+ {COMPONENT_LABELS[type]}</option>
          ))}
        </select>
        <button
          disabled={usedTypes.has(componentToAdd) || selectedObject.components.length >= 10}
          onClick={() => onApply(addComponent(project, scene.id, selectedObject.id, componentToAdd))}
          type="button"
        >추가</button>
      </div>}
      {advanced && <button
        className="gss-danger-button"
        disabled={removableReason !== null}
        onClick={() => {
          onApply(removeObject(project, scene.id, selectedObject.id));
          onObjectRemoved();
        }}
        title={removableReason ?? '오브젝트 삭제'}
        type="button"
      >오브젝트 삭제</button>}
      {showAssetPicker && sprite?.type === 'SPRITE' && (
        <AssetPickerModal
          assetUrls={assetUrls}
          currentAssetId={sprite.assetId}
          onClose={() => setShowAssetPicker(false)}
          onSelect={(assetId) => replace({ ...sprite, assetId })}
          onUpload={onReplaceSprite}
          project={project}
        />
      )}
    </div>
  );
};
