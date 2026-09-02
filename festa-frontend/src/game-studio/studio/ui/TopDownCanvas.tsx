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
const MIN_CANVAS_WIDTH_PX = 480;
const VIEWPORT_OVERSCAN_CELLS = 2;

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
  const usingTileOverview = tileLayer !== null && zoom <= 25;
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
      <div className="gss-map-stage" style={{ width: `${canvasWidth}px` }}>
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
