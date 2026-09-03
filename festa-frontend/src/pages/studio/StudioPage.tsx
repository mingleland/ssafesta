// spec 005 Booth Studio 편집기의 조립 지점 — /app/studio/:boothId가 마운트하는 화면.
// 기능층(draft 쿼리·저장/게시 mutation·게이트·reducer)은 그대로, 표현은 BoothStudioShell 로 조립한다(S15P21A604-405).
import { useEffect, useMemo, useReducer, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { isApiError } from '../../shared/api/client';
import { layoutApi } from '../../entities/layout/api.select';
import type { BoothFacade } from '../../entities/booth/types';
import { BOOTH_SIZE_FALLBACK, MAX_OBJECTS_FALLBACK } from '../../shared/config/studio';
import { createInitialState, editorReducer } from '../../features/studio/model/editorReducer';
import { useSaveDraft, usePublish } from '../../features/studio/model/useLayoutMutations';
import { useStudioGates } from '../../features/studio/model/useStudioGates';
import { useCatalogItems } from '../../features/studio/model/catalog';
import { findFreeSpot } from '../../features/studio/lib/coords';
import { useOwnerGate } from '../../features/booth/model/useOwnerGate';
import type { StudioMode, TransformTool } from '../../features/studio/model/studioMode';
import type { PaletteItem, TemplatePreset } from '../../features/studio/model/visualAssets';
import { BoothStudioShell, StudioGate } from '../../features/studio/ui/shell/BoothStudioShell';
import { WORLD_RETURN_TO_MANAGEMENT } from '../../features/world/model/gameClientUi';
import { TopToolbar } from '../../features/studio/ui/shell/TopToolbar';
import { ModeRail } from '../../features/studio/ui/shell/ModeRail';
import { AssetPalette } from '../../features/studio/ui/shell/AssetPalette';
import { Inspector, InspectorEmpty } from '../../features/studio/ui/shell/Inspector';
import { TemplateInspector } from '../../features/studio/ui/shell/TemplateInspector';
import { StatusBar } from '../../features/studio/ui/shell/StatusBar';
import { BoothCanvasViewport } from '../../features/studio/ui/canvas/BoothCanvasViewport';
import type { BoothDecor } from '../../features/studio/ui/canvas/TemporaryIsoRenderer';
import { PropertiesPanel } from '../../features/studio/ui/PropertiesPanel';
import { PublishDialog, DetailList } from '../../features/studio/ui/PublishDialog';
import { FacadePanel } from '../../features/studio/ui/FacadePanel';

// 카탈로그 type — BE 시드는 AVATAR_PART 뿐(-167). 부스 장식 유형 값은 외부 확정 대기라 조회만 걸어 둔다
const CATALOG_TYPE = 'BOOTH_DECOR';
const ZOOM_STEPS = [0.8, 1, 1.2];

export function StudioPage() {
  const { boothId } = useParams<{ boothId: string }>();
  const navigate = useNavigate();
  const boothIdNum = Number(boothId);
  const [state, dispatch] = useReducer(editorReducer, boothIdNum, createInitialState);

  // ── Shell 표현 상태 (계약과 무관 — 저장 안 됨) ──
  const [mode, setMode] = useState<StudioMode>('layout');
  const [tool, setTool] = useState<TransformTool>('select');
  const [snapOn, setSnapOn] = useState(true);
  const [zoomIdx, setZoomIdx] = useState(1);
  const [preset, setPreset] = useState<TemplatePreset | null>(null);
  const [facadePreview, setFacadePreview] = useState<BoothFacade | null>(null);
  const [presetForFacade, setPresetForFacade] = useState<{ themeCode: BoothFacade['themeCode']; primaryColor: string } | null>(null);

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

  const catalogQuery = useCatalogItems(CATALOG_TYPE);

  const saveMutation = useSaveDraft(dispatch);
  const publishMutation = usePublish(dispatch);

  useEffect(() => {
    if (draftQuery.data === undefined) return; // 로딩 중
    // 미저장 편집 중에는 refetch가 와도 무시한다(T026). conflict 중 "새로고침" 클릭은 의도적 폐기라 통과시킨다.
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
    // state.dirty·state.conflict는 일부러 deps에서 뺀다(T026 실버그) — 상세는 이전 주석 이력
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [draftQuery.data, boothIdNum]);

  // 저장 실패(revision 충돌 제외) 시 첫 오류 대상 오브젝트를 자동 선택해 Inspector에서 바로 보이게 한다(T018)
  useEffect(() => {
    const err = saveMutation.error;
    if (isApiError(err) && err.code !== 'LAYOUT_REVISION_CONFLICT') {
      const target = err.errors.find((d) => d.objectId !== undefined);
      if (target?.objectId) dispatch({ type: 'SELECT_OBJECT', objectId: target.objectId });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [saveMutation.error]);

  const template = templatesQuery.data?.templates.find((t) => t.template === state.template);
  const bounds = template?.footprint ?? BOOTH_SIZE_FALLBACK;
  const maxObjects = template?.maxObjects ?? MAX_OBJECTS_FALLBACK;

  // G-1 Owner 가드(UX 보조 — 서버 FR-012 403이 최종 차단). ['my-booth'] 캐시를 SlotListPage와 공유
  const ownerGate = useOwnerGate(boothIdNum);

  const gates = useStudioGates({
    state,
    draftError: draftQuery.error,
    saveError: saveMutation.error,
    publishError: publishMutation.error,
    publishData: publishMutation.data,
    publishIsSuccess: publishMutation.isSuccess,
    publishIsError: publishMutation.isError,
    maxObjects,
    bounds,
  });

  // 캔버스 장식 — 외관 폼(실제 계약값) + 프리셋(목업). 저장값이 아닌 미리보기다
  const decor: BoothDecor = useMemo(() => {
    // 템플릿 모드에서는 고른 프리셋이 미리보기를 이긴다 — 그래야 프리셋 비교가 된다
    const primary = mode === 'template'
      ? preset?.primaryHex ?? facadePreview?.primaryColor ?? '#3B82F6'
      : facadePreview?.primaryColor ?? preset?.primaryHex ?? '#3B82F6';
    return {
      floorHex: preset?.floorHex ?? '#1d4ed8',
      wallHex: '#f3f5fa',
      primaryHex: primary,
      signText: facadePreview?.signText ?? 'Example',
      graphic: true,
    };
  }, [facadePreview, preset, mode]);

  if (ownerGate.status === 'loading') return <StudioGate>부스 소유 확인 중...</StudioGate>;
  if (ownerGate.status === 'not-owner') {
    return (
      <StudioGate>
        <p>이 부스의 소유자만 편집할 수 있습니다.</p>
        <Link to="/app/booths">부스 슬롯 목록으로</Link>
      </StudioGate>
    );
  }
  if (ownerGate.status === 'error') {
    return <StudioGate>부스 소유 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.</StudioGate>;
  }
  if (draftQuery.isLoading) return <StudioGate>불러오는 중...</StudioGate>;
  if (gates.draftLeaseExpired) return <StudioGate>임대가 만료되어 이 부스를 편집할 수 없습니다.</StudioGate>;
  if (draftQuery.isError) return <StudioGate>작업본을 불러오지 못했습니다.</StudioGate>;

  function handleSave() {
    saveMutation.mutate({
      boothId: boothIdNum,
      expectedRevision: state.baseRevision,
      template: state.template,
      objects: state.objects,
    });
  }

  function handleAdd(item: PaletteItem) {
    // 같은 지점에 쌓이면 선택도 드래그도 못 한다 — 빈 자리를 찾아 놓는다
    const spot = findFreeSpot(state.objects.map((o) => ({ x: o.position.x, z: o.position.z })), bounds);
    // ADD_OBJECT 가 새 오브젝트를 선택한다 — 장식형이면 레지스트리의 외형 코드를 함께 기록한다
    dispatch({ type: 'ADD_OBJECT', objectType: item.objectType, x: spot.x, z: spot.z, assetCode: item.assetCode });
  }

  const selectedObject = state.objects.find((o) => o.objectId === state.selectedObjectId);
  const canSave = state.dirty && !gates.leaseExpired && !state.conflict;
  const canPublish = !publishMutation.isPending && gates.preErrors.length === 0 && !gates.leaseExpired && !state.dirty;

  const inspectorBody = (() => {
    if (mode === 'facade') {
      return <FacadePanel boothId={boothIdNum} onChange={setFacadePreview} preset={presetForFacade} />;
    }
    if (mode === 'template') {
      return (
        <TemplateInspector
          preset={preset}
          onApplyToFacade={() => {
            if (preset) setPresetForFacade({ themeCode: preset.themeCode, primaryColor: preset.primaryHex });
            setMode('facade');
          }}
        />
      );
    }
    if (!selectedObject) {
      return <InspectorEmpty>캔버스에서 오브젝트를 선택하거나<br />팔레트에서 추가하세요.</InspectorEmpty>;
    }
    return (
      <PropertiesPanel
        object={selectedObject}
        bounds={bounds}
        onMove={(x, z) => dispatch({ type: 'MOVE_OBJECT', objectId: selectedObject.objectId, x, z })}
        onRotate={(rotationY) => dispatch({ type: 'ROTATE_OBJECT', objectId: selectedObject.objectId, rotationY })}
        onLinkContent={(configId) => dispatch({ type: 'LINK_CONTENT', objectId: selectedObject.objectId, configId })}
        onSetAssetCode={(assetCode) => dispatch({ type: 'SET_ASSET_CODE', objectId: selectedObject.objectId, assetCode })}
        onRemove={() => dispatch({ type: 'REMOVE_OBJECT', objectId: selectedObject.objectId })}
      />
    );
  })();

  const inspectorTitle = mode === 'facade' ? '부스 외관' : mode === 'template' ? (preset ? `${preset.label} 프리셋` : '템플릿') : selectedObject ? `오브젝트 ${state.objects.indexOf(selectedObject) + 1}` : '선택 없음';

  // 저장 오류·공개 전 확인·충돌 안내 — 캔버스 좌상단 HUD 로 노출한다(흰 페이지 배너 대신)
  const hud = (
    <>
      {state.conflict && (
        <span className="studio-chip studio-chip-warn">
          다른 편집자가 저장했습니다
          <button type="button" className="studio-btn studio-btn-sm" onClick={() => draftQuery.refetch()}>새로고침</button>
        </span>
      )}
      {gates.leaseExpired && <span className="studio-chip studio-chip-warn">임대가 만료되어 더 이상 편집할 수 없습니다</span>}
    </>
  );

  return (
    <BoothStudioShell
      toolbar={
        <TopToolbar
          boothName={`부스 #${boothIdNum}`}
          saveStatus={state.saveStatus}
          dirty={state.dirty}
          conflict={state.conflict}
          leaseExpired={gates.leaseExpired}
          canSave={canSave}
          canPublish={canPublish}
          publishing={publishMutation.isPending}
          publishedVersion={state.publishedVersion ?? null}
          zoomPercent={Math.round(ZOOM_STEPS[zoomIdx] * 100)}
          onBack={() => navigate(WORLD_RETURN_TO_MANAGEMENT)}
          onSave={handleSave}
          onPublish={() => publishMutation.mutate(boothIdNum)}
          onZoomToggle={() => setZoomIdx((i) => (i + 1) % ZOOM_STEPS.length)}
        />
      }
      rail={<ModeRail mode={mode} onChange={setMode} />}
      palette={
        <AssetPalette
          mode={mode}
          currentCount={state.objects.length}
          maxObjects={maxObjects}
          catalog={catalogQuery.data ?? []}
          activePresetId={preset?.id ?? null}
          onAddObject={handleAdd}
          onPickPreset={setPreset}
        />
      }
      canvas={
        <BoothCanvasViewport
          objects={state.objects}
          selectedObjectId={state.selectedObjectId}
          bounds={bounds}
          zoom={ZOOM_STEPS[zoomIdx]}
          tool={tool}
          snapOn={snapOn}
          decor={decor}
          hudLeft={hud}
          onSelect={(objectId) => dispatch({ type: 'SELECT_OBJECT', objectId })}
          onMove={(objectId, x, z) => dispatch({ type: 'MOVE_OBJECT', objectId, x, z })}
          onRotate={(objectId, rotationY) => dispatch({ type: 'ROTATE_OBJECT', objectId, rotationY })}
          onTool={setTool}
          onSnapToggle={() => setSnapOn((s) => !s)}
          onFrame={() => setZoomIdx(1)}
        />
      }
      inspector={
        <Inspector title={inspectorTitle} thumb={mode === 'layout' && selectedObject ? 'panel' : undefined}>
          {inspectorBody}
          {mode === 'layout' && gates.saveErrorDetails.length > 0 && (
            <div className="studio-group">
              <p className="studio-group-title" data-tone="plain">저장하지 못했습니다</p>
              <DetailList items={gates.saveErrorDetails} />
            </div>
          )}
          {mode === 'layout' && !gates.showPublishResult && (gates.preErrors.length > 0 || gates.preWarnings.length > 0) && (
            <div className="studio-group">
              <p className="studio-group-title" data-tone="plain">공개 전 확인</p>
              <DetailList items={gates.preErrors} />
              <DetailList items={gates.preWarnings} />
            </div>
          )}
          {mode === 'layout' && state.dirty && <p className="studio-note" style={{ padding: '0 14px 12px' }}>저장하지 않은 변경 사항이 있습니다. 저장 후 게시할 수 있습니다.</p>}
        </Inspector>
      }
      status={<StatusBar count={state.objects.length} max={maxObjects} saveStatus={state.saveStatus} dirty={state.dirty} tool={tool} snap={snapOn} />}
      overlay={
        gates.showPublishResult ? (
          <PublishDialog errors={gates.publishErrors} warnings={gates.publishWarnings} published={publishMutation.isSuccess} onClose={() => publishMutation.reset()} />
        ) : undefined
      }
    />
  );
}
