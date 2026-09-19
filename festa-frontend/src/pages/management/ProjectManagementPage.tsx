// Project Management — 부스 소유자의 전시 프로젝트 편집 (user-flow-decisions §19).
// 진입: Booth Management → PROJECT [관리]. 방문자용 Project Overlay 와 분리된 화면이다.
//
// 데이터층은 features/project/model/edit.ts 를 그대로 소비한다 — dirty 키만 PATCH 하는 규칙,
// 저장 중 입력 차단, projectId null 이면 create 는 전부 그 모델의 계약이고 여기서 바꾸지 않는다.
// 모델에 없는 필드·조회수 같은 지표를 추가하지 않는다.
import { useEffect, useRef, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  loadProjectEdit,
  saveProject,
  updateField,
  useProjectEdit,
} from '../../features/project/model/edit';
import {
  PROJECT_LOGO_ACCEPT,
  uploadProjectLogo,
  fetchProjectLogo,
  isManagedProjectLogo,
  validateProjectLogo,
} from '../../features/project/model/logoUpload';
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
const BASIC_FIELDS: Field[] = [
  { key: 'name', label: '프로젝트 이름', kind: 'text' },
  { key: 'description', label: '소개', kind: 'area' },
];

const GROUPS: { title: string; note?: string; fields: Field[] }[] = [
  {
    title: '대표 · 미디어',
    note: '월드의 전시 패널에 그려지는 값입니다.',
    fields: [
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

function ProjectLogoField({ boothId, savedUrl, disabled, onUploading }: {
  boothId: number; savedUrl: string | null; disabled: boolean; onUploading: (value: boolean) => void;
}) {
  const [selection, setSelection] = useState<{ file: File; url: string } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [broken, setBroken] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [resolved, setResolved] = useState<{ source: string; url: string } | null>(null);
  const active = useRef(true);
  const inFlight = useRef(false);

  const previewUrl = selection?.url ?? (savedUrl && isManagedProjectLogo(savedUrl)
    ? (resolved?.source === savedUrl ? resolved.url : null) : savedUrl);

  useEffect(() => {
    active.current = true;
    return () => { active.current = false; };
  }, []);

  useEffect(() => {
    if (!savedUrl || !isManagedProjectLogo(savedUrl)) return;
    const controller = new AbortController();
    let objectUrl: string | undefined;
    void fetchProjectLogo(savedUrl, controller.signal).then((blob) => {
      if (controller.signal.aborted) return;
      objectUrl = URL.createObjectURL(blob);
      setResolved({ source: savedUrl, url: objectUrl });
    }).catch(() => {
      if (!controller.signal.aborted) setError('현재 프로젝트 로고를 불러오지 못했습니다.');
    });
    return () => {
      controller.abort();
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [savedUrl]);

  useEffect(() => {
    return () => {
      if (selection) URL.revokeObjectURL(selection.url);
    };
  }, [selection]);

  useEffect(() => setBroken(false), [previewUrl]);

  async function selectFile(file: File | undefined) {
    if (!file || disabled || inFlight.current) return;
    const validationError = validateProjectLogo(file);
    if (validationError) {
      setError(validationError);
      return;
    }
    setError(null);
    setSelection({ file, url: URL.createObjectURL(file) });
    inFlight.current = true;
    setUploading(true);
    onUploading(true);
    try {
      const uploaded = await uploadProjectLogo(boothId, file);
      if (active.current) updateField('thumbnailUrl', uploaded.thumbnailUrl);
    } catch (cause) {
      if (active.current) {
        setSelection(null);
        setError(cause instanceof Error ? cause.message : '이미지를 업로드하지 못했습니다. 다시 시도해 주세요.');
      }
    } finally {
      inFlight.current = false;
      if (active.current) {
        setUploading(false);
        onUploading(false);
      }
    }
  }

  return (
    <div className="mg-logo-field">
      <span className="mg-label">대표 이미지</span>
      <div className="mg-logo-box">
        {previewUrl && !broken ? (
          <img
            className="mg-logo-preview"
            src={previewUrl}
            alt={selection ? '선택한 프로젝트 로고 미리보기' : '현재 프로젝트 로고'}
            onError={() => setBroken(true)}
          />
        ) : (
          <div className="mg-logo-placeholder" aria-hidden="true">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8">
              <rect x="3" y="3" width="18" height="18" rx="3" />
              <circle cx="9" cy="9" r="2" />
              <path d="m21 15-4-4L5 21" />
            </svg>
            <strong>로고 이미지 선택</strong>
          </div>
        )}
        <label className="sc-btn mg-logo-select">
          이미지 선택
          <input
            type="file"
            accept={PROJECT_LOGO_ACCEPT}
            disabled={disabled || uploading}
            aria-label="프로젝트 로고 이미지 선택"
            onChange={(event) => {
              void selectFile(event.target.files?.[0]);
              event.target.value = '';
            }}
          />
        </label>
      </div>
      <span className="sc-note">PNG, JPG, GIF, WebP · 5MB 이하 · 가로·세로 4096px 이하</span>
      {error && <span className="mg-field-error" role="alert">{error}</span>}
      <span className="mg-logo-contract-note" role="status">
        {uploading ? '이미지를 업로드하고 확인하는 중입니다.' : selection
          ? `${selection.file.name} 업로드 완료. 변경 저장을 누르면 프로젝트에 반영됩니다.`
          : '업로드할 이미지를 선택해 주세요.'}
      </span>
    </div>
  );
}

export function ProjectManagementPage() {
  const boothIdNum = useManagementBoothId();
  const state = useProjectEdit();
  const [uploadingLogo, setUploadingLogo] = useState(false);
  // 저장 성공이 내 부스 관리창의 프로젝트 조회를 무효화하게 한다 (edit.ts saveProject 주석)
  const queryClient = useQueryClient();

  useEffect(() => {
    setUploadingLogo(false);
    if (Number.isFinite(boothIdNum)) void loadProjectEdit(boothIdNum);
  }, [boothIdNum]);

  const saving = state.save.phase === 'submitting';

  const renderField = (field: Field) => {
    const value = state.draft[field.key] ?? '';
    const dirty = state.dirty.has(field.key);
    const fieldError = state.fieldErrors[field.key];
    const errorId = `project-field-${field.key}-error`;
    return (
      <label key={field.key} className={'mg-field' + (dirty ? ' mg-field-dirty' : '')}>
        <span className="mg-label">
          {field.label}
          {dirty && <span className="mg-dirty-dot" aria-label="변경됨" />}
        </span>
        {field.kind === 'area' ? (
          <textarea
            className="mg-input mg-textarea"
            value={value}
            disabled={saving}
            rows={4}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? errorId : undefined}
            onChange={(event) => updateField(field.key, event.target.value === '' ? null : event.target.value)}
          />
        ) : (
          <input
            className="mg-input"
            type="text"
            value={value}
            disabled={saving}
            placeholder={field.hint}
            aria-label={field.label}
            aria-invalid={fieldError ? true : undefined}
            aria-describedby={fieldError ? errorId : undefined}
            onChange={(event) => updateField(field.key, event.target.value === '' ? null : event.target.value)}
          />
        )}
        {fieldError && <span id={errorId} className="mg-field-error" role="alert">{fieldError}</span>}
        {field.hint !== undefined && field.kind !== 'url' && <span className="sc-note">{field.hint}</span>}
      </label>
    );
  };

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
            disabled={saving || uploadingLogo || state.dirty.size === 0}
            onClick={() => void saveProject(queryClient)}
          >
            {saving ? '저장 중...' : '변경 저장'}
          </button>
        </div>
      }
    >
      <form className="mg-groups" onSubmit={(e) => e.preventDefault()}>
        <section className="sc-card mg-group">
          <div className="mg-group-head">
            <span className="sc-section-title">기본 정보</span>
          </div>
          <div className="mg-project-identity">
            <div>
              <ProjectLogoField key={boothIdNum} boothId={boothIdNum} savedUrl={state.draft.thumbnailUrl}
                disabled={saving} onUploading={setUploadingLogo} />
              {state.fieldErrors.thumbnailUrl && <span role="alert" className="mg-field-error">{state.fieldErrors.thumbnailUrl}</span>}
            </div>
            <div className="mg-project-copy">
              {BASIC_FIELDS.map(renderField)}
            </div>
          </div>
        </section>
        {GROUPS.map((group) => (
          <section key={group.title} className="sc-card mg-group">
            <div className="mg-group-head">
              <span className="sc-section-title">{group.title}</span>
              {group.note !== undefined && <span className="sc-note">{group.note}</span>}
            </div>
            {group.fields.map(renderField)}
          </section>
        ))}
      </form>
    </ManagementScreen>
  );
}
