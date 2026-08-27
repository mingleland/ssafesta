import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ChangeEvent,
} from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { parseGameProject, type AssetReference, type GameObject, type GameProject } from '../../contracts/gameProject.ts';
import {
  addDialogueScene,
  addAssetReference,
  addObject,
  addTileLayer,
  addTopDownScene,
  addPlatformerScene,
  copyObjectsToScene,
  duplicateScene,
  duplicateObjects,
  fillTileLayer,
  floodFillTiles,
  moveScene,
  moveObjects,
  nextStableId,
  paintTiles,
  removeObjects,
  removeScene,
  replaceComponent,
  renameProject,
  sceneRemovalReason,
  withBuiltinAssetLibrary,
} from '../model/authoringCommands.ts';
import { PRESET_DEFINITIONS } from '../model/authoringRegistry.ts';
import { createStarterProject } from '../model/createStarterProject.ts';
import { createProjectFromTemplate, PROJECT_TEMPLATES, type ProjectTemplateId } from '../model/projectTemplates.ts';
import { createBrowserAssetRepository, type GameAssetRepository } from '../assets/localAssetRepository.ts';
import { useResolvedAssetUrls } from '../assets/useResolvedAssetUrls.ts';
import { resolveTilesetVisual, tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import { createBrowserDraftRepository, type DraftSaveReceipt, type GameDraftRepository } from '../ports/draftRepository.ts';
import { GameAuthoringApiError, type GamePublisher } from '../ports/gameAuthoringApi.ts';
import {
  createBrowserRecoveryJournal,
  shouldOfferRecovery,
  type RecoverySnapshot,
} from '../ports/localRecoveryJournal.ts';
import { findPublishBlockers } from '../ports/publishValidation.ts';
import { createGameProjectStore } from '../store/gameProjectStore.ts';
import { CommitInput } from './CommitInput.tsx';
import { DialogueEditor } from './DialogueEditor.tsx';
import { EventEditor } from './EventEditor.tsx';
import { InspectorPanel } from './InspectorPanel.tsx';
import { ObjectLayerPanel } from './ObjectLayerPanel.tsx';
import { ProjectDataPanel } from './ProjectDataPanel.tsx';
import { TopDownCanvas, type CanvasTool, type TileTool } from './TopDownCanvas.tsx';
import './GameStudioShell.css';

type RightPanel = 'PROPERTIES' | 'EVENTS' | 'PROJECT';
type SaveStatus = 'loading' | 'clean' | 'dirty' | 'saving' | 'saved' | 'publishing' | 'published' | 'error';

const tutorialDismissalKey = (gameId: number) => `festa.game-studio.onboarding.v3.${gameId}`;

const TUTORIAL_STEPS = [
  { title: '게임이 시작될 맵을 확인하세요', copy: 'START 표시가 있는 맵이 플레이어가 처음 만나는 화면입니다. 탐색 맵과 플랫폼 맵 모두 같은 방식입니다.' },
  { title: '바꾸고 싶은 오브젝트를 선택하세요', copy: '캐릭터, 문, 발판, 적처럼 맵에 놓인 요소를 하나 선택하면 오른쪽에서 바로 수정할 수 있습니다.' },
  { title: '모습을 내 기획에 맞게 바꿔 보세요', copy: '속성의 재료함에서 제공 이미지를 고르세요. 꼭 필요한 오브젝트만 내 이미지로 교체할 수도 있습니다.' },
  { title: '게임의 재미를 만드는 동작을 확인하세요', copy: '이벤트 탭에서 말 걸기, 아이템 획득, 잠긴 문, 목표 도착 같은 규칙을 한국어로 조합합니다.' },
  { title: '저장하고 직접 플레이하세요', copy: '저장한 뒤 플레이 테스트에서 처음부터 끝까지 해보세요. 편집본은 게시 전까지 다른 사용자에게 공개되지 않습니다.' },
] as const;

const shouldShowFirstVisitGuide = (gameId: number): boolean => {
  try {
    return window.localStorage.getItem(tutorialDismissalKey(gameId)) !== 'done';
  } catch {
    return true;
  }
};

const rememberGuideSeen = (gameId: number): void => {
  try {
    window.localStorage.setItem(tutorialDismissalKey(gameId), 'done');
  } catch {
    // 저장소가 차단된 브라우저에서도 안내 자체는 정상 동작한다.
  }
};

const editorSetStorageKey = (gameId: number, kind: 'hidden' | 'locked') => `festa.game-studio.editor.${gameId}.${kind}`;

const loadEditorSet = (gameId: number, kind: 'hidden' | 'locked'): ReadonlySet<string> => {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(editorSetStorageKey(gameId, kind)) ?? '[]');
    return new Set(Array.isArray(parsed) ? parsed.filter((value): value is string => typeof value === 'string') : []);
  } catch {
    return new Set();
  }
};

const saveEditorSet = (gameId: number, kind: 'hidden' | 'locked', ids: ReadonlySet<string>): void => {
  try {
    window.localStorage.setItem(editorSetStorageKey(gameId, kind), JSON.stringify([...ids]));
  } catch {
    // 편집 보조 상태 저장 실패는 GameProject 저장을 방해하지 않는다.
  }
};

interface GameStudioShellProps {
  readonly gameId: number;
  readonly initialProject?: GameProject;
  readonly repository?: GameDraftRepository | null;
  readonly publisher?: GamePublisher | null;
  readonly persistenceLabel?: string;
  readonly assetRepository?: GameAssetRepository | null;
}

const saveLabel: Readonly<Record<SaveStatus, string>> = {
  loading: '불러오는 중',
  clean: '초안 준비됨',
  dirty: '저장되지 않은 변경',
  saving: '저장 중',
  saved: '저장 완료',
  publishing: '게시 중',
  published: '게시 완료',
  error: '확인 필요',
};

const downloadProject = (project: GameProject): void => {
  const blob = new Blob([JSON.stringify(project, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = `festa-game-${project.gameId}-r${project.revision}.json`;
  anchor.click();
  URL.revokeObjectURL(url);
};

export const GameStudioShell = ({
  gameId,
  initialProject,
  repository: repositoryProp,
  publisher = null,
  persistenceLabel = '브라우저',
  assetRepository: assetRepositoryProp,
}: GameStudioShellProps) => {
  const navigate = useNavigate();
  const repository = useMemo(
    () => repositoryProp === undefined ? createBrowserDraftRepository() : repositoryProp,
    [repositoryProp],
  );
  const assetRepository = useMemo(
    () => assetRepositoryProp === undefined ? createBrowserAssetRepository() : assetRepositoryProp,
    [assetRepositoryProp],
  );
  const previewRepository = useMemo(() => createBrowserDraftRepository(), []);
  const recoveryJournal = useMemo(() => createBrowserRecoveryJournal(), []);
  const store = useMemo(() => createGameProjectStore(initialProject ?? createStarterProject(gameId)), [gameId, initialProject]);
  const snapshot = useSyncExternalStore(store.subscribe, store.getState, store.getState);
  const project = snapshot.project;
  const assetUrls = useResolvedAssetUrls(project.assets, assetRepository);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedSceneId, setSelectedSceneId] = useState(project.startSceneId);
  const [selectedObjectId, setSelectedObjectId] = useState<string | null>(null);
  const [selectedObjectIds, setSelectedObjectIds] = useState<ReadonlySet<string>>(new Set());
  const [placementPreset, setPlacementPreset] = useState<GameObject['preset'] | null>(null);
  const [paletteMode, setPaletteMode] = useState<'OBJECTS' | 'TILES'>('OBJECTS');
  const [objectCategory, setObjectCategory] = useState<'전체' | '기본' | '캐릭터' | '상호작용' | '액션' | '장식'>('전체');
  const [objectSearch, setObjectSearch] = useState('');
  const [selectedLayerId, setSelectedLayerId] = useState<string | null>(null);
  const [tileBrush, setTileBrush] = useState<number | null>(null);
  const [tileTool, setTileTool] = useState<TileTool>('BRUSH');
  const [rightPanel, setRightPanel] = useState<RightPanel>('PROPERTIES');
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('loading');
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [lastPublishedVersion, setLastPublishedVersion] = useState<number | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [zoom, setZoom] = useState(100);
  const [fitRequestToken, setFitRequestToken] = useState(0);
  const [focusRequestToken, setFocusRequestToken] = useState(0);
  const [canvasTool, setCanvasTool] = useState<CanvasTool>('SELECT');
  const [showGrid, setShowGrid] = useState(true);
  const [showCollisions, setShowCollisions] = useState(false);
  const [showGuide, setShowGuide] = useState(() => shouldShowFirstVisitGuide(gameId));
  const [showTemplates, setShowTemplates] = useState(false);
  const [pendingTemplateId, setPendingTemplateId] = useState<ProjectTemplateId | null>(null);
  const [tutorialStep, setTutorialStep] = useState<number | null>(null);
  const [showLayers, setShowLayers] = useState(false);
  const [focusMode, setFocusMode] = useState(false);
  const [editorHiddenObjectIds, setEditorHiddenObjectIds] = useState<ReadonlySet<string>>(() => loadEditorSet(gameId, 'hidden'));
  const [editorLockedObjectIds, setEditorLockedObjectIds] = useState<ReadonlySet<string>>(() => loadEditorSet(gameId, 'locked'));
  const [objectClipboard, setObjectClipboard] = useState<{ readonly sourceSceneId: string; readonly objectIds: readonly string[] } | null>(null);
  const [draftConflict, setDraftConflict] = useState<{ readonly currentRevision: number; readonly localProject: GameProject } | null>(null);
  const [recoveryCandidate, setRecoveryCandidate] = useState<RecoverySnapshot | null>(null);
  const [recoverySavedAt, setRecoverySavedAt] = useState<string | null>(null);
  const recoveryFailureReported = useRef(false);

  const selectedScene = project.scenes.find((scene) => scene.id === selectedSceneId) ?? project.scenes[0];
  const selectedObject = selectedScene !== undefined && selectedScene.type !== 'DIALOGUE'
    ? selectedScene.objects.find((object) => object.id === selectedObjectId) ?? null
    : null;
  const selectedTileLayer = selectedScene !== undefined && selectedScene.type !== 'DIALOGUE'
    ? selectedScene.tileLayers.find((layer) => layer.id === selectedLayerId) ?? selectedScene.tileLayers[0] ?? null
    : null;
  const selectedTilesetVisual = resolveTilesetVisual(
    selectedTileLayer === null ? undefined : project.assets.find((asset) => asset.id === selectedTileLayer.tilesetAssetId),
    assetUrls,
  );

  useEffect(() => {
    let active = true;
    if (repository === null) {
      const currentProject = store.getState().project;
      const recovery = recoveryJournal?.load(gameId) ?? null;
      if (recovery !== null && shouldOfferRecovery(recovery, currentProject)) {
        setRecoveryCandidate(recovery);
      } else if (recovery !== null) {
        recoveryJournal?.clear(gameId);
      }
      setSaveStatus('clean');
      setHasUnsavedChanges(false);
      setNotice('브라우저 저장소를 사용할 수 없습니다. JSON 내보내기를 이용하세요.');
      return () => { active = false; };
    }
    setSaveStatus('loading');
    repository.load(gameId)
      .then((draft) => {
        if (!active) return;
        let loadedProject = store.getState().project;
        if (draft !== null) {
          const upgradedDraft = withBuiltinAssetLibrary(draft);
          store.reset(upgradedDraft);
          setSelectedSceneId(upgradedDraft.startSceneId);
          loadedProject = upgradedDraft;
          setNotice(`${persistenceLabel}에 저장한 초안을 불러왔습니다.`);
        }
        const recovery = recoveryJournal?.load(gameId) ?? null;
        if (recovery !== null && shouldOfferRecovery(recovery, loadedProject)) {
          setRecoveryCandidate(recovery);
        } else if (recovery !== null) {
          recoveryJournal?.clear(gameId);
        }
        setSaveStatus('clean');
        setHasUnsavedChanges(false);
      })
      .catch((error: unknown) => {
        if (!active) return;
        setSaveStatus('error');
        setNotice(error instanceof Error ? error.message : '초안을 불러오지 못했습니다.');
      });
    return () => { active = false; };
  }, [gameId, persistenceLabel, recoveryJournal, repository, store]);

  useEffect(() => {
    if (!hasUnsavedChanges || saveStatus === 'loading' || saveStatus === 'saving' || recoveryJournal === null || recoveryCandidate !== null) return undefined;
    const projectToRecover = project;
    const timer = window.setTimeout(() => {
      try {
        const recovery = recoveryJournal.save(projectToRecover);
        setRecoverySavedAt(recovery.savedAt);
        recoveryFailureReported.current = false;
      } catch {
        if (!recoveryFailureReported.current) {
          recoveryFailureReported.current = true;
          setNotice('이 기기의 임시 복구 저장 공간이 부족합니다. JSON 내보내기로 변경을 보관해 주세요.');
        }
      }
    }, 800);
    return () => window.clearTimeout(timer);
  }, [hasUnsavedChanges, project, recoveryCandidate, recoveryJournal, saveStatus]);

  useEffect(() => {
    if (!hasUnsavedChanges && draftConflict === null) return undefined;
    const warnBeforeLeaving = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warnBeforeLeaving);
    return () => window.removeEventListener('beforeunload', warnBeforeLeaving);
  }, [draftConflict, hasUnsavedChanges]);

  useEffect(() => saveEditorSet(gameId, 'hidden', editorHiddenObjectIds), [editorHiddenObjectIds, gameId]);
  useEffect(() => saveEditorSet(gameId, 'locked', editorLockedObjectIds), [editorLockedObjectIds, gameId]);

  useEffect(() => {
    if (selectedScene !== undefined) return;
    setSelectedSceneId(project.startSceneId);
    setSelectedObjectId(null);
    setSelectedObjectIds(new Set());
  }, [project.startSceneId, selectedScene]);

  const apply = useCallback((nextProject: GameProject) => {
    try {
      store.replace(nextProject);
      setHasUnsavedChanges(true);
      setSaveStatus('dirty');
      setNotice(null);
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '편집 내용을 적용하지 못했습니다.');
    }
  }, [store]);

  const selectObjects = useCallback((objectIds: readonly string[], primaryObjectId: string | null) => {
    const nextIds = new Set(objectIds);
    setSelectedObjectIds(nextIds);
    setSelectedObjectId(primaryObjectId !== null && nextIds.has(primaryObjectId)
      ? primaryObjectId
      : objectIds.at(-1) ?? null);
    if (nextIds.size > 0) setRightPanel('PROPERTIES');
  }, []);

  const clearObjectSelection = useCallback(() => {
    setSelectedObjectIds(new Set());
    setSelectedObjectId(null);
  }, []);

  const duplicateSelection = useCallback(() => {
    const currentProject = store.getState().project;
    const scene = currentProject.scenes.find((candidate) => candidate.id === selectedSceneId);
    if (scene === undefined || scene.type === 'DIALOGUE') return;
    const objectIds = [...selectedObjectIds].filter((id) => !editorLockedObjectIds.has(id));
    if (objectIds.length === 0) {
      setNotice('복제할 오브젝트를 선택해 주세요. 잠긴 오브젝트는 복제하지 않습니다.');
      return;
    }
    try {
      const result = duplicateObjects(currentProject, scene.id, objectIds);
      apply(result.project);
      selectObjects(result.objectIds, result.objectIds.at(-1) ?? null);
      setNotice(`${result.objectIds.length}개 오브젝트와 연결된 동작을 복제했습니다.`);
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '오브젝트를 복제하지 못했습니다.');
    }
  }, [apply, editorLockedObjectIds, selectObjects, selectedObjectIds, selectedSceneId, store]);

  const copySelection = useCallback(() => {
    const currentProject = store.getState().project;
    const scene = currentProject.scenes.find((candidate) => candidate.id === selectedSceneId);
    if (scene === undefined || scene.type === 'DIALOGUE') return;
    const objectIds = [...selectedObjectIds].filter((id) => (
      !editorLockedObjectIds.has(id) && scene.objects.some((object) => object.id === id && object.preset !== 'PLAYER_SPAWN')
    ));
    if (objectIds.length === 0) {
      setNotice('복사할 오브젝트를 선택해 주세요. 시작점과 잠긴 오브젝트는 제외됩니다.');
      return;
    }
    setObjectClipboard({ sourceSceneId: scene.id, objectIds });
    setNotice(`${objectIds.length}개 오브젝트를 복사했습니다. 다른 Scene에서도 붙여넣을 수 있습니다.`);
  }, [editorLockedObjectIds, selectedObjectIds, selectedSceneId, store]);

  const pasteSelection = useCallback(() => {
    const currentProject = store.getState().project;
    const scene = currentProject.scenes.find((candidate) => candidate.id === selectedSceneId);
    if (scene === undefined || scene.type === 'DIALOGUE') {
      setNotice('오브젝트는 탐색 맵이나 플랫폼 맵에만 붙여넣을 수 있습니다.');
      return;
    }
    if (objectClipboard === null) {
      setNotice('먼저 복사할 오브젝트를 선택하고 Ctrl+C를 눌러 주세요.');
      return;
    }
    try {
      const result = copyObjectsToScene(
        currentProject,
        objectClipboard.sourceSceneId,
        scene.id,
        objectClipboard.objectIds,
      );
      apply(result.project);
      selectObjects(result.objectIds, result.objectIds.at(-1) ?? null);
      setNotice(`${result.objectIds.length}개 오브젝트와 연결된 동작을 ${scene.name}에 붙여넣었습니다.`);
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '오브젝트를 붙여넣지 못했습니다.');
    }
  }, [apply, objectClipboard, selectObjects, selectedSceneId, store]);

  const deleteSelection = useCallback(() => {
    const currentProject = store.getState().project;
    const scene = currentProject.scenes.find((candidate) => candidate.id === selectedSceneId);
    if (scene === undefined || scene.type === 'DIALOGUE') return;
    const objectIds = [...selectedObjectIds].filter((id) => !editorLockedObjectIds.has(id));
    if (objectIds.length === 0) {
      setNotice('삭제할 오브젝트를 선택해 주세요. 잠긴 오브젝트는 삭제하지 않습니다.');
      return;
    }
    try {
      const result = removeObjects(currentProject, scene.id, objectIds);
      if (result.removedObjectIds.length > 0) apply(result.project);
      const removedIds = new Set(result.removedObjectIds);
      const remaining = [...selectedObjectIds].filter((id) => !removedIds.has(id));
      selectObjects(remaining, remaining.at(-1) ?? null);
      if (result.blocked.length > 0) {
        setNotice(`${result.removedObjectIds.length}개 삭제 · ${result.blocked.length}개는 시작점 또는 다른 동작 참조 때문에 유지했습니다.`);
      } else {
        setNotice(`${result.removedObjectIds.length}개 오브젝트를 삭제했습니다.`);
      }
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '오브젝트를 삭제하지 못했습니다.');
    }
  }, [apply, editorLockedObjectIds, selectObjects, selectedObjectIds, selectedSceneId, store]);

  const uploadAsset = useCallback(async (
    kind: AssetReference['kind'],
    file: File,
  ): Promise<AssetReference | null> => {
    if (assetRepository === null) {
      setSaveStatus('error');
      setNotice('이 브라우저에서는 로컬 자산 저장소를 사용할 수 없습니다.');
      return null;
    }
    try {
      const assetId = nextStableId(store.getState().project, kind === 'TILESET' ? 'tileset' : 'image');
      const result = await assetRepository.save(gameId, { file, kind, suggestedAssetId: assetId });
      apply(addAssetReference(store.getState().project, result.asset));
      setNotice(`${result.originalName}을 ${kind} 자산으로 추가했습니다.`);
      return result.asset;
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '자산을 추가하지 못했습니다.');
      return null;
    }
  }, [apply, assetRepository, gameId, store]);

  const save = useCallback(async (): Promise<DraftSaveReceipt | null> => {
    try {
      parseGameProject(store.getState().project);
      if (repository === null) {
        setSaveStatus('error');
        setNotice('브라우저 저장소를 사용할 수 없습니다. JSON으로 내보내 보관하세요.');
        return null;
      }
      setSaveStatus('saving');
      const receipt = await repository.save(store.getState().project);
      store.syncRevision(receipt.revision);
      recoveryJournal?.clear(gameId);
      setRecoveryCandidate(null);
      setRecoverySavedAt(null);
      setDraftConflict(null);
      setHasUnsavedChanges(false);
      setSaveStatus('saved');
      const warningCopy = receipt.warnings?.length ? ` · 확인 ${receipt.warnings.length}건` : '';
      setNotice(`${new Date(receipt.savedAt).toLocaleTimeString('ko-KR')}에 ${persistenceLabel} 초안을 저장했습니다${warningCopy}.`);
      return receipt;
    } catch (error) {
      setSaveStatus('error');
      if (error instanceof GameAuthoringApiError && error.currentRevision !== undefined) {
        setDraftConflict({ currentRevision: error.currentRevision, localProject: store.getState().project });
        setNotice(`${error.message} 현재 서버 revision은 ${error.currentRevision}입니다.`);
      } else {
        setNotice(error instanceof Error ? error.message : '저장 전 검증에 실패했습니다.');
      }
      return null;
    }
  }, [gameId, persistenceLabel, recoveryJournal, repository, store]);

  const restoreRecovery = useCallback(() => {
    if (recoveryCandidate === null) return;
    const recoveredProject = withBuiltinAssetLibrary(recoveryCandidate.project);
    store.reset(recoveredProject);
    setSelectedSceneId(recoveredProject.startSceneId);
    setSelectedObjectId(null);
    setSelectedObjectIds(new Set());
    setSelectedLayerId(null);
    setRecoverySavedAt(recoveryCandidate.savedAt);
    setRecoveryCandidate(null);
    setHasUnsavedChanges(true);
    setSaveStatus('dirty');
    setNotice('저장되지 않았던 편집 내용을 복구했습니다. 확인한 뒤 저장해 주세요.');
  }, [recoveryCandidate, store]);

  const discardRecovery = useCallback(() => {
    recoveryJournal?.clear(gameId);
    setRecoveryCandidate(null);
    setRecoverySavedAt(null);
    setNotice('이 기기의 이전 임시 복구본을 삭제했습니다. 현재 저장본은 그대로 유지됩니다.');
  }, [gameId, recoveryJournal]);

  const restoreServerDraftWithBackup = useCallback(async (): Promise<void> => {
    if (draftConflict === null || repository === null) return;
    downloadProject(draftConflict.localProject);
    try {
      setSaveStatus('loading');
      const latest = await repository.load(gameId);
      if (latest === null) throw new Error('서버의 최신 초안을 찾을 수 없습니다.');
      const upgradedDraft = withBuiltinAssetLibrary(latest);
      store.reset(upgradedDraft);
      setSelectedSceneId(upgradedDraft.startSceneId);
      setSelectedObjectId(null);
      setSelectedObjectIds(new Set());
      setDraftConflict(null);
      setHasUnsavedChanges(false);
      setSaveStatus('clean');
      setNotice('내 변경을 JSON으로 보관하고 서버 최신 초안을 불러왔습니다. 필요한 부분을 다시 적용하세요.');
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '서버 최신 초안을 불러오지 못했습니다.');
    }
  }, [draftConflict, gameId, repository, store]);

  const publish = useCallback(async (): Promise<void> => {
    if (publisher === null) {
      setSaveStatus('error');
      setNotice('서버 게시 기능이 아직 활성화되지 않았습니다. 로컬 플레이 테스트는 계속 사용할 수 있습니다.');
      return;
    }
    try {
      const blockers = findPublishBlockers(store.getState().project);
      if (blockers.length > 0) {
        setRightPanel('PROJECT');
        setSaveStatus('error');
        const remaining = blockers.length - 1;
        setNotice(`${blockers[0]?.message ?? '게시할 수 없는 자산이 있습니다.'}${remaining > 0 ? ` 외 ${remaining}건` : ''}`);
        return;
      }
      const saved = await save();
      if (saved === null) return;
      setSaveStatus('publishing');
      const receipt = await publisher.publish(gameId, saved.revision);
      setSaveStatus('published');
      setLastPublishedVersion(receipt.publishedVersion);
      const warningCopy = receipt.warnings.length > 0 ? ` 확인할 경고 ${receipt.warnings.length}건이 있습니다.` : '';
      setNotice(`공개 버전 ${receipt.publishedVersion} 게시를 완료했습니다.${warningCopy}`);
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '게임을 게시하지 못했습니다.');
    }
  }, [gameId, publisher, save, store]);

  const openPreview = useCallback(async (performanceMode = false): Promise<void> => {
    if (hasUnsavedChanges && (await save()) === null) return;
    if (previewRepository === null) {
      setSaveStatus('error');
      setNotice('이 브라우저에서는 로컬 플레이 snapshot을 만들 수 없습니다.');
      return;
    }
    try {
      await previewRepository.save(store.getState().project);
      const performanceQuery = performanceMode ? '&perf=1' : '';
      void navigate(`/app/games/${gameId}/play?source=local${performanceQuery}`);
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '플레이 테스트용 snapshot을 만들지 못했습니다.');
    }
  }, [gameId, hasUnsavedChanges, navigate, previewRepository, save, store]);

  const placeObject = useCallback((preset: GameObject['preset'], x: number, y: number) => {
    if (selectedScene.type === 'DIALOGUE') return;
    try {
      const result = addObject(project, selectedScene.id, preset, { x, y });
      apply(result.project);
      setSelectedObjectId(result.objectId);
      setSelectedObjectIds(new Set([result.objectId]));
      setRightPanel('PROPERTIES');
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '오브젝트를 배치하지 못했습니다.');
    }
  }, [apply, project, selectedScene]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target;
      const editingText = target instanceof HTMLInputElement
        || target instanceof HTMLTextAreaElement
        || target instanceof HTMLSelectElement
        || (target instanceof HTMLElement && target.isContentEditable);
      const insideDialog = target instanceof Element && target.closest('[role="dialog"]') !== null;
      if (event.key === 'Escape') {
        if (showGuide) {
          rememberGuideSeen(gameId);
          setShowGuide(false);
          return;
        }
        if (showTemplates) {
          setPendingTemplateId(null);
          setShowTemplates(false);
          return;
        }
        if (selectedObjectIds.size > 0) {
          clearObjectSelection();
          return;
        }
        if (focusMode) {
          setFocusMode(false);
          return;
        }
        setPlacementPreset(null);
        setTileBrush(null);
        setShowLayers(false);
        return;
      }
      if (insideDialog) return;
      if (event.shiftKey && event.key.toLowerCase() === 'f' && !editingText) {
        event.preventDefault();
        setFocusMode((current) => !current);
        return;
      }
      if (event.altKey && event.key.toLowerCase() === 'l' && !editingText) {
        event.preventDefault();
        setShowLayers((current) => !current);
        return;
      }
      if (!editingText && (event.key === 'Enter' || event.key === ' ') && placementPreset !== null) {
        // 팔레트 버튼은 캔버스에 tabIndex가 없어 활성화 후에도 포커스가 버튼에 남는다 —
        // keyboardCanvasContext(캔버스/body 포커스) 요건을 걸면 키보드로는 절대 확정할 수 없다.
        event.preventDefault();
        const scene = store.getState().project.scenes.find((candidate) => candidate.id === selectedSceneId);
        if (scene !== undefined && scene.type !== 'DIALOGUE') {
          placeObject(placementPreset, Math.floor(scene.width / 2), Math.floor(scene.height / 2));
        }
        setPlacementPreset(null);
        return;
      }
      const keyboardCanvasContext = target === document.body || (target instanceof HTMLElement && (
        target.classList.contains('gss-map-object') || target.classList.contains('gss-map-canvas')
      ));
      if (!editingText && keyboardCanvasContext && !(event.ctrlKey || event.metaKey) && !event.altKey) {
        const key = event.key.toLowerCase();
        if (key === 'q') {
          event.preventDefault();
          setCanvasTool('SELECT');
          setPlacementPreset(null);
          setTileBrush(null);
          return;
        }
        if (key === 'w') {
          event.preventDefault();
          setCanvasTool('PAN');
          setPlacementPreset(null);
          setTileBrush(null);
          return;
        }
        if (key === 'g') {
          event.preventDefault();
          setShowGrid((current) => !current);
          return;
        }
        if (paletteMode === 'TILES' && ['b', 'r', 'f', 'i'].includes(key)) {
          event.preventDefault();
          setTileTool(key === 'r' ? 'RECTANGLE' : key === 'f' ? 'FLOOD_FILL' : key === 'i' ? 'PICKER' : 'BRUSH');
          return;
        }
        if (event.key === 'Delete' || event.key === 'Backspace') {
          event.preventDefault();
          deleteSelection();
          return;
        }
      }
      if (!editingText && keyboardCanvasContext && !(event.ctrlKey || event.metaKey) && selectedObjectIds.size > 0 && event.key.startsWith('Arrow')) {
        const currentProject = store.getState().project;
        const scene = currentProject.scenes.find((candidate) => candidate.id === selectedSceneId);
        if (scene !== undefined && scene.type !== 'DIALOGUE') {
          event.preventDefault();
          const movableIds = [...selectedObjectIds].filter((id) => !editorLockedObjectIds.has(id));
          const deltaX = event.key === 'ArrowLeft' ? -1 : event.key === 'ArrowRight' ? 1 : 0;
          const deltaY = event.key === 'ArrowUp' ? -1 : event.key === 'ArrowDown' ? 1 : 0;
          if (movableIds.length > 0) apply(moveObjects(currentProject, scene.id, movableIds, deltaX, deltaY));
        }
        return;
      }
      if (!(event.ctrlKey || event.metaKey)) return;
      if (!editingText && keyboardCanvasContext && event.key.toLowerCase() === 'a') {
        const scene = store.getState().project.scenes.find((candidate) => candidate.id === selectedSceneId);
        if (scene !== undefined && scene.type !== 'DIALOGUE') {
          event.preventDefault();
          const objectIds = scene.objects.filter((object) => !editorHiddenObjectIds.has(object.id)).map((object) => object.id);
          selectObjects(objectIds, objectIds.at(-1) ?? null);
        }
        return;
      }
      if (!editingText && keyboardCanvasContext && event.key.toLowerCase() === 'd') {
        event.preventDefault();
        duplicateSelection();
        return;
      }
      if (!editingText && keyboardCanvasContext && event.key.toLowerCase() === 'c') {
        event.preventDefault();
        copySelection();
        return;
      }
      if (!editingText && keyboardCanvasContext && event.key.toLowerCase() === 'v') {
        event.preventDefault();
        pasteSelection();
        return;
      }
      if (event.key.toLowerCase() === 's') {
        event.preventDefault();
        void save();
      }
      if (event.key.toLowerCase() === 'z' && !event.shiftKey) {
        event.preventDefault();
        store.undo();
        setHasUnsavedChanges(true);
        setSaveStatus('dirty');
      }
      if (event.key.toLowerCase() === 'y' || (event.key.toLowerCase() === 'z' && event.shiftKey)) {
        event.preventDefault();
        store.redo();
        setHasUnsavedChanges(true);
        setSaveStatus('dirty');
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [apply, clearObjectSelection, copySelection, deleteSelection, duplicateSelection, editorHiddenObjectIds, editorLockedObjectIds, focusMode, gameId, paletteMode, pasteSelection, placeObject, placementPreset, save, selectObjects, selectedObjectIds, selectedSceneId, showGuide, showTemplates, store]);

  const importProject = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (file === undefined) return;
    try {
      const imported = withBuiltinAssetLibrary(parseGameProject(JSON.parse(await file.text())));
      if (imported.gameId !== gameId) throw new Error(`gameId가 ${gameId}인 프로젝트만 가져올 수 있습니다.`);
      store.reset(imported);
      setSelectedSceneId(imported.startSceneId);
      setSelectedObjectId(null);
      setSelectedObjectIds(new Set());
      setHasUnsavedChanges(true);
      setSaveStatus('dirty');
      setNotice(`${file.name}을 가져왔습니다. 저장 전 플레이 테스트를 권장합니다.`);
    } catch (error) {
      setSaveStatus('error');
      setNotice(error instanceof Error ? error.message : '올바른 GameProject JSON이 아닙니다.');
    }
  };

  if (selectedScene === undefined) return null;

  const tutorialReady = tutorialStep === 0
    ? selectedScene.type !== 'DIALOGUE'
    : tutorialStep === 1
      ? selectedObject !== null
      : tutorialStep === 2
        ? selectedObject !== null
          && rightPanel === 'PROPERTIES'
          && selectedObject.components.some((component) => component.type === 'SPRITE')
        : tutorialStep === 3
          ? selectedObject !== null && rightPanel === 'EVENTS'
          : saveStatus === 'saved' || saveStatus === 'clean' || saveStatus === 'published';

  const focusTutorialTarget = () => {
    const worldScene = project.scenes.find((scene) => scene.type !== 'DIALOGUE');
    if (worldScene === undefined) return;
    setSelectedSceneId(worldScene.id);
    setPlacementPreset(null);
    setTileBrush(null);
    if (tutorialStep === 0 || tutorialStep === null) {
      setSelectedObjectId(null);
      setSelectedObjectIds(new Set());
      return;
    }
    const target = tutorialStep === 3
      ? worldScene.objects.find((object) => worldScene.events.some((event) => event.trigger.type !== 'ON_SCENE_START' && event.trigger.targetId === object.id))
        ?? worldScene.objects.find((object) => object.preset !== 'PLAYER_SPAWN')
      : worldScene.objects.find((object) => object.preset !== 'PLAYER_SPAWN');
    if (target !== undefined) {
      setSelectedObjectId(target.id);
      setSelectedObjectIds(new Set([target.id]));
      setRightPanel(tutorialStep === 3 ? 'EVENTS' : 'PROPERTIES');
    }
  };

  const toggleObjectInSet = (
    setter: React.Dispatch<React.SetStateAction<ReadonlySet<string>>>,
    objectId: string,
  ) => setter((current) => {
    const next = new Set(current);
    if (next.has(objectId)) next.delete(objectId);
    else next.add(objectId);
    return next;
  });

  const addScene = (type: 'TOP_DOWN' | 'PLATFORMER' | 'DIALOGUE', presentation?: 'OVERLAY' | 'FULL_SCREEN') => {
    const next = type === 'TOP_DOWN'
      ? addTopDownScene(project)
      : type === 'PLATFORMER'
        ? addPlatformerScene(project)
        : addDialogueScene(project, presentation);
    apply(next);
    setSelectedSceneId(next.scenes.at(-1)?.id ?? next.startSceneId);
    setSelectedObjectId(null);
    setSelectedObjectIds(new Set());
  };

  return (
    <main className={`gss-root${focusMode ? ' is-focus-mode' : ''}`} data-game-studio-route="edit">
      <header className="gss-topbar">
        <div className="gss-brand-area">
          <Link aria-label="홈으로 돌아가기" className="gss-back" to="/app/home">‹</Link>
          <div className="gss-logo"><span>F</span><strong>Game Studio</strong></div>
          <div className="gss-title-input">
            <CommitInput label="프로젝트 이름" onCommit={(title) => apply(renameProject(project, title))} value={project.title} />
          </div>
        </div>
        <div className="gss-history-tools">
          <span className={`gss-save-state is-${saveStatus}`}><i />{saveLabel[saveStatus]}</span>
          {hasUnsavedChanges && recoverySavedAt !== null && (
            <span
              className="gss-recovery-state"
              title={`${new Date(recoverySavedAt).toLocaleString('ko-KR')}에 이 기기에 임시 복구본을 보관했습니다.`}
            >임시 복구 {new Date(recoverySavedAt).toLocaleTimeString('ko-KR', { hour: '2-digit', minute: '2-digit' })}</span>
          )}
          <button
            aria-label="실행 취소"
            aria-keyshortcuts="Control+Z Meta+Z"
            className="gss-icon-button"
            disabled={!snapshot.canUndo}
            onClick={() => { store.undo(); setHasUnsavedChanges(true); setSaveStatus('dirty'); }}
            title="실행 취소 (Ctrl+Z)"
            type="button"
          >↶</button>
          <button
            aria-label="다시 실행"
            aria-keyshortcuts="Control+Y Meta+Y Control+Shift+Z Meta+Shift+Z"
            className="gss-icon-button"
            disabled={!snapshot.canRedo}
            onClick={() => { store.redo(); setHasUnsavedChanges(true); setSaveStatus('dirty'); }}
            title="다시 실행 (Ctrl+Y)"
            type="button"
          >↷</button>
        </div>
        <div className="gss-primary-actions">
          <button className="gss-guide-button" onClick={() => setShowGuide(true)} type="button">? 사용 안내</button>
          <button className="gss-guide-button" onClick={() => { setPendingTemplateId(null); setShowTemplates(true); }} type="button">▦ 시작 템플릿</button>
          <button
            className="gss-preview-button"
            disabled={saveStatus === 'loading' || saveStatus === 'saving'}
            onClick={() => void openPreview()}
            type="button"
          ><span>▶</span> 플레이 테스트</button>
          <button
            className="gss-guide-button"
            disabled={saveStatus === 'loading' || saveStatus === 'saving'}
            onClick={() => void openPreview(true)}
            title="활성 탭 FPS, p95 프레임 시간, 끊김 비율을 보며 플레이합니다."
            type="button"
          >성능 점검</button>
          <button aria-keyshortcuts="Control+S Meta+S" disabled={saveStatus === 'saving' || saveStatus === 'publishing'} onClick={() => void save()} type="button">저장</button>
          <button
            className="gss-publish-button"
            disabled={publisher === null || saveStatus === 'loading' || saveStatus === 'saving' || saveStatus === 'publishing'}
            onClick={() => void publish()}
            title={publisher === null ? '서버 Draft/Publish 연결 시 자동 활성화됩니다.' : '현재 초안을 검증하고 새 공개 버전을 만듭니다.'}
            type="button"
          >게시하기</button>
          {lastPublishedVersion !== null && (
            <button
              className="gss-guide-button"
              onClick={() => void navigate(`/app/games/${gameId}/play`)}
              title={`공개 버전 ${lastPublishedVersion}을 플레이합니다.`}
              type="button"
            >게시본 확인 v{lastPublishedVersion}</button>
          )}
        </div>
      </header>

      <section className="gss-layout">
        <aside className="gss-left-sidebar">
          <div className="gss-sidebar-section gss-scene-section">
            <div className="gss-sidebar-heading"><span>장면</span><span>{project.scenes.length}/50</span></div>
            <div className="gss-scene-add-row">
              <button onClick={() => addScene('TOP_DOWN')} title="캐릭터가 이동하고 오브젝트와 상호작용하는 장면" type="button">+ 탐색 맵</button>
              <button onClick={() => addScene('PLATFORMER')} title="중력과 점프가 있는 횡스크롤 액션 장면" type="button">+ 플랫폼</button>
              <button onClick={() => addScene('DIALOGUE', 'OVERLAY')} title="게임 화면 위에 표시되는 대화와 선택지" type="button">+ 대화</button>
              <button onClick={() => addScene('DIALOGUE', 'FULL_SCREEN')} title="배경과 인물을 크게 보여주는 이야기 장면" type="button">+ 연출</button>
            </div>
            <nav className="gss-scene-list">
              {project.scenes.map((scene, index) => (
                <button
                  className={scene.id === selectedScene.id ? 'is-active' : ''}
                  key={scene.id}
                  onClick={() => {
                    setSelectedSceneId(scene.id);
                    setSelectedObjectId(null);
                    setSelectedObjectIds(new Set());
                    setPlacementPreset(null);
                    setTileBrush(null);
                    setSelectedLayerId(scene.type !== 'DIALOGUE' ? scene.tileLayers[0]?.id ?? null : null);
                  }}
                  type="button"
                >
                  <span>{scene.type === 'TOP_DOWN' ? '▦' : scene.type === 'PLATFORMER' ? '▰' : '☰'}</span>
                  <div><strong>{scene.name}</strong><small>{index + 1} · {scene.type}</small></div>
                  {scene.id === project.startSceneId && <em>START</em>}
                </button>
              ))}
            </nav>
            <div className="gss-scene-actions">
              <button
                disabled={project.scenes.length >= 50}
                onClick={() => {
                  try {
                    const result = duplicateScene(project, selectedScene.id);
                    apply(result.project);
                    setSelectedSceneId(result.sceneId);
                    clearObjectSelection();
                    setNotice(`${selectedScene.name}의 배치와 동작을 새 Scene으로 복제했습니다.`);
                  } catch (error) {
                    setSaveStatus('error');
                    setNotice(error instanceof Error ? error.message : 'Scene을 복제하지 못했습니다.');
                  }
                }}
                title="배치·타일·이벤트·대화를 모두 복제"
                type="button"
              >Scene 복제</button>
              <button
                aria-label="Scene 위로 이동"
                disabled={project.scenes.findIndex((scene) => scene.id === selectedScene.id) === 0}
                onClick={() => apply(moveScene(project, selectedScene.id, -1))}
                title="Scene 순서를 위로 이동"
                type="button"
              >↑</button>
              <button
                aria-label="Scene 아래로 이동"
                disabled={project.scenes.findIndex((scene) => scene.id === selectedScene.id) === project.scenes.length - 1}
                onClick={() => apply(moveScene(project, selectedScene.id, 1))}
                title="Scene 순서를 아래로 이동"
                type="button"
              >↓</button>
              <button
                className="gss-text-danger"
                disabled={sceneRemovalReason(project, selectedScene.id) !== null}
                onClick={() => {
                  const next = removeScene(project, selectedScene.id);
                  apply(next);
                  setSelectedSceneId(next.startSceneId);
                  clearObjectSelection();
                }}
                title={sceneRemovalReason(project, selectedScene.id) ?? '선택 Scene 삭제'}
                type="button"
              >삭제</button>
            </div>
          </div>

          {selectedScene.type !== 'DIALOGUE' && (
            <div className="gss-sidebar-section gss-object-section">
              <div className="gss-palette-tabs">
                <button className={paletteMode === 'OBJECTS' ? 'is-active' : ''} onClick={() => { setPaletteMode('OBJECTS'); setTileBrush(null); }} type="button">오브젝트</button>
                <button className={paletteMode === 'TILES' ? 'is-active' : ''} onClick={() => { setPaletteMode('TILES'); setPlacementPreset(null); }} type="button">타일맵</button>
              </div>
              {paletteMode === 'OBJECTS' ? (
                <>
                  <div className="gss-sidebar-heading"><span>배치할 요소</span><span>{selectedScene.objects.length}/500</span></div>
                  <p className="gss-sidebar-copy">끌어다 놓거나 클릭 후 맵의 위치를 선택하세요.</p>
                  <input
                    aria-label="배치 요소 검색"
                    className="gss-palette-search"
                    onChange={(event) => setObjectSearch(event.target.value)}
                    placeholder="NPC, 함정, 포털 검색"
                    value={objectSearch}
                  />
                  <div className="gss-category-tabs">
                    {(['전체', '기본', '캐릭터', '상호작용', '액션', '장식'] as const).map((category) => (
                      <button className={objectCategory === category ? 'is-active' : ''} key={category} onClick={() => setObjectCategory(category)} type="button">{category}</button>
                    ))}
                  </div>
                  <div className="gss-object-palette">
                    {PRESET_DEFINITIONS.filter((definition) => (
                      (objectCategory === '전체' || definition.category === objectCategory)
                      && `${definition.label} ${definition.description}`.toLowerCase().includes(objectSearch.trim().toLowerCase())
                    )).map((definition) => {
                  const spawnExists = definition.type === 'PLAYER_SPAWN'
                    && selectedScene.objects.some((object) => object.preset === 'PLAYER_SPAWN');
                  const previewVisual = resolveStaticImageVisual(
                    project.assets.find((asset) => asset.source === definition.previewSource),
                    assetUrls,
                  );
                  return (
                    <button
                      className={placementPreset === definition.type ? 'is-active' : ''}
                      disabled={spawnExists || selectedScene.objects.length >= 500}
                      draggable={!spawnExists}
                      key={definition.type}
                      onClick={() => { setTileBrush(null); setPlacementPreset((current) => current === definition.type ? null : definition.type); }}
                      onDragStart={(event) => {
                        event.dataTransfer.setData('application/festa-game-object', definition.type);
                        event.dataTransfer.effectAllowed = 'copy';
                      }}
                      title={definition.description}
                      type="button"
                    >
                      {previewVisual === null
                        ? <span aria-hidden="true">{definition.icon}</span>
                        : <span aria-hidden="true" className="gss-palette-image" style={staticImageBackgroundStyle(previewVisual)} />}
                      <strong>{definition.label}</strong>
                    </button>
                  );
                    })}
                  </div>
                </>
              ) : (
                <div className="gss-tile-tools">
                  <div className="gss-sidebar-heading"><span>타일 레이어</span><span>{selectedScene.tileLayers.length}/10</span></div>
                  <div className="gss-inline-actions">
                    <select
                      disabled={selectedScene.tileLayers.length === 0}
                      onChange={(event) => setSelectedLayerId(event.target.value)}
                      value={selectedTileLayer?.id ?? ''}
                    >
                      {selectedScene.tileLayers.map((layer) => <option key={layer.id} value={layer.id}>{layer.name}</option>)}
                    </select>
                    <button
                      disabled={selectedScene.tileLayers.length >= 10}
                      onClick={() => {
                        try {
                          const result = addTileLayer(project, selectedScene.id);
                          apply(result.project);
                          setSelectedLayerId(result.layerId);
                          setTileBrush(0);
                        } catch (error) {
                          setNotice(error instanceof Error ? error.message : '레이어를 추가하지 못했습니다.');
                          setSaveStatus('error');
                        }
                      }}
                      type="button"
                    >+ 레이어</button>
                  </div>
                  {selectedTileLayer === null ? (
                    <div className="gss-help-card"><strong>Tile Layer를 추가하세요</strong><p>레이어마다 바닥, 벽, 장식을 나누어 그릴 수 있습니다.</p></div>
                  ) : (
                    <>
                      <p className="gss-sidebar-copy">그리기 방식을 고른 뒤 캔버스에서 바로 작업하세요.</p>
                      <div aria-label="타일 그리기 도구" className="gss-tile-mode-row" role="group">
                        {([
                          ['BRUSH', '브러시', 'B'],
                          ['RECTANGLE', '사각형', 'R'],
                          ['FLOOD_FILL', '영역 채우기', 'F'],
                          ['PICKER', '스포이드', 'I'],
                        ] as const).map(([tool, label, shortcut]) => (
                          <button
                            aria-keyshortcuts={shortcut}
                            aria-pressed={tileTool === tool}
                            className={tileTool === tool ? 'is-active' : ''}
                            key={tool}
                            onClick={() => setTileTool(tool)}
                            type="button"
                          ><strong>{label}</strong><kbd>{shortcut}</kbd></button>
                        ))}
                      </div>
                      <div className="gss-tile-palette">
                        {Array.from({ length: 16 }, (_, index) => (
                          <button
                            aria-label={`타일 ${index}`}
                            className={`is-tile-${index % 8}${tileBrush === index ? ' is-active' : ''}`}
                            key={index}
                            onClick={() => setTileBrush(index)}
                            style={selectedTilesetVisual === null ? undefined : tileBackgroundStyle(selectedTilesetVisual, index)}
                            type="button"
                          ><span>{index}</span></button>
                        ))}
                        <button className={tileBrush === -1 ? 'is-active is-eraser' : 'is-eraser'} onClick={() => setTileBrush(-1)} type="button">지우개</button>
                      </div>
                      <div className="gss-inline-actions">
                        <button disabled={tileBrush === null} onClick={() => apply(fillTileLayer(project, selectedScene.id, selectedTileLayer.id, tileBrush ?? -1))} type="button">전체 채우기</button>
                        <button onClick={() => apply(fillTileLayer(project, selectedScene.id, selectedTileLayer.id, -1))} type="button">전체 지우기</button>
                      </div>
                    </>
                  )}
                </div>
              )}
            </div>
          )}
          <div className="gss-import-export">
            <button onClick={() => fileInputRef.current?.click()} type="button">JSON 가져오기</button>
            <button onClick={() => downloadProject(project)} type="button">JSON 내보내기</button>
            <input accept="application/json,.json" hidden onChange={(event) => void importProject(event)} ref={fileInputRef} type="file" />
          </div>
        </aside>

        <section className="gss-workspace">
          <div className="gss-canvas-toolbar">
            <div><span className="gss-type-badge">{selectedScene.type}</span><strong>{selectedScene.name}</strong><small>{selectedScene.id}</small></div>
            {selectedScene.type !== 'DIALOGUE' && (
              <div className="gss-canvas-tools">
                <div className="gss-tool-segment" role="group" aria-label="캔버스 도구">
                  <button
                    aria-keyshortcuts="Q"
                    aria-pressed={canvasTool === 'SELECT'}
                    className={canvasTool === 'SELECT' ? 'is-active' : ''}
                    onClick={() => { setCanvasTool('SELECT'); setPlacementPreset(null); setTileBrush(null); }}
                    title="클릭하거나 빈 공간을 드래그해 여러 오브젝트 선택 (Q)"
                    type="button"
                  >선택 <kbd>Q</kbd></button>
                  <button
                    aria-keyshortcuts="W"
                    aria-pressed={canvasTool === 'PAN'}
                    className={canvasTool === 'PAN' ? 'is-active' : ''}
                    onClick={() => { setCanvasTool('PAN'); setPlacementPreset(null); setTileBrush(null); }}
                    title="캔버스를 드래그해 화면 이동 (W 또는 마우스 가운데 버튼)"
                    type="button"
                  >화면 이동 <kbd>W</kbd></button>
                </div>
                <button
                  aria-keyshortcuts="G"
                  aria-pressed={showGrid}
                  className={showGrid ? 'is-active' : ''}
                  onClick={() => setShowGrid((current) => !current)}
                  title="배치 격자 표시 전환 (G)"
                  type="button"
                >격자</button>
                <button
                  aria-pressed={showCollisions}
                  className={showCollisions ? 'is-active' : ''}
                  onClick={() => setShowCollisions((current) => !current)}
                  title="충돌 Component가 있는 오브젝트의 범위 표시"
                  type="button"
                >충돌 영역</button>
                <button
                  aria-keyshortcuts="Control+D Meta+D"
                  disabled={selectedObjectIds.size === 0}
                  onClick={duplicateSelection}
                  title="선택한 오브젝트와 연결된 동작 복제 (Ctrl+D)"
                  type="button"
                >복제</button>
                <button
                  aria-keyshortcuts="Control+C Meta+C"
                  disabled={selectedObjectIds.size === 0}
                  onClick={copySelection}
                  title="선택한 오브젝트와 연결 동작 복사 (Ctrl+C)"
                  type="button"
                >복사</button>
                <button
                  aria-keyshortcuts="Control+V Meta+V"
                  disabled={objectClipboard === null}
                  onClick={pasteSelection}
                  title="현재 Scene에 오브젝트 붙여넣기 (Ctrl+V)"
                  type="button"
                >붙여넣기</button>
                <button
                  aria-keyshortcuts="Delete Backspace"
                  className="is-danger"
                  disabled={selectedObjectIds.size === 0}
                  onClick={deleteSelection}
                  title="선택한 오브젝트 삭제 (Delete)"
                  type="button"
                >삭제</button>
                <button
                  aria-keyshortcuts="Shift+F"
                  aria-pressed={focusMode}
                  className={focusMode ? 'is-active' : ''}
                  onClick={() => setFocusMode((current) => !current)}
                  title="양쪽 패널을 숨기거나 다시 엽니다 (Shift+F)"
                  type="button"
                >{focusMode ? '패널 열기' : '화면 넓게'}</button>
                <button
                  aria-expanded={showLayers}
                  aria-keyshortcuts="Alt+L"
                  className={showLayers ? 'is-active' : ''}
                  onClick={() => setShowLayers((current) => !current)}
                  title="배치된 오브젝트 찾기·잠금·숨김 (Alt+L)"
                  type="button"
                >레이어 {selectedScene.objects.length}</button>
                <div className="gss-view-controls" role="group" aria-label="캔버스 보기">
                  <button onClick={() => setFitRequestToken((current) => current + 1)} title="전체 맵이 화면에 들어오도록 맞춤" type="button">전체</button>
                  <button disabled={selectedObjectId === null} onClick={() => setFocusRequestToken((current) => current + 1)} title="선택한 오브젝트를 화면 중앙으로 이동" type="button">선택 위치</button>
                  <button onClick={() => setZoom(100)} title="셀 한 칸을 32px로 표시" type="button">1:1</button>
                </div>
                <div className="gss-zoom-controls">
                  <button aria-label="축소" onClick={() => setZoom((current) => Math.max(10, current - 10))} type="button">−</button>
                  <span>{zoom}%</span>
                  <button aria-label="확대" onClick={() => setZoom((current) => Math.min(200, current + 10))} type="button">+</button>
                </div>
              </div>
            )}
          </div>
          {selectedScene.type !== 'DIALOGUE' ? (
            <TopDownCanvas
              assets={project.assets}
              assetUrls={assetUrls}
              canvasTool={canvasTool}
              fitRequestToken={fitRequestToken}
              focusRequestToken={focusRequestToken}
              editorHiddenObjectIds={editorHiddenObjectIds}
              editorLockedObjectIds={editorLockedObjectIds}
              onMoveObjects={(objectIds, deltaX, deltaY) => {
                const movableIds = objectIds.filter((id) => !editorLockedObjectIds.has(id));
                if (movableIds.length > 0) apply(moveObjects(store.getState().project, selectedScene.id, movableIds, deltaX, deltaY));
              }}
              onPaintTiles={(cells, tileIndex) => {
                if (selectedTileLayer !== null) apply(paintTiles(store.getState().project, selectedScene.id, selectedTileLayer.id, cells, tileIndex));
              }}
              onFloodFillTiles={(x, y, tileIndex) => {
                if (selectedTileLayer !== null) apply(floodFillTiles(store.getState().project, selectedScene.id, selectedTileLayer.id, x, y, tileIndex));
              }}
              onPickTile={(tileIndex) => {
                setTileBrush(tileIndex);
                setTileTool('BRUSH');
                setNotice(tileIndex < 0 ? '빈 타일을 골랐습니다. 지우개 브러시로 전환했습니다.' : `타일 ${tileIndex}을 골라 브러시로 전환했습니다.`);
              }}
              onPlaceObject={placeObject}
              onPlacementComplete={() => setPlacementPreset(null)}
              onZoomChange={setZoom}
              onSelectObjects={selectObjects}
              placementPreset={placementPreset}
              scene={selectedScene}
              selectedObjectId={selectedObjectId}
              selectedObjectIds={selectedObjectIds}
              showGrid={showGrid}
              showCollisions={showCollisions}
              tileBrush={paletteMode === 'TILES' ? tileBrush : null}
              tileLayer={selectedTileLayer}
              tileTool={tileTool}
              tilesetVisual={selectedTilesetVisual}
              zoom={zoom}
            />
          ) : (
            <DialogueEditor assetUrls={assetUrls} onApply={apply} project={project} scene={selectedScene} />
          )}
          {showLayers && selectedScene.type !== 'DIALOGUE' && (
            <ObjectLayerPanel
              hiddenObjectIds={editorHiddenObjectIds}
              lockedObjectIds={editorLockedObjectIds}
              onChangeZIndex={(object, zIndex) => {
                const sprite = object.components.find((component) => component.type === 'SPRITE');
                if (sprite?.type === 'SPRITE') apply(replaceComponent(project, selectedScene.id, object.id, { ...sprite, zIndex }));
              }}
              onClose={() => setShowLayers(false)}
              onSelect={(objectId) => {
                selectObjects([objectId], objectId);
              }}
              onToggleHidden={(objectId) => toggleObjectInSet(setEditorHiddenObjectIds, objectId)}
              onToggleLocked={(objectId) => toggleObjectInSet(setEditorLockedObjectIds, objectId)}
              scene={selectedScene}
              selectedObjectId={selectedObjectId}
            />
          )}
          <footer className="gss-statusbar">
            <span><i className="is-valid" />GameProject {project.schemaVersion} 검증 적용</span>
            {selectedObjectIds.size > 0 && <strong>{selectedObjectIds.size}개 선택 · Shift/Ctrl로 추가 선택 · 화살표로 이동</strong>}
            {placementPreset !== null && <strong>배치 모드 · {placementPreset} — 맵의 위치를 클릭하세요</strong>}
            {tileBrush !== null && paletteMode === 'TILES' && <strong>타일 {tileTool === 'BRUSH' ? '브러시' : tileTool === 'RECTANGLE' ? '사각형' : tileTool === 'FLOOD_FILL' ? '영역 채우기' : '스포이드'} · {tileBrush === -1 ? '지우개' : tileBrush}</strong>}
            <span>Game #{gameId} · revision {project.revision}</span>
          </footer>
        </section>

        <aside className="gss-right-sidebar">
          <div className="gss-panel-tabs">
            {(['PROPERTIES', 'EVENTS', 'PROJECT'] as const).map((panel) => (
              <button
                className={rightPanel === panel ? 'is-active' : ''}
                key={panel}
                onClick={() => setRightPanel(panel)}
                type="button"
              >{panel === 'PROPERTIES' ? '속성' : panel === 'EVENTS' ? '이벤트' : '데이터'}</button>
            ))}
          </div>
          <div className="gss-panel-scroll">
            {rightPanel === 'PROPERTIES' && selectedScene.type !== 'DIALOGUE' && (
              <InspectorPanel
                assetUrls={assetUrls}
                onApply={apply}
                onObjectRemoved={clearObjectSelection}
                onReplaceSprite={(file) => {
                  if (selectedObject === null) return;
                  void uploadAsset('IMAGE', file).then((asset) => {
                    if (asset === null) return;
                    apply(replaceComponent(
                      store.getState().project,
                      selectedScene.id,
                      selectedObject.id,
                      { type: 'SPRITE', assetId: asset.id, scale: 100, zIndex: 2 },
                    ));
                  });
                }}
                project={project}
                scene={selectedScene}
                selectedObject={selectedObject}
              />
            )}
            {rightPanel === 'PROPERTIES' && selectedScene.type === 'DIALOGUE' && (
              <div className="gss-panel-stack">
                <div className="gss-panel-heading"><div><span className="gss-eyebrow">DIALOGUE</span><h2>{selectedScene.name}</h2></div></div>
                <div className="gss-info-grid"><span>표시 방식</span><strong>{selectedScene.presentation}</strong><span>노드</span><strong>{selectedScene.nodes.length}</strong><span>시작 노드</span><strong>{selectedScene.startNodeId}</strong></div>
              </div>
            )}
            {rightPanel === 'EVENTS' && selectedScene.type !== 'DIALOGUE' && (
              <EventEditor onApply={apply} project={project} scene={selectedScene} selectedObject={selectedObject} />
            )}
            {rightPanel === 'EVENTS' && selectedScene.type === 'DIALOGUE' && (
              <div className="gss-help-card"><strong>선택지 결과가 Dialogue Event입니다</strong><p>중앙 편집기에서 다음 노드, Scene 이동, 대화 닫기 또는 게임 완료를 선택하세요.</p></div>
            )}
            {rightPanel === 'PROJECT' && <ProjectDataPanel
              onApply={apply}
              onUploadAsset={(kind: AssetReference['kind'], file: File) => { void uploadAsset(kind, file); }}
              project={project}
            />}
          </div>
        </aside>
      </section>
      {showGuide && (
        <div className="gss-guide-backdrop" role="presentation" onMouseDown={() => { rememberGuideSeen(gameId); setShowGuide(false); }}>
          <section aria-modal="true" className="gss-guide-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog">
            <header><div><span>처음 시작하기 · 약 10분</span><h2>완성 예제를 내 게임으로 바꿔 봅시다</h2></div><button aria-label="안내 닫기" onClick={() => { rememberGuideSeen(gameId); setShowGuide(false); }} type="button">×</button></header>
            <ol>
              <li><span>1</span><div><strong>만들고 싶은 플레이 방식부터 고릅니다</strong><p>이야기, 방탈출, 수집, 점프맵, 슈팅, 생존전 중 가장 가까운 완성 예제에서 시작합니다.</p></div></li>
              <li><span>2</span><div><strong>배치와 이미지는 원하는 만큼 바꿉니다</strong><p>맵에서 끌어 이동하고, 재료함에서 모습을 고르며, 바꾸고 싶은 오브젝트만 내 이미지로 교체합니다.</p></div></li>
              <li><span>3</span><div><strong>조건과 결과를 한국어로 연결합니다</strong><p>“상호작용할 때 → 열쇠가 있으면 → 다음 장면 이동”처럼 읽히는 이벤트를 조합합니다.</p></div></li>
              <li><span>4</span><div><strong>즉시 플레이하고 고칩니다</strong><p>플레이 테스트는 현재 편집본의 별도 snapshot으로 실행되어 서버 게시 전에도 완주를 검증할 수 있습니다.</p></div></li>
            </ol>
            <div className="gss-guide-tip"><strong>PC 편집 팁</strong><p>방향키로 한 칸 이동, Ctrl+S로 저장, Ctrl+Z로 실행 취소, Alt+L로 레이어, Shift+F로 화면을 넓게 볼 수 있습니다.</p></div>
            <div className="gss-guide-actions"><button onClick={() => { rememberGuideSeen(gameId); setShowGuide(false); setPendingTemplateId(null); setShowTemplates(true); }} type="button">완성 예제 선택하기</button><button autoFocus className="gss-guide-start" onClick={() => { rememberGuideSeen(gameId); setShowGuide(false); setTutorialStep(0); }} type="button">단계별 튜토리얼 시작</button></div>
          </section>
        </div>
      )}
      {showTemplates && (
        <div className="gss-guide-backdrop" role="presentation" onMouseDown={() => { setPendingTemplateId(null); setShowTemplates(false); }}>
          <section aria-modal="true" className="gss-template-modal" onMouseDown={(event) => event.stopPropagation()} role="dialog">
            <header><div><span>바로 수정할 수 있는 완성 예제</span><h2>어떤 게임에서 시작할까요?</h2><p>모든 템플릿은 배치·이미지·규칙을 자유롭게 바꿀 수 있습니다.</p></div><button aria-label="템플릿 닫기" onClick={() => { setPendingTemplateId(null); setShowTemplates(false); }} type="button">×</button></header>
            <div className="gss-template-grid">
              {PROJECT_TEMPLATES.map((template) => (
                <button
                  className={pendingTemplateId === template.id ? 'is-pending' : ''}
                  key={template.id}
                  onClick={() => setPendingTemplateId(template.id)}
                  type="button"
                >
                  <img alt={`${template.title} 게임 화면 미리보기`} src={template.previewUrl} />
                  <div>
                    <small>{template.genre} · {template.runtimeMode === 'TOP_DOWN' ? '탐색 맵' : '플랫폼 맵'}</small>
                    <strong>{template.title}</strong>
                    <p>{template.description}</p>
                    <em>{template.systems.join(' · ')}</em>
                    <span className="gss-template-meta"><i>{template.difficulty}</i><i>약 {template.estimatedMinutes}분</i>{template.recommended && <i className="is-recommended">처음 추천</i>}</span>
                  </div>
                </button>
              ))}
            </div>
            {pendingTemplateId !== null && (() => {
              const selectedTemplate = PROJECT_TEMPLATES.find((template) => template.id === pendingTemplateId);
              if (selectedTemplate === undefined) return null;
              return (
                <footer className="gss-template-confirm">
                  <div><strong>{selectedTemplate.title}</strong><p>현재 편집 내용을 이 완성 예제로 바꿉니다. 저장하지 않은 변경은 사라집니다.</p></div>
                  <button onClick={() => setPendingTemplateId(null)} type="button">취소</button>
                  <button
                    className="gss-guide-start"
                    onClick={() => {
                      const next = createProjectFromTemplate(gameId, selectedTemplate.id);
                      store.reset(next);
                      setSelectedSceneId(next.startSceneId);
                      setSelectedObjectId(null);
                      setSelectedObjectIds(new Set());
                      setSelectedLayerId(null);
                      setHasUnsavedChanges(true);
                      setSaveStatus('dirty');
                      setPendingTemplateId(null);
                      setShowTemplates(false);
                      setNotice(`${selectedTemplate.title} 템플릿을 불러왔습니다. 플레이 테스트로 확인해 보세요.`);
                    }}
                    type="button"
                  >이 템플릿 불러오기</button>
                </footer>
              );
            })()}
          </section>
        </div>
      )}
      {tutorialStep !== null && (
        <aside className="gss-tutorial-dock">
          <header><span>첫 게임 만들기 · {tutorialStep + 1}/{TUTORIAL_STEPS.length}</span><button aria-label="튜토리얼 종료" onClick={() => setTutorialStep(null)} type="button">×</button></header>
          <div className="gss-tutorial-progress"><i style={{ width: `${((tutorialStep + 1) / TUTORIAL_STEPS.length) * 100}%` }} /></div>
          <strong>{TUTORIAL_STEPS[tutorialStep]?.title}</strong>
          <p>{TUTORIAL_STEPS[tutorialStep]?.copy}</p>
          {!tutorialReady && tutorialStep < TUTORIAL_STEPS.length - 1 && <button className="gss-tutorial-find" onClick={focusTutorialTarget} type="button">화면에서 해당 요소 찾기</button>}
          <button
            disabled={!tutorialReady}
            onClick={() => setTutorialStep((current) => current === null || current >= TUTORIAL_STEPS.length - 1 ? null : current + 1)}
            type="button"
          >{tutorialStep === TUTORIAL_STEPS.length - 1 ? '튜토리얼 완료' : tutorialReady ? '다음 단계' : '화면에서 먼저 해보세요'}</button>
        </aside>
      )}
      {draftConflict !== null && (
        <aside aria-live="assertive" className="gss-conflict-dock" role="alert">
          <header><span>저장 충돌</span><button aria-label="충돌 안내 닫기" onClick={() => setDraftConflict(null)} type="button">×</button></header>
          <strong>내 변경은 이 화면에 그대로 남아 있습니다</strong>
          <p>다른 곳에서 먼저 저장해 서버 revision이 {draftConflict.currentRevision}이 되었습니다. 덮어쓰지 않고 복구 방법을 선택하세요.</p>
          <div>
            <button onClick={() => downloadProject(draftConflict.localProject)} type="button">내 변경 JSON 보관</button>
            <button className="is-primary" onClick={() => void restoreServerDraftWithBackup()} type="button">백업 후 서버본 불러오기</button>
          </div>
        </aside>
      )}
      {recoveryCandidate !== null && draftConflict === null && (
        <aside aria-live="polite" className="gss-conflict-dock gss-recovery-dock" role="status">
          <header><span>로컬 안전 복구</span><button aria-label="복구 안내 닫기" onClick={() => setRecoveryCandidate(null)} type="button">×</button></header>
          <strong>저장되지 않은 편집 내용을 발견했습니다</strong>
          <p>{new Date(recoveryCandidate.savedAt).toLocaleString('ko-KR')}에 이 기기에 임시 보관한 내용입니다. 현재 저장본과 비교해 복구할 수 있습니다.</p>
          <div>
            <button onClick={() => downloadProject(recoveryCandidate.project)} type="button">JSON 보관</button>
            <button onClick={discardRecovery} type="button">임시본 버리기</button>
            <button className="is-primary" onClick={restoreRecovery} type="button">복구해서 계속 편집</button>
          </div>
        </aside>
      )}
      {notice !== null && (
        <button className={`gss-toast is-${saveStatus}`} onClick={() => setNotice(null)} type="button">
          <span>{saveStatus === 'error' ? '!' : '✓'}</span>{notice}<em>×</em>
        </button>
      )}
    </main>
  );
};
