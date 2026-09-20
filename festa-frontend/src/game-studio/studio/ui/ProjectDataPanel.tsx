import { useEffect, useRef, useState } from 'react';
import {
  DEFAULT_GAME_RULES,
  estimateGameProjectJsonBytes,
  GAME_PROJECT_LIMITS,
  type AssetReference,
  type GameObjective,
  type GameObjectiveType,
  type GameProject,
  type Scalar,
  type VariableDefinition,
} from '../../contracts/gameProject.ts';
import {
  addBooleanVariable,
  addIntegerVariable,
  addStringVariable,
  addItemDefinition,
  renameItemDefinition,
  replaceGameRules,
  replaceVariableDefinition,
} from '../model/authoringCommands.ts';
import { CommitInput } from './CommitInput.tsx';
import { assetDisplayLabel, BUILTIN_STATIC_IMAGES, BUILTIN_TILESETS } from '../assets/builtinAssetCatalog.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { resolveTilesetVisual, tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import { findAssetUsageLocations, findVariableUsageLocations, findItemUsageLocations, findPublishBlockers } from '../ports/publishValidation.ts';
import { analyzeProjectHealth } from '../model/projectHealth.ts';

interface ProjectDataPanelProps {
  readonly project: GameProject;
  // S15P21A604-573 — 자산 목록이 실제 이미지를 보여주려면 blob URL로 해석된 assetUrls가
  // 필요하다. GameStudioShell.tsx는 이미 useResolvedAssetUrls로 이걸 계산해서
  // InspectorPanel/DialogueEditor엔 넘기면서 이 패널에는 안 넘기고 있었다 — 그 배선 누락.
  // optional로 둔다 — 없거나(테스트) 아직 해석 전(로딩 중)이면 썸네일 없이 배지만 보여준다.
  readonly assetUrls?: Readonly<Record<string, string>>;
  readonly onApply: (project: GameProject) => void;
  // S15P21A604-630 — "이미지 추가"가 한 번에 여러 파일을 넘길 수 있도록 File 배열을 받는다.
  // 호출부(GameStudioShell)가 순차 처리·300개 상한·배치 요약을 책임진다 — 이 컴포넌트는
  // 선택된 파일을 그대로 넘기기만 한다.
  readonly onUploadAsset: (kind: AssetReference['kind'], files: readonly File[]) => void;
  readonly onDeleteAsset: (assetId: string) => void;
  readonly onDeleteVariable: (variableId: string) => void;
  readonly onDeleteItem: (itemId: string) => void;
  // S15P21A604-630 — 다중 업로드 처리 중임을 보여주는 "n/총" 카운터. 없거나 null이면
  // (단일 파일이거나 업로드 중이 아니면) 표시하지 않는다.
  readonly uploadProgress?: { readonly current: number; readonly total: number } | null;
}

// S15P21A604-582 — 변수/아이템/자산 삭제 확인 UI가 스타일 없이 각 섹션 맨 끝에 인라인으로
// 렌더돼서(관련 CSS 0줄) 클릭한 카드와 동떨어진 위치에 문단처럼 떴다(Notion QA #56).
// GameStudioShell.tsx의 showResetConfirm과 같은 중앙 모달 패턴으로 통일한다 — 문구/사용
// 위치(⌖) 표시는 그대로, 감싸는 컨테이너만 교체.
const DeleteConfirmModal = ({
  title,
  unusedNote,
  usedNote,
  usage,
  onCancel,
  onConfirm,
}: {
  readonly title: string;
  readonly unusedNote: string;
  readonly usedNote: string;
  readonly usage: readonly string[];
  readonly onCancel: () => void;
  readonly onConfirm: () => void;
}) => {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onCancel();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [onCancel]);

  return (
    <div className="gss-guide-backdrop" onMouseDown={onCancel} role="presentation">
      <section aria-modal="true" className="gss-delete-confirm-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog">
        <h2>{title} 삭제할까요?</h2>
        {usage.length === 0
          ? <p>{unusedNote}</p>
          : (
            <>
              <p>{usedNote}</p>
              {usage.map((location) => <em key={location}>⌖ {location}</em>)}
            </>
          )}
        <div className="gss-delete-confirm-actions">
          <button autoFocus onClick={onCancel} type="button">취소</button>
          <button className="gss-delete-confirm-danger" onClick={onConfirm} type="button">삭제</button>
        </div>
      </section>
    </div>
  );
};

const VariableValueInput = ({
  variable,
  onChange,
}: {
  readonly variable: VariableDefinition;
  readonly onChange: (value: Scalar) => void;
}) => {
  if (variable.type === 'BOOLEAN') {
    return (
      <label className="gss-check-row gss-check-row--compact">
        <input
          checked={variable.initialValue === true}
          onChange={(event) => onChange(event.target.checked)}
          type="checkbox"
        />
        시작값 true
      </label>
    );
  }
  return (
    <input
      onChange={(event) => onChange(variable.type === 'INTEGER'
        ? Number.parseInt(event.target.value, 10) || 0
        : event.target.value)}
      type={variable.type === 'INTEGER' ? 'number' : 'text'}
      value={String(variable.initialValue)}
    />
  );
};

// S15P21A604-580 — 유니코드 "×" 글자를 직접 썼더니 폰트마다 잉크(획)가 박스 중앙에서
// 살짝 벗어나 보였다(아래·왼쪽으로 치우침 — 폰트 렌더링에 따라 달라지는 문제). SVG로
// 바꾸면 폰트와 무관하게 항상 기하학적으로 정확히 중앙에 온다.
const DeleteIcon = () => (
  <svg aria-hidden="true" fill="none" height="12" stroke="currentColor" strokeLinecap="round" strokeWidth="2.5" viewBox="0 0 24 24" width="12">
    <line x1="6" x2="18" y1="6" y2="18" />
    <line x1="18" x2="6" y1="6" y2="18" />
  </svg>
);

const objectiveLabels: Readonly<Record<GameObjectiveType, { readonly title: string; readonly unit: string; readonly defaultTarget: number; readonly max: number }>> = {
  SCORE_AT_LEAST: { title: '점수 달성', unit: '점', defaultTarget: 500, max: 999999999 },
  DEFEAT_ENEMIES: { title: '적 처치', unit: '명', defaultTarget: 5, max: 10000 },
  SURVIVE_SECONDS: { title: '시간 생존', unit: '초', defaultTarget: 30, max: 3600 },
};

export const ProjectDataPanel = ({ project, assetUrls = {}, onApply, onUploadAsset, onDeleteAsset, onDeleteVariable, onDeleteItem, uploadProgress = null }: ProjectDataPanelProps) => {
  const imageInput = useRef<HTMLInputElement>(null);
  const tilesetInput = useRef<HTMLInputElement>(null);
  // S15P21A604-561 — 삭제 버튼을 누르면 바로 지우지 않고, 사용 위치를 먼저 보여주고
  // 확인을 받는다. null이면 확인 카드가 안 뜬다.
  const [confirmDeleteAssetId, setConfirmDeleteAssetId] = useState<string | null>(null);
  // S15P21A604-562 — 변수 삭제도 자산과 같은 확인 카드 패턴을 쓴다.
  const [confirmDeleteVariableId, setConfirmDeleteVariableId] = useState<string | null>(null);
  // S15P21A604-565 — 아이템 삭제도 동일한 확인 카드 패턴을 쓴다.
  const [confirmDeleteItemId, setConfirmDeleteItemId] = useState<string | null>(null);
  const projectBytes = estimateGameProjectJsonBytes(project);
  const projectUsage = Math.min(100, (projectBytes / GAME_PROJECT_LIMITS.maxJsonBytes) * 100);
  const publishBlockers = findPublishBlockers(project);
  const projectHealth = analyzeProjectHealth(project);
  const passedHealthChecks = projectHealth.filter((check) => check.status === 'PASS').length;
  const blockingHealthChecks = projectHealth.filter((check) => check.status === 'BLOCKER').length;
  const rules = project.rules ?? DEFAULT_GAME_RULES;
  const replaceObjectives = (objectives: readonly GameObjective[]) => onApply(replaceGameRules(project, {
    ...rules,
    completion: { ...rules.completion, objectives },
  }));
  const addObjective = (type: GameObjectiveType) => {
    if (rules.completion.objectives.some((objective) => objective.type === type)) return;
    replaceObjectives([...rules.completion.objectives, { type, target: objectiveLabels[type].defaultTarget }]);
  };
  return <div className="gss-panel-stack">
    <div className="gss-panel-heading"><div><span className="gss-eyebrow">PROJECT DATA</span><h2>게임 규칙 데이터</h2></div></div>
    <div className="gss-budget-card"><header><strong>프로젝트 저장 용량</strong><span>{(projectBytes / 1024).toFixed(1)} KB / 2 MB</span></header><div><i style={{ width: `${projectUsage}%` }} /></div><p>이미지·오디오는 별도 자산 저장소에 보관되어 이 용량에 포함되지 않습니다.</p></div>
    <section className={`gss-health-card${blockingHealthChecks === 0 && passedHealthChecks === projectHealth.length ? ' is-ready' : ''}`}>
      <header>
        <div><span className="gss-eyebrow">PLAYABILITY CHECK</span><strong>게임 완성도 점검</strong></div>
        <span>{passedHealthChecks}/{projectHealth.length}</span>
      </header>
      <p>{blockingHealthChecks > 0 ? '게시 전에 막힌 항목부터 해결하세요.' : '경고는 게시를 막지 않지만 플레이 감각을 위해 확인하는 것이 좋습니다.'}</p>
      <div>
        {projectHealth.map((check) => (
          <article className={`is-${check.status.toLowerCase()}`} key={check.id}>
            <span aria-hidden="true">{check.status === 'PASS' ? '✓' : check.status === 'BLOCKER' ? '!' : '△'}</span>
            <div>
              <strong>{check.title}</strong>
              <small>{check.detail}</small>
              {check.locations.length > 0 && <em>{check.locations.slice(0, 3).join(' · ')}{check.locations.length > 3 ? ` 외 ${check.locations.length - 3}곳` : ''}</em>}
            </div>
          </article>
        ))}
      </div>
    </section>
    <div className="gss-section-title"><span>GAME GOALS</span><small>{rules.completion.objectives.length}/3</small></div>
    <div className="gss-help-card"><strong>게임이 언제 끝나는지 정하세요</strong><p>목표를 고르면 플레이 화면에 진행도가 자동 표시되고, 달성하는 순간 게임이 완료됩니다. 목표가 없으면 문·포털·대화 이벤트의 “게임 완료”를 사용합니다.</p></div>
    {rules.completion.objectives.length > 1 && (
      <label className="gss-field"><span>여러 목표의 완료 방식</span><select
        onChange={(event) => onApply(replaceGameRules(project, { ...rules, completion: { ...rules.completion, mode: event.target.value as 'ALL' | 'ANY' } }))}
        value={rules.completion.mode}
      ><option value="ALL">모든 목표 달성</option><option value="ANY">하나만 달성</option></select></label>
    )}
    {rules.completion.objectives.map((objective, index) => {
      const definition = objectiveLabels[objective.type];
      return (
        <article className="gss-data-card" key={objective.type}>
          <header><strong>{definition.title}</strong><button aria-label={`${definition.title} 목표 삭제`} className="gss-delete-icon-button" onClick={() => replaceObjectives(rules.completion.objectives.filter((_, objectiveIndex) => objectiveIndex !== index))} type="button"><DeleteIcon /></button></header>
          <CommitInput
            label={`목표값 (${definition.unit})`}
            onCommit={(value) => {
              const parsed = Number.parseInt(value, 10);
              const target = Number.isFinite(parsed) ? Math.max(1, Math.min(definition.max, parsed)) : objective.target;
              replaceObjectives(rules.completion.objectives.map((candidate, objectiveIndex) => objectiveIndex === index ? { ...candidate, target } : candidate));
            }}
            value={String(objective.target)}
          />
        </article>
      );
    })}
    <div className="gss-inline-actions">
      {(Object.keys(objectiveLabels) as GameObjectiveType[]).map((type) => (
        <button
          disabled={rules.completion.objectives.some((objective) => objective.type === type)}
          key={type}
          onClick={() => addObjective(type)}
          type="button"
        >+ {objectiveLabels[type].title}</button>
      ))}
    </div>
    <label className="gss-field"><span>체력이 0이 되면</span><select
      onChange={(event) => onApply(replaceGameRules(project, { ...rules, playerDefeat: event.target.value as 'RESPAWN' | 'END_GAME' }))}
      value={rules.playerDefeat}
    ><option value="RESPAWN">체크포인트에서 다시 시작</option><option value="END_GAME">도전 실패로 종료</option></select></label>
    <div className="gss-section-title"><span>VARIABLES</span><small>{project.variables.length}/100</small></div>
    {project.variables.map((variable) => (
      <article className="gss-data-card" key={variable.id}>
        <header>
          <strong>{variable.id}</strong>
          <span>{variable.type}</span>
          <button aria-label={`${variable.id} 삭제`} className="gss-delete-icon-button" onClick={() => setConfirmDeleteVariableId(variable.id)} type="button"><DeleteIcon /></button>
        </header>
        <VariableValueInput
          onChange={(value) => onApply(replaceVariableDefinition(project, variable.id, value))}
          variable={variable}
        />
      </article>
    ))}
    {/* S15P21A604-566 — 버튼 3개를 가로로 늘어놓으면 패널 폭을 넘겨 가로 스크롤이 생겨서(기존
        .gss-add-block의 width:100%가 flex item에서 각자 컨테이너 전체 폭을 요구했기 때문),
        gss-inline-actions--thirds로 세 버튼이 폭을 1/3씩 나눠 갖도록 하고 캡션에서 "변수"를
        빼 한 줄에 다 보이게 했다. */}
    <div className="gss-inline-actions gss-inline-actions--thirds">
      <button
        className="gss-add-block"
        disabled={project.variables.length >= 100}
        onClick={() => onApply(addBooleanVariable(project))}
        type="button"
      >+ Boolean</button>
      <button
        className="gss-add-block"
        disabled={project.variables.length >= 100}
        onClick={() => onApply(addIntegerVariable(project))}
        type="button"
      >+ Integer</button>
      <button
        className="gss-add-block"
        disabled={project.variables.length >= 100}
        onClick={() => onApply(addStringVariable(project))}
        type="button"
      >+ String</button>
    </div>
    {confirmDeleteVariableId !== null && (() => {
      const target = project.variables.find((variable) => variable.id === confirmDeleteVariableId);
      if (target === undefined) return null;
      return (
        <DeleteConfirmModal
          onCancel={() => setConfirmDeleteVariableId(null)}
          onConfirm={() => { onDeleteVariable(confirmDeleteVariableId); setConfirmDeleteVariableId(null); }}
          title={target.id}
          unusedNote="현재 조건·액션에서 사용되지 않는 변수입니다."
          usage={findVariableUsageLocations(project, confirmDeleteVariableId)}
          usedNote="다음 위치에서 사용 중입니다 — 삭제하면 검증 오류가 날 수 있습니다."
        />
      );
    })()}

    <div className="gss-section-title"><span>ITEMS</span><small>{project.items.length}/100</small></div>
    {project.items.map((item) => (
      <article className="gss-data-card" key={item.id}>
        <header>
          <strong>{item.id}</strong>
          <span>ITEM</span>
          <button aria-label={`${item.id} 삭제`} className="gss-delete-icon-button" onClick={() => setConfirmDeleteItemId(item.id)} type="button"><DeleteIcon /></button>
        </header>
        <CommitInput
          label="사용자에게 보이는 이름"
          onCommit={(name) => onApply(renameItemDefinition(project, item.id, name))}
          value={item.name}
        />
      </article>
    ))}
    <button
      className="gss-add-block"
      disabled={project.items.length >= 100}
      onClick={() => onApply(addItemDefinition(project))}
      type="button"
    >+ 아이템</button>
    {confirmDeleteItemId !== null && (() => {
      const target = project.items.find((item) => item.id === confirmDeleteItemId);
      if (target === undefined) return null;
      return (
        <DeleteConfirmModal
          onCancel={() => setConfirmDeleteItemId(null)}
          onConfirm={() => { onDeleteItem(confirmDeleteItemId); setConfirmDeleteItemId(null); }}
          title={target.name}
          unusedNote="현재 오브젝트·조건·액션에서 사용되지 않는 아이템입니다."
          usage={findItemUsageLocations(project, confirmDeleteItemId)}
          usedNote="다음 위치에서 사용 중입니다 — 삭제하면 검증 오류가 날 수 있습니다."
        />
      );
    })()}

    <div className="gss-section-title"><span>ASSETS</span><small>{project.assets.length}/300</small></div>
    {publishBlockers.length > 0 && (
      <section className="gss-publish-check" role="alert">
        <header><strong>게시 전에 {publishBlockers.length}가지를 확인하세요</strong><span>{publishBlockers.length}</span></header>
        <p>편집과 플레이 테스트는 계속할 수 있습니다. 아래 항목을 해결해야 다른 사용자가 끝까지 플레이할 수 있습니다.</p>
        {publishBlockers.map((blocker) => (
          <article key={blocker.assetId}>
            <strong>{blocker.assetId}</strong>
            <small>{blocker.code === 'LOCAL_ASSET' ? '이 브라우저에만 있는 이미지' : blocker.code === 'NO_COMPLETION_PATH' ? '완료 조건 없음' : '게시할 수 없는 주소'}</small>
            {blocker.locations.length === 0
              ? <em>현재 배치에서 사용되지 않음</em>
              : blocker.locations.map((location) => <em key={location}>⌖ {location}</em>)}
          </article>
        ))}
      </section>
    )}
    <div className="gss-help-card"><strong>바로 쓰는 기본 재료</strong><p>파일을 찾을 필요 없이 캐릭터, 표정, 오브젝트, 배경과 타일셋을 속성에서 바로 선택할 수 있습니다.</p></div>
    <div className="gss-builtin-library">
      {BUILTIN_STATIC_IMAGES.map((asset) => (
        <div key={asset.source} title={asset.label}>
          <span style={staticImageBackgroundStyle(asset)} />
          <small>{asset.label}</small>
        </div>
      ))}
      {BUILTIN_TILESETS.map((asset) => (
        <div key={asset.source} title={asset.label}>
          <span style={tileBackgroundStyle(asset, 0)} />
          <small>{asset.label}</small>
        </div>
      ))}
    </div>
    <details className="gss-custom-assets">
      <summary>내 자산으로 교체 (선택)</summary>
      <p>기본 재료 대신 직접 만든 이미지를 쓸 때만 파일을 선택합니다. 이미지는 여러 장을 한 번에 고를 수 있습니다.</p>
      <div className="gss-inline-actions">
        <button disabled={project.assets.length >= 300} onClick={() => imageInput.current?.click()} type="button">이미지 추가</button>
        <button disabled={project.assets.length >= 300} onClick={() => tilesetInput.current?.click()} type="button">타일셋 추가</button>
      </div>
      {uploadProgress !== null && (
        <p className="gss-upload-progress">{uploadProgress.current}/{uploadProgress.total} 처리중…</p>
      )}
      {/* S15P21A604-630 — 이미지는 multiple로 여러 장을 한 번에 선택할 수 있다. 타일셋은
          세트당 아틀라스 이미지 1장이 기본 단위라(8×8 등) 다중 선택 대상에서 뺐다 — 지금처럼
          단일 파일 그대로 둔다. */}
      <input
        accept="image/png,image/jpeg,image/gif,image/webp"
        hidden
        multiple
        onChange={(event) => {
          const files = Array.from(event.target.files ?? []);
          event.target.value = '';
          if (files.length > 0) onUploadAsset('IMAGE', files);
        }}
        ref={imageInput}
        type="file"
      />
      <input
        accept="image/png,image/jpeg,image/webp"
        hidden
        onChange={(event) => {
          const file = event.target.files?.[0];
          event.target.value = '';
          if (file !== undefined) onUploadAsset('TILESET', [file]);
        }}
        ref={tilesetInput}
        type="file"
      />
    </details>
    <div className="gss-asset-list">
      {project.assets.map((asset) => {
        // 빌트인은 프로젝트 소유가 아니라 카탈로그 참조라 삭제 대상이 아니다.
        const deletable = !asset.source.startsWith('builtin://');
        // S15P21A604-573 — 종류 배지 하나로만 뭉뚱그려 보이던 자산 목록에 실제 썸네일을
        // 넣는다. assetUrls에 아직 없으면(로딩 중이거나 AUDIO처럼 이미지가 없으면) 빈
        // 자리만 남기고 배지는 그대로 보여준다 — gss-builtin-library와 같은 렌더 패턴.
        const imageVisual = asset.kind === 'IMAGE' ? resolveStaticImageVisual(asset, assetUrls) : null;
        const tilesetVisual = asset.kind === 'TILESET' ? resolveTilesetVisual(asset, assetUrls) : null;
        return (
          <div key={asset.id}>
            <span className={`gss-asset-kind is-${asset.kind.toLowerCase()}`}>
              {imageVisual !== null && <i className="gss-asset-thumb" style={staticImageBackgroundStyle(imageVisual)} />}
              {tilesetVisual !== null && <i className="gss-asset-thumb" style={tileBackgroundStyle(tilesetVisual, 0)} />}
              <em>{asset.kind}</em>
            </span>
            <strong>{assetDisplayLabel(asset)}</strong>
            <small>{asset.id}</small>
            {deletable && (
              <button aria-label={`${assetDisplayLabel(asset)} 삭제`} className="gss-delete-icon-button" onClick={() => setConfirmDeleteAssetId(asset.id)} type="button"><DeleteIcon /></button>
            )}
          </div>
        );
      })}
    </div>
    {confirmDeleteAssetId !== null && (() => {
      const target = project.assets.find((asset) => asset.id === confirmDeleteAssetId);
      if (target === undefined) return null;
      return (
        <DeleteConfirmModal
          onCancel={() => setConfirmDeleteAssetId(null)}
          onConfirm={() => { onDeleteAsset(confirmDeleteAssetId); setConfirmDeleteAssetId(null); }}
          title={assetDisplayLabel(target)}
          unusedNote="현재 배치에서 사용되지 않는 자산입니다."
          usage={findAssetUsageLocations(project, confirmDeleteAssetId)}
          usedNote="다음 위치에서 사용 중입니다 — 삭제하면 그 자리는 빈 값으로 남습니다."
        />
      );
    })()}
    <div className="gss-help-card"><strong>게시 연결 준비 완료</strong><p>편집기는 이미지 대신 assetId만 저장합니다. 현재 로컬 저장소를 게시 자산 API로 교체해도 프로젝트 구조는 바뀌지 않습니다.</p></div>
  </div>;
};
