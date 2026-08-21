// spec 005 Booth Studio 편집기의 조립 지점 — /app/studio/:boothId가 마운트하는 화면
import { useEffect, useReducer } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useParams } from 'react-router-dom';
import { isApiError } from '../../shared/api/client';
import type { ApiErrorDetail } from '../../shared/api/client';
import * as realApi from '../../entities/layout/api';
import * as mockApi from '../../entities/layout/api.mock';
import { BOOTH_SIZE_FALLBACK, MAX_OBJECTS_FALLBACK } from '../../shared/config/studio';
import { createInitialState, editorReducer } from '../../features/studio/model/editorReducer';
import { useSaveDraft, usePublish } from '../../features/studio/model/useLayoutMutations';
import { EditorCanvas } from '../../features/studio/ui/EditorCanvas';
import { ObjectPalette } from '../../features/studio/ui/ObjectPalette';
import { PublishDialog } from '../../features/studio/ui/PublishDialog';

// VITE_USE_MOCK=true면 실 BE 없이 메모리 mock으로 개발한다 (FE/research.md R-09)
const layoutApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;

export function StudioPage() {
  const { boothId } = useParams<{ boothId: string }>();
  const boothIdNum = Number(boothId);
  const [state, dispatch] = useReducer(editorReducer, boothIdNum, createInitialState);

  const draftQuery = useQuery({
    queryKey: ['layout-draft', boothIdNum],
    queryFn: () => layoutApi.getDraft(boothIdNum),
    enabled: Number.isFinite(boothIdNum),
  });

  const templatesQuery = useQuery({
    queryKey: ['layout-templates'],
    queryFn: () => layoutApi.getTemplates(),
  });

  const saveMutation = useSaveDraft(dispatch);
  const publishMutation = usePublish(dispatch);

  useEffect(() => {
    if (draftQuery.data === undefined) return; // 로딩 중
    if (draftQuery.data === null) {
      // 204 — 작업본 없음: 빈 배치로 시작, 첫 저장은 expectedRevision:0 (계약 §2)
      dispatch({ type: 'LOAD_DRAFT', boothId: boothIdNum, template: 'PROJECT_EXHIBITION', objects: [], revision: 0, publishedVersion: null });
      return;
    }
    dispatch({
      type: 'LOAD_DRAFT',
      boothId: boothIdNum,
      template: draftQuery.data.template,
      objects: draftQuery.data.objects,
      revision: draftQuery.data.revision,
      publishedVersion: draftQuery.data.publishedVersion,
    });
  }, [draftQuery.data, boothIdNum]);

  if (draftQuery.isLoading) return <div>불러오는 중...</div>;
  if (draftQuery.isError) return <div>작업본을 불러오지 못했습니다.</div>;

  const template = templatesQuery.data?.templates.find((t) => t.template === state.template);
  const bounds = template?.footprint ?? BOOTH_SIZE_FALLBACK;
  const maxObjects = template?.maxObjects ?? MAX_OBJECTS_FALLBACK;

  function handleSave() {
    saveMutation.mutate({
      boothId: boothIdNum,
      expectedRevision: state.baseRevision,
      template: state.template,
      objects: state.objects,
    });
  }

  function handleReload() {
    draftQuery.refetch();
  }

  const publishError = publishMutation.error;
  const publishErrors: ApiErrorDetail[] = isApiError(publishError) ? publishError.errors : [];
  const publishWarnings = publishMutation.data?.warnings ?? [];
  const showPublishResult = publishMutation.isSuccess || publishMutation.isError;

  return (
    <div>
      <p>저장 상태: {state.saveStatus}</p>
      {state.saveStatus === 'conflict' && (
        <p>
          다른 편집자가 저장했습니다. <button type="button" onClick={handleReload}>새로고침</button>
        </p>
      )}
      {state.publishedVersion !== null && state.publishedVersion !== undefined
        ? <p>공개 회차: {state.publishedVersion}</p>
        : <p>비공개</p>}

      <ObjectPalette
        currentCount={state.objects.length}
        maxObjects={maxObjects}
        onAdd={(objectType) => dispatch({ type: 'ADD_OBJECT', objectType, x: 0, z: 0 })}
      />

      <EditorCanvas
        objects={state.objects}
        selectedObjectId={state.selectedObjectId}
        bounds={bounds}
        onSelect={(objectId) => dispatch({ type: 'SELECT_OBJECT', objectId })}
        onMove={(objectId, x, z) => dispatch({ type: 'MOVE_OBJECT', objectId, x, z })}
      />

      <button type="button" onClick={handleSave} disabled={!state.dirty}>
        저장
      </button>
      <button type="button" onClick={() => publishMutation.mutate(boothIdNum)} disabled={publishMutation.isPending}>
        공개
      </button>

      {showPublishResult && (
        <PublishDialog
          errors={publishErrors}
          warnings={publishWarnings}
          published={publishMutation.isSuccess}
          onClose={() => publishMutation.reset()}
        />
      )}
    </div>
  );
}
