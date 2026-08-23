import { useRef } from 'react';
import { estimateGameProjectJsonBytes, GAME_PROJECT_LIMITS, type AssetReference, type GameProject, type Scalar, type VariableDefinition } from '../../contracts/gameProject.ts';
import {
  addBooleanVariable,
  addItemDefinition,
  renameItemDefinition,
  replaceVariableDefinition,
} from '../model/authoringCommands.ts';
import { CommitInput } from './CommitInput.tsx';
import { assetDisplayLabel, BUILTIN_STATIC_IMAGES, BUILTIN_TILESETS } from '../assets/builtinAssetCatalog.ts';
import { staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import { findPublishBlockers } from '../ports/publishValidation.ts';

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

export const ProjectDataPanel = ({ project, onApply, onUploadAsset }: ProjectDataPanelProps) => {
  const imageInput = useRef<HTMLInputElement>(null);
  const tilesetInput = useRef<HTMLInputElement>(null);
  const projectBytes = estimateGameProjectJsonBytes(project);
  const projectUsage = Math.min(100, (projectBytes / GAME_PROJECT_LIMITS.maxJsonBytes) * 100);
  const publishBlockers = findPublishBlockers(project);
  return <div className="gss-panel-stack">
    <div className="gss-panel-heading"><div><span className="gss-eyebrow">PROJECT DATA</span><h2>게임 규칙 데이터</h2></div></div>
    <div className="gss-budget-card"><header><strong>프로젝트 저장 용량</strong><span>{(projectBytes / 1024).toFixed(1)} KB / 2 MB</span></header><div><i style={{ width: `${projectUsage}%` }} /></div><p>이미지·오디오는 별도 자산 저장소에 보관되어 이 용량에 포함되지 않습니다.</p></div>
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
        <header><strong>게시 전에 자산 {publishBlockers.length}개를 연결하세요</strong><span>{publishBlockers.length}</span></header>
        <p>편집과 플레이 테스트는 계속할 수 있습니다. 게시할 때만 서버 자산 주소가 필요합니다.</p>
        {publishBlockers.map((blocker) => (
          <article key={blocker.assetId}>
            <strong>{blocker.assetId}</strong>
            <small>{blocker.code === 'LOCAL_ASSET' ? '이 브라우저에만 있는 이미지' : '게시할 수 없는 주소'}</small>
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
