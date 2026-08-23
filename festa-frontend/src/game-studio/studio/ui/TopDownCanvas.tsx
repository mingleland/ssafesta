import { useEffect, useRef, useState, type DragEvent, type PointerEvent } from 'react';
import type { AssetReference, GameObject, TileLayer, WorldScene } from '../../contracts/gameProject.ts';
import { findPresetDefinition } from '../model/authoringRegistry.ts';
import { tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import type { TilesetDefinition } from '../assets/builtinAssetCatalog.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';

export type CanvasTool = 'SELECT' | 'PAN';

interface TopDownCanvasProps {
  readonly scene: WorldScene;
  readonly assets: readonly AssetReference[];
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly selectedObjectId: string | null;
  readonly selectedObjectIds: ReadonlySet<string>;
  readonly placementPreset: GameObject['preset'] | null;
  readonly tileLayer: TileLayer | null;
  readonly tileBrush: number | null;
  readonly tilesetVisual: TilesetDefinition | null;
  readonly zoom: number;
  readonly canvasTool: CanvasTool;
  readonly showGrid: boolean;
  readonly editorHiddenObjectIds: ReadonlySet<string>;
  readonly editorLockedObjectIds: ReadonlySet<string>;
  readonly onSelectObjects: (objectIds: readonly string[], primaryObjectId: string | null) => void;
  readonly onPlaceObject: (preset: GameObject['preset'], x: number, y: number) => void;
  readonly onMoveObjects: (objectIds: readonly string[], deltaX: number, deltaY: number) => void;
  readonly onPaintTiles: (cells: readonly { readonly x: number; readonly y: number }[], tileIndex: number) => void;
  readonly onPlacementComplete: () => void;
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

export const TopDownCanvas = ({
  scene,
  assets,
  assetUrls,
  selectedObjectId,
  selectedObjectIds,
  placementPreset,
  tileLayer,
  tileBrush,
  tilesetVisual,
  zoom,
  canvasTool,
  showGrid,
  editorHiddenObjectIds,
  editorLockedObjectIds,
  onSelectObjects,
  onPlaceObject,
  onMoveObjects,
  onPaintTiles,
  onPlacementComplete,
}: TopDownCanvasProps) => {
  const canvasRef = useRef<HTMLDivElement>(null);
  const [draggingSelection, setDraggingSelection] = useState<DraggingSelection | null>(null);
  const [selectionBox, setSelectionBox] = useState<SelectionBox | null>(null);
  const [panning, setPanning] = useState<PanningState | null>(null);
  const [paintingTiles, setPaintingTiles] = useState(false);
  const pendingPaintCellsRef = useRef(new Map<string, { readonly x: number; readonly y: number }>());
  const paintFrameRef = useRef<number | null>(null);
  const paintCallbackRef = useRef(onPaintTiles);
  const tileBrushRef = useRef(tileBrush);
  paintCallbackRef.current = onPaintTiles;
  tileBrushRef.current = tileBrush;
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
    if (draggingSelection !== null || paintingTiles || selectionBox !== null) {
      if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
    }
    if (paintingTiles) flushPaintCells();
    finishSelectionBox();
    setDraggingSelection(null);
    setSelectionBox(null);
    setPaintingTiles(false);
  };

  return (
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
    >
      <div className="gss-map-stage" style={{ width: `${zoom}%` }}>
        <div
          aria-label={`${scene.name} 맵 편집 캔버스`}
          className={`gss-map-canvas${placementPreset === null && tileBrush === null ? '' : ' is-placing'}${showGrid ? '' : ' is-grid-hidden'}${canvasTool === 'PAN' ? ' is-pan-tool' : ''}`}
          onClick={(event) => {
            if (event.target !== event.currentTarget || tileBrush !== null || canvasTool === 'PAN') return;
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
            setPaintingTiles(false);
          }}
          onPointerMove={moveOnCanvas}
          onPointerUp={finishCanvasGesture}
          onPointerDown={(event) => {
            if (canvasTool === 'PAN' || event.target !== event.currentTarget) return;
            if (tileBrush !== null) {
              event.currentTarget.setPointerCapture(event.pointerId);
              setPaintingTiles(true);
              queuePaintCell(pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene));
              return;
            }
            if (placementPreset !== null) return;
            event.currentTarget.setPointerCapture(event.pointerId);
            const position = pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene);
            setSelectionBox({ startX: position.x, startY: position.y, endX: position.x, endY: position.y });
          }}
          ref={canvasRef}
          role="application"
          style={{
            aspectRatio: `${scene.width} / ${scene.height}`,
            ...(backgroundVisual === null ? {} : staticImageBackgroundStyle(backgroundVisual)),
            '--gss-grid-columns': scene.width,
            '--gss-grid-rows': scene.height,
          } as React.CSSProperties}
        >
          {tileLayer !== null && (
            <div className="gss-tile-layer" aria-hidden="true">
              {tileLayer.data.map((tile, index) => tile < 0 ? null : (
                <span
                  className={`is-tile-${tile % 8}`}
                  key={`${tileLayer.id}-${index}`}
                  style={{
                    gridColumn: (index % scene.width) + 1,
                    gridRow: Math.floor(index / scene.width) + 1,
                    ...(tilesetVisual === null ? {} : tileBackgroundStyle(tilesetVisual, tile)),
                  }}
                />
              ))}
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
          {scene.objects.filter((object) => !editorHiddenObjectIds.has(object.id)).map((object) => {
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
                className={`gss-map-object gss-map-object--${object.preset.toLowerCase()}${selected ? ' is-selected' : ''}${selectedObjectId === object.id ? ' is-primary' : ''}${editorLockedObjectIds.has(object.id) ? ' is-editor-locked' : ''}`}
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
  );
};
