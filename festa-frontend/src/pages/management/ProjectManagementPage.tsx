// Project Management — 부스 소유자의 전시 프로젝트 편집 (user-flow-decisions §19).
// 진입: Booth Management → PROJECT [관리]. 방문자용 Project Overlay 와 분리된 화면이다.
//
// 데이터층은 features/project/model/edit.ts 를 그대로 소비한다 — dirty 키만 PATCH 하는 규칙,
// 저장 중 입력 차단, projectId null 이면 create 는 전부 그 모델의 계약이고 여기서 바꾸지 않는다.
// 모델에 없는 필드·조회수 같은 지표를 추가하지 않는다.
import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  loadProjectEdit,
  saveProject,
  updateField,
  useProjectEdit,
} from '../../features/project/model/edit';
import type { ProjectFieldKey } from '../../shared/contracts/project';
import { ScreenError, ScreenLoading } from '../../features/shell/ui/PageShell';
import {
  ManagementScreen,
  useManagementBoothId,
} from '../../features/booth/ui/ManagementScreen';
import './management.css';

type Field = { key: ProjectFieldKey; label: string; kind: 'text' | 'area' | 'url'; hint?: string };

/**
 * 실제 모델의 필드만 — 라벨과 입력 종류만 여기서 정한다.
 *
 * 평면 나열을 뜻 단위로 묶는다. 방문자에게 먼저 읽히는 것(이름·소개)과 전시에 그려지는 것
 * (대표 이미지·소개 영상), 그리고 부가로 따라가는 외부 링크는 중요도가 다르다.
 */
const GROUPS: { title: string; note?: string; fields: Field[] }[] = [
  {
    title: '기본 정보',
    fields: [
      { key: 'name', label: '프로젝트 이름', kind: 'text' },
      { key: 'description', label: '소개', kind: 'area' },
    ],
  },
  {
    title: '대표 · 미디어',
    note: '월드의 전시 패널에 그려지는 값입니다.',
    fields: [
      { key: 'thumbnailUrl', label: '대표 이미지 주소', kind: 'url', hint: 'https://' },
      { key: 'videoUrl', label: '소개 영상 주소', kind: 'url', hint: 'YouTube 링크를 넣으면 전시에 임베드됩니다' },
    ],
  },
  {
    title: '외부 링크',
    note: '방문자가 더 알아보고 싶을 때 따라가는 주소입니다.',
    fields: [
      { key: 'deployUrl', label: '서비스 주소', kind: 'url', hint: 'https://' },
      { key: 'gitUrl', label: '저장소 주소', kind: 'url', hint: 'https://' },
      { key: 'portfolioUrl', label: '포트폴리오 주소', kind: 'url', hint: 'https://' },
    ],
  },
];

/** 대표 이미지가 실제로 열리는지 문자열이 아니라 그림으로 확인시킨다. 실패하면 조용히 감춘다 */
function ThumbnailProbe({ url }: { url: string }) {
  const [broken, setBroken] = useState(false);
  useEffect(() => setBroken(false), [url]);
  if (!/^https?:\/\//.test(url)) return null;
  if (broken) {
    return <span className="mg-thumb-note sc-note">이 주소에서 이미지를 불러오지 못했습니다.</span>;
  }
  return <img className="mg-thumb" src={url} alt="대표 이미지 미리보기" onError={() => setBroken(true)} />;
}

export function ProjectManagementPage() {
  const boothIdNum = useManagementBoothId();
  const state = useProjectEdit();
  // 저장 성공이 내 부스 관리창의 프로젝트 조회를 무효화하게 한다 (edit.ts saveProject 주석)
  const queryClient = useQueryClient();

  useEffect(() => {
    if (Number.isFinite(boothIdNum)) void loadProjectEdit(boothIdNum);
  }, [boothIdNum]);

  const saving = state.save.phase === 'submitting';

  if (state.status === 'idle' || state.status === 'loading') {
    return (
      <ManagementScreen title="프로젝트 관리">
        <ScreenLoading label="프로젝트를 불러오는 중..." />
      </ManagementScreen>
    );
  }
  if (state.status === 'error') {
    return (
      <ManagementScreen title="프로젝트 관리">
        <ScreenError
          title="프로젝트를 불러오지 못했습니다"
          message="잠시 후 다시 시도해 주세요."
          onRetry={() => void loadProjectEdit(boothIdNum)}
        />
      </ManagementScreen>
    );
  }

  return (
    <ManagementScreen
      title="프로젝트 관리"
      subtitle={state.projectId === null ? '아직 등록한 프로젝트가 없습니다 — 저장하면 새로 만들어집니다' : '방문자에게 보이는 전시 내용'}
      actions={
        // 저장 결과를 버튼 옆에서 말한다 — 폼 맨 아래에 두면 긴 폼에서 눌린 버튼과 멀어진다
        <div className="mg-foot">
          {state.save.phase === 'success' && <span className="mg-ok">저장했습니다</span>}
          {state.save.phase === 'error' && Object.keys(state.fieldErrors).length === 0 && (
            <span className="sc-alert" role="alert">저장하지 못했습니다. 잠시 후 다시 시도해 주세요.</span>
          )}
          {state.save.phase === 'error' && Object.keys(state.fieldErrors).length > 0 && (
            <span className="sc-alert" role="alert">입력을 확인해 주세요.</span>
          )}
          <button
            type="button"
            className="sc-btn sc-btn-primary"
            disabled={saving || state.dirty.size === 0}
            onClick={() => void saveProject(queryClient)}
          >
            {saving ? '저장 중...' : '변경 저장'}
          </button>
        </div>
      }
    >
      <form className="mg-groups" onSubmit={(e) => e.preventDefault()}>
        {GROUPS.map((group) => (
          <section key={group.title} className="sc-card mg-group">
            <div className="mg-group-head">
              <span className="sc-section-title">{group.title}</span>
              {group.note !== undefined && <span className="sc-note">{group.note}</span>}
            </div>
            {group.fields.map((f) => {
              const value = state.draft[f.key] ?? '';
              const dirty = state.dirty.has(f.key);
              const fieldError = state.fieldErrors[f.key];
              const errorId = `project-field-${f.key}-error`;
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
                      aria-invalid={fieldError ? true : undefined}
                      aria-describedby={fieldError ? errorId : undefined}
                      onChange={(e) => updateField(f.key, e.target.value === '' ? null : e.target.value)}
                    />
                  ) : (
                    <input
                      className="mg-input"
                      type="text"
                      value={value}
                      disabled={saving}
                      placeholder={f.hint}
                      aria-invalid={fieldError ? true : undefined}
                      aria-describedby={fieldError ? errorId : undefined}
                      onChange={(e) => updateField(f.key, e.target.value === '' ? null : e.target.value)}
                    />
                  )}
                  {fieldError && <span id={errorId} className="mg-field-error" role="alert">{fieldError}</span>}
                  {f.key === 'thumbnailUrl' && value !== '' && <ThumbnailProbe url={value} />}
                  {f.hint !== undefined && f.kind !== 'url' && <span className="sc-note">{f.hint}</span>}
                </label>
              );
            })}
          </section>
        ))}
      </form>
    </ManagementScreen>
  );
}
