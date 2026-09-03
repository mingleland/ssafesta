// Project Management — 부스 소유자의 전시 프로젝트 편집 (user-flow-decisions §19).
// 진입: Booth Management → PROJECT [관리]. 방문자용 Project Overlay 와 분리된 화면이다.
//
// 데이터층은 features/project/model/edit.ts 를 그대로 소비한다 — dirty 키만 PATCH 하는 규칙,
// 저장 중 입력 차단, projectId null 이면 create 는 전부 그 모델의 계약이고 여기서 바꾸지 않는다.
// 모델에 없는 필드·조회수 같은 지표를 추가하지 않는다.
import { useEffect } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  loadProjectEdit,
  saveProject,
  updateField,
  useProjectEdit,
} from '../../features/project/model/edit';
import type { ProjectFieldKey } from '../../shared/contracts/project';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../features/world/model/gameClientUi';
import { PageShell, ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import './management.css';

/** 실제 모델의 필드만 — 라벨과 입력 종류만 여기서 정한다 */
const FIELDS: { key: ProjectFieldKey; label: string; kind: 'text' | 'area' | 'url'; hint?: string }[] = [
  { key: 'name', label: '프로젝트 이름', kind: 'text' },
  { key: 'description', label: '소개', kind: 'area' },
  { key: 'thumbnailUrl', label: '대표 이미지 주소', kind: 'url', hint: 'https://' },
  { key: 'videoUrl', label: '소개 영상 주소', kind: 'url', hint: 'YouTube 링크를 넣으면 전시에 임베드됩니다' },
  { key: 'deployUrl', label: '서비스 주소', kind: 'url', hint: 'https://' },
  { key: 'gitUrl', label: '저장소 주소', kind: 'url', hint: 'https://' },
  { key: 'portfolioUrl', label: '포트폴리오 주소', kind: 'url', hint: 'https://' },
];

export function ProjectManagementPage() {
  const { boothId } = useParams<{ boothId: string }>();
  const navigate = useNavigate();
  const boothIdNum = Number(boothId);
  const state = useProjectEdit();

  useEffect(() => {
    if (Number.isFinite(boothIdNum)) void loadProjectEdit(boothIdNum);
  }, [boothIdNum]);

  const saving = state.save.phase === 'submitting';

  if (state.status === 'idle' || state.status === 'loading') {
    return (
      <PageShell title="프로젝트 관리" backTo={WORLD_RETURN_TO_MANAGEMENT}>
        <ScreenLoading label="프로젝트를 불러오는 중..." />
      </PageShell>
    );
  }
  if (state.status === 'error') {
    return (
      <PageShell title="프로젝트 관리" backTo={WORLD_RETURN_TO_MANAGEMENT}>
        <ScreenError
          title="프로젝트를 불러오지 못했습니다"
          message="잠시 후 다시 시도해 주세요."
          onRetry={() => void loadProjectEdit(boothIdNum)}
        />
      </PageShell>
    );
  }

  return (
    <PageShell
      title="프로젝트 관리"
      subtitle={state.projectId === null ? '아직 등록한 프로젝트가 없습니다 — 저장하면 새로 만들어집니다' : '방문자에게 보이는 전시 내용'}
      backTo={WORLD_RETURN_TO_MANAGEMENT}
      actions={
        <button
          type="button"
          className="sc-btn sc-btn-primary"
          disabled={saving || state.dirty.size === 0}
          onClick={() => void saveProject()}
        >
          {saving ? '저장 중...' : '저장'}
        </button>
      }
    >
      <form className="sc-card mg-form" onSubmit={(e) => e.preventDefault()}>
        {FIELDS.map((f) => {
          const value = state.draft[f.key] ?? '';
          const dirty = state.dirty.has(f.key);
          return (
            <label key={f.key} className={'mg-field' + (dirty ? ' mg-field-dirty' : '')}>
              <span className="mg-label">
                {f.label}
                {dirty && <span className="mg-dirty-dot" aria-label="변경됨" />}
              </span>
              {f.kind === 'area' ? (
                <textarea
                  className="mg-input mg-textarea"
                  value={value}
                  disabled={saving}
                  rows={4}
                  onChange={(e) => updateField(f.key, e.target.value === '' ? null : e.target.value)}
                />
              ) : (
                <input
                  className="mg-input"
                  type="text"
                  value={value}
                  disabled={saving}
                  placeholder={f.hint}
                  onChange={(e) => updateField(f.key, e.target.value === '' ? null : e.target.value)}
                />
              )}
              {f.hint !== undefined && f.kind !== 'url' && <span className="sc-note">{f.hint}</span>}
            </label>
          );
        })}

        <div className="mg-form-foot">
          {state.save.phase === 'success' && <span className="mg-ok">저장했습니다</span>}
          {state.save.phase === 'error' && (
            <span className="sc-alert" role="alert">
              저장하지 못했습니다. 잠시 후 다시 시도해 주세요.
            </span>
          )}
          <button type="button" className="sc-btn" onClick={() => navigate(WORLD_RETURN_TO_MANAGEMENT)}>
            부스 관리로
          </button>
        </div>
      </form>
    </PageShell>
  );
}
