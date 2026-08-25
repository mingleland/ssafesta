import { useRef } from 'react';
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
  addItemDefinition,
  renameItemDefinition,
  replaceGameRules,
  replaceVariableDefinition,
} from '../model/authoringCommands.ts';
import { CommitInput } from './CommitInput.tsx';
import { assetDisplayLabel, BUILTIN_STATIC_IMAGES, BUILTIN_TILESETS } from '../assets/builtinAssetCatalog.ts';
import { staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import { findPublishBlockers } from '../ports/publishValidation.ts';
import { analyzeProjectHealth } from '../model/projectHealth.ts';

interface ProjectDataPanelProps {
  readonly project: GameProject;
  readonly onApply: (project: GameProject) => void;
  readonly onUploadAsset: (kind: AssetReference['kind'], file: File) => void;
}

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

const objectiveLabels: Readonly<Record<GameObjectiveType, { readonly title: string; readonly unit: string; readonly defaultTarget: number; readonly max: number }>> = {
  SCORE_AT_LEAST: { title: '점수 달성', unit: '점', defaultTarget: 500, max: 999999999 },
  DEFEAT_ENEMIES: { title: '적 처치', unit: '명', defaultTarget: 5, max: 10000 },
  SURVIVE_SECONDS: { title: '시간 생존', unit: '초', defaultTarget: 30, max: 3600 },
};

export const ProjectDataPanel = ({ project, onApply, onUploadAsset }: ProjectDataPanelProps) => {
  const imageInput = useRef<HTMLInputElement>(null);
  const tilesetInput = useRef<HTMLInputElement>(null);
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
          <header><strong>{definition.title}</strong><button aria-label={`${definition.title} 목표 삭제`} onClick={() => replaceObjectives(rules.completion.objectives.filter((_, objectiveIndex) => objectiveIndex !== index))} type="button">×</button></header>
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
        <header><strong>{variable.id}</strong><span>{variable.type}</span></header>
        <VariableValueInput
          onChange={(value) => onApply(replaceVariableDefinition(project, variable.id, value))}
          variable={variable}
        />
      </article>
    ))}
    <button
      className="gss-add-block"
      disabled={project.variables.length >= 100}
      onClick={() => onApply(addBooleanVariable(project))}
      type="button"
    >+ Boolean 변수</button>

    <div className="gss-section-title"><span>ITEMS</span><small>{project.items.length}/100</small></div>
    {project.items.map((item) => (
      <article className="gss-data-card" key={item.id}>
        <header><strong>{item.id}</strong><span>ITEM</span></header>
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
      <p>기본 재료 대신 직접 만든 이미지를 쓸 때만 파일을 선택합니다.</p>
      <div className="gss-inline-actions">
        <button disabled={project.assets.length >= 300} onClick={() => imageInput.current?.click()} type="button">이미지 추가</button>
        <button disabled={project.assets.length >= 300} onClick={() => tilesetInput.current?.click()} type="button">타일셋 추가</button>
      </div>
      <input
        accept="image/png,image/jpeg,image/gif,image/webp"
        hidden
        onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (file !== undefined) onUploadAsset('IMAGE', file); }}
        ref={imageInput}
        type="file"
      />
      <input
        accept="image/png,image/jpeg,image/webp"
        hidden
        onChange={(event) => { const file = event.target.files?.[0]; event.target.value = ''; if (file !== undefined) onUploadAsset('TILESET', file); }}
        ref={tilesetInput}
        type="file"
      />
    </details>
    <div className="gss-asset-list">
      {project.assets.map((asset) => (
        <div key={asset.id}><span className={`gss-asset-kind is-${asset.kind.toLowerCase()}`}>{asset.kind}</span><strong>{assetDisplayLabel(asset)}</strong><small>{asset.id}</small></div>
      ))}
    </div>
    <div className="gss-help-card"><strong>게시 연결 준비 완료</strong><p>편집기는 이미지 대신 assetId만 저장합니다. 현재 로컬 저장소를 게시 자산 API로 교체해도 프로젝트 구조는 바뀌지 않습니다.</p></div>
  </div>;
};
