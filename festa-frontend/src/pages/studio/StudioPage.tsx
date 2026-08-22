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
import { PropertiesPanel } from '../../features/studio/ui/PropertiesPanel';
import { PublishDialog, DetailList } from '../../features/studio/ui/PublishDialog';
import { precheckErrors, precheckWarnings } from '../../features/studio/lib/validate';

// VITE_USE_MOCK=true면 실 BE 없이 메모리 mock으로 개발한다 (FE/research.md R-09)
const layoutApi = import.meta.env.VITE_USE_MOCK === 'true' ? mockApi : realApi;

// draftQuery·save·publish 세 경로 어디서든 BOOTH_LEASE_EXPIRED가 뜰 수 있다(만료된 부스에 진입·저장·공개 시도).
// 편집을 전부 막는 게 목적이라 한 곳에서 판정한다(T018).
function isLeaseExpired(...errors: unknown[]): boolean {
  return errors.some((e) => isApiError(e) && e.code === 'BOOTH_LEASE_EXPIRED');
}

export function StudioPage() {
  const { boothId } = useParams<{ boothId: string }>();
  const boothIdNum = Number(boothId);
  const [state, dispatch] = useReducer(editorReducer, boothIdNum, createInitialState);

  const draftQuery = useQuery({
    queryKey: ['layout-draft', boothIdNum],
    queryFn: () => layoutApi.getDraft(boothIdNum),
    enabled: Number.isFinite(boothIdNum),
    // 탭 전환·창 복귀 시 자동 refetch가 미저장 편집을 서버본으로 덮어쓰던 결함(T026) — 이 쿼리에서만 끈다
    refetchOnWindowFocus: false,
  });

  const templatesQuery = useQuery({
    queryKey: ['layout-templates'],
    queryFn: () => layoutApi.getTemplates(),
  });

  const saveMutation = useSaveDraft(dispatch);
  const publishMutation = usePublish(dispatch);

  useEffect(() => {
    if (draftQuery.data === undefined) return; // 로딩 중
    // 미저장 편집 중에는 refetch가 와도 무시한다 — refetchOnWindowFocus 외에도 invalidateQueries
    // 레이스로 새 데이터가 도착할 수 있다(T026). conflict 중 "새로고침" 클릭은 의도적 폐기라 통과시킨다.
    if (state.dirty && !state.conflict) return;
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
  }, [draftQuery.data, boothIdNum, state.dirty, state.conflict]);

  // 저장 실패(revision 충돌 제외) 시 첫 오류 대상 오브젝트를 자동 선택해 PropertiesPanel에서 바로 보이게 한다(T018)
  useEffect(() => {
    const err = saveMutation.error;
    if (isApiError(err) && err.code !== 'LAYOUT_REVISION_CONFLICT') {
      const target = err.errors.find((d) => d.objectId !== undefined);
      if (target?.objectId) dispatch({ type: 'SELECT_OBJECT', objectId: target.objectId });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saveMutation.error]);

  if (draftQuery.isLoading) return <div>불러오는 중...</div>;
  if (isLeaseExpired(draftQuery.error)) {
    return <div>임대가 만료되어 이 부스를 편집할 수 없습니다.</div>;
  }
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

  // LAYOUT_VALIDATION_FAILED 등 conflict가 아닌 저장 실패의 상세 — conflict는 위에서 별도 안내로 처리한다(T018)
  const saveError = saveMutation.error;
  const saveErrorDetails: ApiErrorDetail[] =
    isApiError(saveError) && saveError.code !== 'LAYOUT_REVISION_CONFLICT' ? saveError.errors : [];

  const leaseExpired = isLeaseExpired(saveError, publishError);

  // 공개 요청 전 미리보기 — 서버 응답이 오면(showPublishResult) 그 값으로 교체된다(T016)
  const preErrors = precheckErrors(state.objects, maxObjects);
  const preWarnings = precheckWarnings(state.objects);
  const selectedObject = state.objects.find((o) => o.objectId === state.selectedObjectId);

  return (
    <div>
      <p>저장 상태: {state.saveStatus}</p>
      {state.conflict && (
        <p>
          다른 편집자가 저장했습니다. <button type="button" onClick={handleReload}>새로고침</button>
        </p>
      )}
      {leaseExpired && <p>임대가 만료되어 더 이상 편집할 수 없습니다.</p>}
      {saveErrorDetails.length > 0 && (
        <div>
          <p>저장하지 못했습니다</p>
          <DetailList items={saveErrorDetails} />
        </div>
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

      {selectedObject && (
        <PropertiesPanel
          object={selectedObject}
          bounds={bounds}
          onMove={(x, z) => dispatch({ type: 'MOVE_OBJECT', objectId: selectedObject.objectId, x, z })}
          onRotate={(rotationY) => dispatch({ type: 'ROTATE_OBJECT', objectId: selectedObject.objectId, rotationY })}
          onLinkContent={(configId) => dispatch({ type: 'LINK_CONTENT', objectId: selectedObject.objectId, configId })}
          onSetAssetCode={(assetCode) => dispatch({ type: 'SET_ASSET_CODE', objectId: selectedObject.objectId, assetCode })}
          onRemove={() => dispatch({ type: 'REMOVE_OBJECT', objectId: selectedObject.objectId })}
        />
      )}

      {/* conflict 중 저장 버튼 disable — 낡은 baseRevision으로 재시도하면 같은 409가 반복된다. 재로드가 유일한 출구 */}
      <button type="button" onClick={handleSave} disabled={!state.dirty || leaseExpired || state.conflict}>
        저장
      </button>

      {!showPublishResult && (preErrors.length > 0 || preWarnings.length > 0) && (
        <div>
          <p>공개 전 확인</p>
          <DetailList items={preErrors} />
          <DetailList items={preWarnings} />
        </div>
      )}

      {/* dirty 상태로 공개하면 서버 draft(마지막 저장본)가 공개되는데 미리보기는 로컬 state 기준이라 어긋난다(T026) */}
      {state.dirty && <p>저장하지 않은 변경 사항이 있습니다. 저장 후 공개할 수 있습니다.</p>}
      <button
        type="button"
        onClick={() => publishMutation.mutate(boothIdNum)}
        disabled={publishMutation.isPending || preErrors.length > 0 || leaseExpired || state.dirty}
      >
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
