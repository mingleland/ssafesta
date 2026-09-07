import { useCallback, useEffect, useMemo, useRef, useState, type DragEvent, type PointerEvent } from 'react';
import type { AssetReference, GameObject, Position2d, TileLayer, WorldScene } from '../../contracts/gameProject.ts';
import { findPresetDefinition } from '../model/authoringRegistry.ts';
import { tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import type { TilesetDefinition } from '../assets/builtinAssetCatalog.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';
import {
  calculateFitZoom,
  calculateGridViewport,
  equalGridViewports,
  expandGridViewport,
  gridViewportContains,
  objectsInViewport,
  tileIndexesInViewport,
  type GridViewport,
} from './canvasViewport.ts';
import { CanvasMinimap } from './CanvasMinimap.tsx';
import { TileOverviewCanvas } from './TileOverviewCanvas.tsx';

export type CanvasTool = 'SELECT' | 'PAN';
export type TileTool = 'BRUSH' | 'RECTANGLE' | 'FLOOD_FILL' | 'PICKER';

interface TopDownCanvasProps {
  readonly scene: WorldScene;
  readonly assets: readonly AssetReference[];
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly selectedObjectId: string | null;
  readonly selectedObjectIds: ReadonlySet<string>;
  readonly placementPreset: GameObject['preset'] | null;
  readonly tileLayer: TileLayer | null;
  readonly tileBrush: number | null;
  readonly tileTool: TileTool;
  readonly tilesetVisual: TilesetDefinition | null;
  readonly zoom: number;
  readonly fitRequestToken: number;
  readonly focusRequestToken: number;
  readonly canvasTool: CanvasTool;
  readonly showGrid: boolean;
  readonly showCollisions: boolean;
  readonly editorHiddenObjectIds: ReadonlySet<string>;
  readonly editorLockedObjectIds: ReadonlySet<string>;
  readonly onSelectObjects: (objectIds: readonly string[], primaryObjectId: string | null) => void;
  readonly onPlaceObject: (preset: GameObject['preset'], x: number, y: number) => void;
  readonly onMoveObjects: (objectIds: readonly string[], deltaX: number, deltaY: number) => void;
  readonly onPaintTiles: (cells: readonly { readonly x: number; readonly y: number }[], tileIndex: number) => void;
  readonly onFloodFillTiles: (x: number, y: number, tileIndex: number) => void;
  readonly onPickTile: (tileIndex: number) => void;
  readonly onPlacementComplete: () => void;
  readonly onZoomChange: (zoom: number) => void;
  // S15P21A604-391 — Scene 전환 시 캔버스 팬(스크롤) 위치를 씬별로 기억/복원하기 위한
  // 훅. onViewportSettle은 뷰포트가 바뀔 때마다(스크롤 포함) 그 중심 좌표를 알려주고,
  // restoreViewportCenter는 부모가 "이 좌표로 스크롤해 달라"고 요청할 때(Scene 재진입
  // 시) 쓴다 — fitRequestToken/focusRequestToken과 같은 scrollToGridPosition 경로를 탄다.
  readonly onViewportSettle?: (center: Position2d) => void;
  readonly restoreViewportCenter?: Position2d | null;
}

const pointerToGrid = (
  element: HTMLElement,
  clientX: number,
  clientY: number,
  scene: WorldScene,
): { x: number; y: number } => {
  const rect = element.getBoundingClientRect();
  return {
    x: Math.max(0, Math.min(scene.width - 1, Math.floor(((clientX - rect.left) / rect.width) * scene.width))),
    y: Math.max(0, Math.min(scene.height - 1, Math.floor(((clientY - rect.top) / rect.height) * scene.height))),
  };
};

interface SelectionBox {
  readonly startX: number;
  readonly startY: number;
  readonly endX: number;
  readonly endY: number;
}

interface DraggingSelection {
  readonly objectIds: readonly string[];
  readonly lastX: number;
  readonly lastY: number;
}

interface PanningState {
  readonly clientX: number;
  readonly clientY: number;
  readonly scrollLeft: number;
  readonly scrollTop: number;
}

const BASE_CELL_SIZE_PX = 32;
// S15P21A604-394 — 원래 480(보기 좋은 최소 크기)이었는데, 30~300% 범위와 맞지 않아
// scene 폭이 넉넉하지 않으면 낮은 zoom%에서 캔버스 크기가 전혀 안 바뀌는 데드존이 생겼다
// (예: 16칸 scene은 93.75% 밑으로 전부 무반응). "타일이 아예 안 보일 정도로 쪼그라드는
// 것만 막는" 최소한의 방어값으로 의미를 바꿔 셀 하나 크기(BASE_CELL_SIZE_PX)로 낮춘다 —
// 가장 좁은 scene(4칸)·최저 zoom(30%)에서도 이 floor가 걸리지 않는다.
const MIN_CANVAS_WIDTH_PX = BASE_CELL_SIZE_PX;
const VIEWPORT_OVERSCAN_CELLS = 2;
// S15P21A604-394 — +/- 버튼과 같은 30~300% 범위, 휠은 한 틱에 5%씩(버튼보다 세밀하게)
const MIN_ZOOM = 30;
const MAX_ZOOM = 300;
const WHEEL_ZOOM_STEP = 5;
// 휠로 연속 확대/축소하는 동안만 .gss-map-stage의 width 전환 애니메이션을 꺼서(즉시 반영)
// 커서 중심 스크롤 보정과 폭 변화가 어긋나며 화면이 흔들리는 것을 막는다.
const WHEEL_ZOOM_IDLE_MS = 200;

export const TopDownCanvas = ({
  scene,
  assets,
  assetUrls,
  selectedObjectId,
  selectedObjectIds,
  placementPreset,
  tileLayer,
  tileBrush,
  tileTool,
  tilesetVisual,
  zoom,
  fitRequestToken,
  focusRequestToken,
  canvasTool,
  showGrid,
  showCollisions,
  editorHiddenObjectIds,
  editorLockedObjectIds,
  onSelectObjects,
  onPlaceObject,
  onMoveObjects,
  onPaintTiles,
  onFloodFillTiles,
  onPickTile,
  onPlacementComplete,
  onZoomChange,
  onViewportSettle,
  restoreViewportCenter,
}: TopDownCanvasProps) => {
  const scrollRef = useRef<HTMLDivElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLDivElement>(null);
  const viewportFrameRef = useRef<number | null>(null);
  const [canvasViewport, setCanvasViewport] = useState<GridViewport | null>(null);
  const [draggingSelection, setDraggingSelection] = useState<DraggingSelection | null>(null);
  const [selectionBox, setSelectionBox] = useState<SelectionBox | null>(null);
  const [tileRectangle, setTileRectangle] = useState<SelectionBox | null>(null);
  const [panning, setPanning] = useState<PanningState | null>(null);
  const [paintingTiles, setPaintingTiles] = useState(false);
  const pendingPaintCellsRef = useRef(new Map<string, { readonly x: number; readonly y: number }>());
  const paintFrameRef = useRef<number | null>(null);
  const paintCallbackRef = useRef(onPaintTiles);
  const tileBrushRef = useRef(tileBrush);
  paintCallbackRef.current = onPaintTiles;
  tileBrushRef.current = tileBrush;
  const canvasWidth = Math.max(MIN_CANVAS_WIDTH_PX, Math.round(scene.width * BASE_CELL_SIZE_PX * (zoom / 100)));
  const backgroundVisual = resolveStaticImageVisual(
    assets.find((asset) => asset.id === scene.backgroundAssetId),
    assetUrls,
  );

  const flushPaintCells = () => {
    paintFrameRef.current = null;
    const cells = [...pendingPaintCellsRef.current.values()];
    pendingPaintCellsRef.current.clear();
    const brush = tileBrushRef.current;
    if (brush !== null && cells.length > 0) paintCallbackRef.current(cells, brush);
  };

  const queuePaintCell = (position: { readonly x: number; readonly y: number }) => {
    pendingPaintCellsRef.current.set(`${position.x}:${position.y}`, position);
    if (paintFrameRef.current === null) paintFrameRef.current = window.requestAnimationFrame(flushPaintCells);
  };

  useEffect(() => () => {
    if (paintFrameRef.current !== null) window.cancelAnimationFrame(paintFrameRef.current);
  }, []);

  const measureViewport = useCallback(() => {
    viewportFrameRef.current = null;
    if (scrollRef.current === null || canvasRef.current === null) return;
    const nextViewport = calculateGridViewport(
      scrollRef.current.getBoundingClientRect(),
      canvasRef.current.getBoundingClientRect(),
      scene.width,
      scene.height,
    );
    setCanvasViewport((current) => equalGridViewports(current, nextViewport) ? current : nextViewport);
  }, [scene.height, scene.width]);

  const scheduleViewportMeasurement = useCallback(() => {
    if (viewportFrameRef.current !== null) return;
    viewportFrameRef.current = window.requestAnimationFrame(measureViewport);
  }, [measureViewport]);

  useEffect(() => {
    const scroll = scrollRef.current;
    const canvas = canvasRef.current;
    if (scroll === null || canvas === null) return;
    scheduleViewportMeasurement();
    scroll.addEventListener('scroll', scheduleViewportMeasurement, { passive: true });
    const resizeObserver = typeof ResizeObserver === 'undefined'
      ? null
      : new ResizeObserver(scheduleViewportMeasurement);
    resizeObserver?.observe(scroll);
    resizeObserver?.observe(canvas);
    window.addEventListener('resize', scheduleViewportMeasurement);
    return () => {
      scroll.removeEventListener('scroll', scheduleViewportMeasurement);
      resizeObserver?.disconnect();
      window.removeEventListener('resize', scheduleViewportMeasurement);
      if (viewportFrameRef.current !== null) window.cancelAnimationFrame(viewportFrameRef.current);
      viewportFrameRef.current = null;
    };
  }, [canvasWidth, scheduleViewportMeasurement]);

  const renderViewport = useMemo(() => canvasViewport === null
    ? null
    : expandGridViewport(canvasViewport, scene.width, scene.height, VIEWPORT_OVERSCAN_CELLS), [canvasViewport, scene.height, scene.width]);
  // S15P21A604-394 — 줌 하한이 10→30이 되면서 원래 임계값(25)은 이제 절대 안 걸림
  // (zoom이 30 밑으로 안 내려가므로). 큰 맵을 낮은 zoom%로 볼 때 개별 DOM 타일 대신
  // 캔버스로 합성해 그리는 이 최적화가 새 하한 근처에서도 계속 동작하도록 값을 올렸다.
  const usingTileOverview = tileLayer !== null && zoom <= 50;
  const renderedObjects = useMemo(() => objectsInViewport(
    scene.objects.filter((object) => !editorHiddenObjectIds.has(object.id)),
    renderViewport,
    selectedObjectId,
  ), [editorHiddenObjectIds, renderViewport, scene.objects, selectedObjectId]);
  const renderedTileIndexes = useMemo(() => usingTileOverview ? [] : tileIndexesInViewport(
    renderViewport,
    scene.width,
    tileLayer?.data.length ?? 0,
  ), [renderViewport, scene.width, tileLayer?.data.length, usingTileOverview]);

  const scrollToGridPosition = useCallback((
    position: { readonly x: number; readonly y: number },
    behavior: ScrollBehavior = 'smooth',
  ) => {
    const scroll = scrollRef.current;
    const canvas = canvasRef.current;
    if (scroll === null || canvas === null) return;
    const scrollRect = scroll.getBoundingClientRect();
    const canvasRect = canvas.getBoundingClientRect();
    const targetLeft = scroll.scrollLeft + (canvasRect.left - scrollRect.left)
      + ((position.x + 0.5) / scene.width) * canvasRect.width;
    const targetTop = scroll.scrollTop + (canvasRect.top - scrollRect.top)
      + ((position.y + 0.5) / scene.height) * canvasRect.height;
    scroll.scrollTo({
      left: Math.max(0, targetLeft - scroll.clientWidth / 2),
      top: Math.max(0, targetTop - scroll.clientHeight / 2),
      behavior,
    });
  }, [scene.height, scene.width]);

  useEffect(() => {
    if (selectedObjectId === null || canvasViewport === null) return;
    const selectedObject = scene.objects.find((object) => object.id === selectedObjectId);
    if (selectedObject === undefined
      || gridViewportContains(canvasViewport, selectedObject.position)) return;
    scrollToGridPosition(selectedObject.position);
  }, [canvasViewport, scene.objects, selectedObjectId, scrollToGridPosition]);

  // S15P21A604-394 — 휠로 확대/축소할 때 커서 아래 지점이 화면상 같은 위치에 남도록.
  // onZoomChange는 zoom을 상위 state로 올려보낼 뿐이라 여기서 스크롤까지 동기로 맞출 수
  // 없다(zoom prop이 갱신되어 canvasWidth가 다시 그려진 뒤에야 최종 크기가 정해짐). 그래서
  // 휠 시점에는 "캔버스 안에서 커서가 가리키는 상대 위치(fraction)"와 "커서의 스크롤
  // 뷰포트 기준 화면 좌표(viewport)"만 기록해 두고, zoom prop이 실제로 바뀐 뒤(effect)
  // 새로 그려진 canvasRect를 다시 읽어서 그 fraction 지점이 같은 화면 좌표에 오도록
  // scrollLeft/Top을 계산한다.
  //
  // 처음엔 "scrollLeft + viewport 오프셋"에 확대 비율만 곱하는 단순한 식을 썼는데,
  // .gss-map-stage에 margin:auto가 있어서 캔버스가 스크롤 뷰포트보다 작을 때는 브라우저가
  // 가운데 정렬시켜버려(스크롤은 0인 채로) 그 전제가 깨졌다 — 30~300%로 범위를 넓히면서
  // "캔버스가 뷰포트보다 작은" 상황을 훨씬 자주 만나게 됐고, 그때마다 계산이 어긋나며
  // 화면이 다른 위치로 튀었다 돌아오는 것처럼 보였다. canvasRect.left를 매번 다시 읽는
  // scrollToGridPosition과 같은 방식으로 바꿔서, 가운데 정렬 여부와 무관하게 항상
  // "지금 실제로 캔버스가 어디 있는지"를 기준으로 계산하도록 고쳤다.
  const wheelZoomAnchorRef = useRef<{
    readonly fractionX: number;
    readonly fractionY: number;
    readonly viewportX: number;
    readonly viewportY: number;
  } | null>(null);

  useEffect(() => {
    const anchor = wheelZoomAnchorRef.current;
    const scroll = scrollRef.current;
    const canvas = canvasRef.current;
    if (anchor === null || scroll === null || canvas === null) return;
    wheelZoomAnchorRef.current = null;
    const scrollRect = scroll.getBoundingClientRect();
    const canvasRect = canvas.getBoundingClientRect();
    const contentX = scroll.scrollLeft + (canvasRect.left - scrollRect.left) + anchor.fractionX * canvasRect.width;
    const contentY = scroll.scrollTop + (canvasRect.top - scrollRect.top) + anchor.fractionY * canvasRect.height;
    scroll.scrollTo({
      left: Math.max(0, contentX - anchor.viewportX),
      top: Math.max(0, contentY - anchor.viewportY),
      behavior: 'auto',
    });
  }, [zoom]);

  // React의 onWheel(JSX) 핸들러는 wheel 리스너를 passive로 등록하므로 그 안에서는
  // preventDefault()가 동작하지 않는다(브라우저 기본 스크롤을 막을 수 없음). 그래서
  // 네이티브 addEventListener를 { passive: false }로 직접 붙인다.
  //
  // pendingZoomRef는 zoom prop이 아니라 "다음 렌더 전까지 우리가 요청해 둔 zoom"을
  // 담는다. 트랙패드/정밀 마우스는 wheel 이벤트를 같은 프레임 안에서 연속으로 여러 번
  // 쏘는데, 그때마다 zoom prop(state)은 아직 갱신 전이라 매번 같은 값에서 계산하면
  // onZoomChange가 같은 값으로만 반복 호출되어(React가 동일 값 setState는 리렌더를
  // 건너뜀) 줌이 안 먹는 것처럼 보인다. pendingZoomRef는 핸들러 안에서 즉시(동기) 갱신해
  // 같은 프레임 안의 연속 이벤트도 누적되게 하고, zoom prop이 실제로 바뀌면 그 값으로
  // 다시 맞춘다(버튼/1:1/맞춤 등 다른 경로로 바뀐 경우도 포함).
  const pendingZoomRef = useRef(zoom);
  useEffect(() => {
    pendingZoomRef.current = zoom;
  }, [zoom]);
  const onZoomChangeRef = useRef(onZoomChange);
  onZoomChangeRef.current = onZoomChange;

  // 휠이 계속 굴러가는 동안은 .gss-map-stage의 width 전환 애니메이션을 꺼서(즉시 반영)
  // 커서 중심 스크롤 보정이 매 틱 최종 폭 기준으로 즉시 적용되게 한다 — 애니메이션이 켜져
  // 있으면 스크롤은 이미 최종 폭 기준으로 점프했는데 실제 폭은 160ms에 걸쳐 뒤늦게
  // 따라오면서 화면이 좌우로 흔들려 보인다. 휠이 멈추고 WHEEL_ZOOM_IDLE_MS 동안 잠잠하면
  // 다시 켜서, +/- 버튼·1:1·맞춤 등 다른 경로의 줌은 기존처럼 부드럽게 움직인다.
  const wheelZoomIdleTimeoutRef = useRef<number | null>(null);

  useEffect(() => {
    const scroll = scrollRef.current;
    if (scroll === null) return;
    const handleWheelZoom = (event: globalThis.WheelEvent) => {
      if (event.deltaY === 0) return;
      event.preventDefault();
      const canvas = canvasRef.current;
      if (canvas === null) return;
      const currentZoom = pendingZoomRef.current;
      const direction = event.deltaY < 0 ? 1 : -1;
      const nextZoom = Math.round(Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, currentZoom + direction * WHEEL_ZOOM_STEP)));
      if (nextZoom === currentZoom) return;
      const scrollRect = scroll.getBoundingClientRect();
      const canvasRect = canvas.getBoundingClientRect();
      wheelZoomAnchorRef.current = {
        fractionX: (event.clientX - canvasRect.left) / canvasRect.width,
        fractionY: (event.clientY - canvasRect.top) / canvasRect.height,
        viewportX: event.clientX - scrollRect.left,
        viewportY: event.clientY - scrollRect.top,
      };
      pendingZoomRef.current = nextZoom;
      stageRef.current?.classList.add('is-wheel-zooming');
      if (wheelZoomIdleTimeoutRef.current !== null) window.clearTimeout(wheelZoomIdleTimeoutRef.current);
      wheelZoomIdleTimeoutRef.current = window.setTimeout(() => {
        wheelZoomIdleTimeoutRef.current = null;
        stageRef.current?.classList.remove('is-wheel-zooming');
      }, WHEEL_ZOOM_IDLE_MS);
      onZoomChangeRef.current(nextZoom);
    };
    scroll.addEventListener('wheel', handleWheelZoom, { passive: false });
    return () => {
      scroll.removeEventListener('wheel', handleWheelZoom);
      if (wheelZoomIdleTimeoutRef.current !== null) window.clearTimeout(wheelZoomIdleTimeoutRef.current);
    };
  }, []);

  useEffect(() => {
    if (fitRequestToken === 0) return;
    const scroll = scrollRef.current;
    if (scroll === null) return;
    onZoomChange(calculateFitZoom(scroll.clientWidth, scroll.clientHeight, scene.width, scene.height));
    window.requestAnimationFrame(() => scrollToGridPosition({ x: (scene.width - 1) / 2, y: (scene.height - 1) / 2 }, 'auto'));
  }, [fitRequestToken, onZoomChange, scene.height, scene.width, scrollToGridPosition]);

  useEffect(() => {
    if (focusRequestToken === 0) return;
    const selectedObject = scene.objects.find((object) => object.id === selectedObjectId);
    if (selectedObject !== undefined) scrollToGridPosition(selectedObject.position);
  }, [focusRequestToken, scene.objects, selectedObjectId, scrollToGridPosition]);

  // S15P21A604-391 — 뷰포트가 바뀔 때마다(스크롤 포함) 현재 보이는 영역의 중심 좌표를
  // 부모에게 알려준다. 부모는 이 값을 Scene을 떠나는 시점에 스냅샷으로만 저장해두고,
  // 매 스크롤마다 리렌더를 일으키지 않도록(ref로 받음) onViewportSettle을 값이 실제로
  // 바뀔 때만 호출한다.
  useEffect(() => {
    if (canvasViewport === null || onViewportSettle === undefined) return;
    onViewportSettle({
      x: (canvasViewport.minX + canvasViewport.maxX) / 2,
      y: (canvasViewport.minY + canvasViewport.maxY) / 2,
    });
  }, [canvasViewport, onViewportSettle]);

  // Scene을 다시 선택했을 때(scene.id가 바뀔 때) 그 Scene에서 마지막으로 보고 있던
  // 위치로 되돌린다. TopDownCanvas는 key 없이 재사용되므로(TOP_DOWN↔PLATFORMER 전환)
  // scene.id 변화만이 "다른 Scene으로 들어왔다"는 신호다.
  useEffect(() => {
    if (restoreViewportCenter === null || restoreViewportCenter === undefined) return;
    const frame = window.requestAnimationFrame(() => scrollToGridPosition(restoreViewportCenter, 'auto'));
    return () => window.cancelAnimationFrame(frame);
  }, [scene.id, restoreViewportCenter, scrollToGridPosition]);

  const finishSelectionBox = () => {
    if (selectionBox === null) return;
    const minX = Math.min(selectionBox.startX, selectionBox.endX);
    const maxX = Math.max(selectionBox.startX, selectionBox.endX);
    const minY = Math.min(selectionBox.startY, selectionBox.endY);
    const maxY = Math.max(selectionBox.startY, selectionBox.endY);
    const objectIds = scene.objects
      .filter((object) => !editorHiddenObjectIds.has(object.id)
        && object.position.x >= minX && object.position.x <= maxX
        && object.position.y >= minY && object.position.y <= maxY)
      .map((object) => object.id);
    onSelectObjects(objectIds, objectIds.at(-1) ?? null);
  };

  const moveOnCanvas = (event: PointerEvent<HTMLDivElement>) => {
    if (canvasRef.current === null) return;
    const position = pointerToGrid(canvasRef.current, event.clientX, event.clientY, scene);
    if (draggingSelection !== null) {
      const deltaX = position.x - draggingSelection.lastX;
      const deltaY = position.y - draggingSelection.lastY;
      if (deltaX !== 0 || deltaY !== 0) {
        onMoveObjects(draggingSelection.objectIds, deltaX, deltaY);
        setDraggingSelection({ ...draggingSelection, lastX: position.x, lastY: position.y });
      }
    }
    if (selectionBox !== null) setSelectionBox({ ...selectionBox, endX: position.x, endY: position.y });
    if (tileRectangle !== null) setTileRectangle({ ...tileRectangle, endX: position.x, endY: position.y });
    if (paintingTiles && tileBrush !== null) queuePaintCell(position);
  };

  const placeFromDrag = (event: DragEvent<HTMLDivElement>) => {
    const presetValue = event.dataTransfer.getData('application/festa-game-object');
    if (presetValue === '' || canvasRef.current === null) return;
    event.preventDefault();
    const position = pointerToGrid(canvasRef.current, event.clientX, event.clientY, scene);
    onPlaceObject(presetValue as GameObject['preset'], position.x, position.y);
    onPlacementComplete();
  };

  const finishCanvasGesture = (event: PointerEvent<HTMLDivElement>) => {
    if (draggingSelection !== null || paintingTiles || selectionBox !== null || tileRectangle !== null) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (paintingTiles) flushPaintCells();
    if (tileRectangle !== null && tileBrush !== null) {
      const minX = Math.min(tileRectangle.startX, tileRectangle.endX);
      const maxX = Math.max(tileRectangle.startX, tileRectangle.endX);
      const minY = Math.min(tileRectangle.startY, tileRectangle.endY);
      const maxY = Math.max(tileRectangle.startY, tileRectangle.endY);
      const cells = Array.from({ length: (maxX - minX + 1) * (maxY - minY + 1) }, (_, index) => ({
        x: minX + (index % (maxX - minX + 1)),
        y: minY + Math.floor(index / (maxX - minX + 1)),
      }));
      onPaintTiles(cells, tileBrush);
    }
    finishSelectionBox();
    setDraggingSelection(null);
    setSelectionBox(null);
    setTileRectangle(null);
    setPaintingTiles(false);
  };

  const tileEditing = tileLayer !== null && (tileTool === 'PICKER' || tileBrush !== null);

  return (
    <div className="gss-canvas-frame">
      <div
      className={`gss-canvas-scroll${panning === null ? '' : ' is-panning'}`}
      onPointerCancel={() => setPanning(null)}
      onPointerDown={(event) => {
        if (canvasTool !== 'PAN' && event.button !== 1) return;
        event.preventDefault();
        event.currentTarget.setPointerCapture(event.pointerId);
        setPanning({
          clientX: event.clientX,
          clientY: event.clientY,
          scrollLeft: event.currentTarget.scrollLeft,
          scrollTop: event.currentTarget.scrollTop,
        });
      }}
      onPointerMove={(event) => {
        if (panning === null) return;
        event.currentTarget.scrollLeft = panning.scrollLeft - (event.clientX - panning.clientX);
        event.currentTarget.scrollTop = panning.scrollTop - (event.clientY - panning.clientY);
      }}
      onPointerUp={(event) => {
        if (panning === null) return;
        if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
        setPanning(null);
      }}
      ref={scrollRef}
    >
      <div className="gss-map-stage" ref={stageRef} style={{ width: `${canvasWidth}px` }}>
        <div
          aria-label={`${scene.name} 맵 편집 캔버스`}
          className={`gss-map-canvas${placementPreset === null && !tileEditing ? '' : ' is-placing'}${showGrid ? '' : ' is-grid-hidden'}${canvasTool === 'PAN' ? ' is-pan-tool' : ''}${tileEditing ? ' is-tile-editing' : ''}${showCollisions ? ' is-showing-collisions' : ''}`}
          onClick={(event) => {
            if (event.target !== event.currentTarget || tileEditing || canvasTool === 'PAN') return;
            if (placementPreset === null) return;
            const position = pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene);
            onPlaceObject(placementPreset, position.x, position.y);
            onPlacementComplete();
          }}
          onDragOver={(event) => event.preventDefault()}
          onDrop={placeFromDrag}
          onPointerCancel={() => {
            if (paintFrameRef.current !== null) window.cancelAnimationFrame(paintFrameRef.current);
            paintFrameRef.current = null;
            pendingPaintCellsRef.current.clear();
            setDraggingSelection(null);
            setSelectionBox(null);
            setTileRectangle(null);
            setPaintingTiles(false);
          }}
          onPointerMove={moveOnCanvas}
          onPointerUp={finishCanvasGesture}
          onPointerDown={(event) => {
            if (canvasTool === 'PAN' || event.target !== event.currentTarget) return;
            if (tileEditing) {
              const position = pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene);
              if (tileTool === 'PICKER') {
                onPickTile(tileLayer?.data[position.y * scene.width + position.x] ?? -1);
                return;
              }
              if (tileBrush === null) return;
              if (tileTool === 'FLOOD_FILL') {
                onFloodFillTiles(position.x, position.y, tileBrush);
                return;
              }
              event.currentTarget.setPointerCapture(event.pointerId);
              if (tileTool === 'RECTANGLE') {
                setTileRectangle({ startX: position.x, startY: position.y, endX: position.x, endY: position.y });
              } else {
                setPaintingTiles(true);
                queuePaintCell(position);
              }
              return;
            }
            if (placementPreset !== null) return;
            event.currentTarget.setPointerCapture(event.pointerId);
            const position = pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene);
            setSelectionBox({ startX: position.x, startY: position.y, endX: position.x, endY: position.y });
          }}
          ref={canvasRef}
          role="application"
          data-rendered-object-count={renderedObjects.length}
          data-rendered-tile-count={renderedTileIndexes.length}
          style={{
            aspectRatio: `${scene.width} / ${scene.height}`,
            ...(backgroundVisual === null ? {} : staticImageBackgroundStyle(backgroundVisual)),
            '--gss-grid-columns': scene.width,
            '--gss-grid-rows': scene.height,
          } as React.CSSProperties}
        >
          {tileLayer !== null && usingTileOverview && (
            <TileOverviewCanvas columns={scene.width} layer={tileLayer} rows={scene.height} tileset={tilesetVisual} />
          )}
          {tileLayer !== null && !usingTileOverview && (
            <div className="gss-tile-layer" aria-hidden="true">
              {renderedTileIndexes.map((index) => {
                const tile = tileLayer.data[index] ?? -1;
                return tile < 0 ? null : (
                <span
                  className={`is-tile-${tile % 8}`}
                  key={`${tileLayer.id}-${index}`}
                  style={{
                    gridColumn: (index % scene.width) + 1,
                    gridRow: Math.floor(index / scene.width) + 1,
                    ...(tilesetVisual === null ? {} : tileBackgroundStyle(tilesetVisual, tile)),
                  }}
                />
                );
              })}
            </div>
          )}
          {selectionBox !== null && (
            <div
              aria-hidden="true"
              className="gss-selection-box"
              style={{
                left: `${(Math.min(selectionBox.startX, selectionBox.endX) / scene.width) * 100}%`,
                top: `${(Math.min(selectionBox.startY, selectionBox.endY) / scene.height) * 100}%`,
                width: `${((Math.abs(selectionBox.endX - selectionBox.startX) + 1) / scene.width) * 100}%`,
                height: `${((Math.abs(selectionBox.endY - selectionBox.startY) + 1) / scene.height) * 100}%`,
              }}
            />
          )}
          {tileRectangle !== null && (
            <div
              aria-hidden="true"
              className="gss-selection-box is-tile-rectangle"
              style={{
                left: `${(Math.min(tileRectangle.startX, tileRectangle.endX) / scene.width) * 100}%`,
                top: `${(Math.min(tileRectangle.startY, tileRectangle.endY) / scene.height) * 100}%`,
                width: `${((Math.abs(tileRectangle.endX - tileRectangle.startX) + 1) / scene.width) * 100}%`,
                height: `${((Math.abs(tileRectangle.endY - tileRectangle.startY) + 1) / scene.height) * 100}%`,
              }}
            />
          )}
          {renderedObjects.map((object) => {
            const definition = findPresetDefinition(object.preset);
            const sprite = object.components.find((component) => component.type === 'SPRITE');
            const spriteVisual = sprite?.type === 'SPRITE'
              ? resolveStaticImageVisual(assets.find((asset) => asset.id === sprite.assetId), assetUrls)
              : null;
            const selected = selectedObjectIds.has(object.id);
            return (
              <button
                aria-label={`${definition.label} ${object.id}, X ${object.position.x}, Y ${object.position.y}`}
                aria-pressed={selected}
                className={`gss-map-object gss-map-object--${object.preset.toLowerCase()}${selected ? ' is-selected' : ''}${selectedObjectId === object.id ? ' is-primary' : ''}${editorLockedObjectIds.has(object.id) ? ' is-editor-locked' : ''}${object.components.some((component) => component.type === 'COLLIDER') ? ' has-collider' : ''}`}
                key={object.id}
                onClick={(event) => {
                  event.stopPropagation();
                  if (event.detail === 0) onSelectObjects([object.id], object.id);
                }}
                onPointerDown={(event) => {
                  if (canvasTool === 'PAN') return;
                  event.stopPropagation();
                  const additive = event.shiftKey || event.ctrlKey || event.metaKey;
                  const nextIds = additive
                    ? selected
                      ? [...selectedObjectIds].filter((id) => id !== object.id)
                      : [...selectedObjectIds, object.id]
                    : selected && selectedObjectIds.size > 1
                      ? [...selectedObjectIds]
                      : [object.id];
                  const primaryId = nextIds.includes(object.id) ? object.id : nextIds.at(-1) ?? null;
                  onSelectObjects(nextIds, primaryId);
                  if (additive && selected) return;
                  const movableIds = nextIds.filter((id) => !editorLockedObjectIds.has(id));
                  if (movableIds.length === 0 || canvasRef.current === null) return;
                  event.currentTarget.parentElement?.setPointerCapture(event.pointerId);
                  const position = pointerToGrid(canvasRef.current, event.clientX, event.clientY, scene);
                  setDraggingSelection({ objectIds: movableIds, lastX: position.x, lastY: position.y });
                }}
                style={{
                  left: `${((object.position.x + 0.5) / scene.width) * 100}%`,
                  top: `${((object.position.y + 0.5) / scene.height) * 100}%`,
                  opacity: object.visible ? 1 : 0.45,
                  transform: `translate(-50%, -50%) scale(${sprite?.type === 'SPRITE' ? (sprite.scale ?? 100) / 100 : 1})`,
                  zIndex: selectedObjectId === object.id ? 40 : selected ? 35 : sprite?.type === 'SPRITE' ? 10 + (sprite.zIndex ?? 2) : 12,
                }}
                title={`${definition.label} · ${object.id}${editorLockedObjectIds.has(object.id) ? ' · 편집 잠금' : ''}`}
                type="button"
              >
                {spriteVisual === null
                  ? <span aria-hidden="true">{definition.icon}</span>
                  : <span aria-hidden="true" className="gss-static-sprite" style={staticImageBackgroundStyle(spriteVisual)} />}
                <small>{object.id}</small>
              </button>
            );
          })}
        </div>
      </div>
      </div>
      <div className="gss-canvas-overlay">
        <div aria-live="polite" className="gss-render-budget">
          화면 오브젝트 {renderedObjects.length}/{scene.objects.length}
          {tileLayer === null ? '' : usingTileOverview ? ` · 타일 ${tileLayer.data.length}칸 합성` : ` · 타일 ${renderedTileIndexes.length}/${tileLayer.data.length}`}
        </div>
        <CanvasMinimap
          hiddenObjectIds={editorHiddenObjectIds}
          onNavigate={(position) => scrollToGridPosition(position)}
          scene={scene}
          selectedObjectId={selectedObjectId}
          viewport={canvasViewport}
        />
      </div>
    </div>
  );
};
