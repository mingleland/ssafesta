import { useRef, useState, type DragEvent, type PointerEvent } from 'react';
import type { AssetReference, GameObject, TileLayer, WorldScene } from '../../contracts/gameProject.ts';
import { findPresetDefinition } from '../model/authoringRegistry.ts';
import { tileBackgroundStyle } from '../assets/tilesetVisual.ts';
import type { TilesetDefinition } from '../assets/builtinAssetCatalog.ts';
import { resolveStaticImageVisual, staticImageBackgroundStyle } from '../assets/staticImageVisual.ts';

interface TopDownCanvasProps {
  readonly scene: WorldScene;
  readonly assets: readonly AssetReference[];
  readonly assetUrls: Readonly<Record<string, string>>;
  readonly selectedObjectId: string | null;
  readonly placementPreset: GameObject['preset'] | null;
  readonly tileLayer: TileLayer | null;
  readonly tileBrush: number | null;
  readonly tilesetVisual: TilesetDefinition | null;
  readonly zoom: number;
  readonly onSelectObject: (objectId: string | null) => void;
  readonly onPlaceObject: (preset: GameObject['preset'], x: number, y: number) => void;
  readonly onMoveObject: (objectId: string, x: number, y: number) => void;
  readonly onPaintTile: (x: number, y: number, tileIndex: number) => void;
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

export const TopDownCanvas = ({
  scene,
  assets,
  assetUrls,
  selectedObjectId,
  placementPreset,
  tileLayer,
  tileBrush,
  tilesetVisual,
  zoom,
  onSelectObject,
  onPlaceObject,
  onMoveObject,
  onPaintTile,
  onPlacementComplete,
}: TopDownCanvasProps) => {
  const canvasRef = useRef<HTMLDivElement>(null);
  const [draggingObjectId, setDraggingObjectId] = useState<string | null>(null);
  const [paintingTiles, setPaintingTiles] = useState(false);
  const backgroundVisual = resolveStaticImageVisual(
    assets.find((asset) => asset.id === scene.backgroundAssetId),
    assetUrls,
  );

  const moveDraggingObject = (event: PointerEvent<HTMLDivElement>) => {
    if (canvasRef.current === null) return;
    const position = pointerToGrid(canvasRef.current, event.clientX, event.clientY, scene);
    if (draggingObjectId !== null) onMoveObject(draggingObjectId, position.x, position.y);
    if (paintingTiles && tileBrush !== null) onPaintTile(position.x, position.y, tileBrush);
  };

  const placeFromDrag = (event: DragEvent<HTMLDivElement>) => {
    const presetValue = event.dataTransfer.getData('application/festa-game-object');
    if (presetValue === '' || canvasRef.current === null) return;
    const preset = presetValue as GameObject['preset'];
    event.preventDefault();
    const position = pointerToGrid(canvasRef.current, event.clientX, event.clientY, scene);
    onPlaceObject(preset, position.x, position.y);
    onPlacementComplete();
  };

  return (
    <div className="gss-canvas-scroll">
      <div
        className="gss-map-stage"
        style={{ width: `${zoom}%` }}
      >
        <div
          aria-label={`${scene.name} 맵 편집 캔버스`}
          className={`gss-map-canvas${placementPreset === null && tileBrush === null ? '' : ' is-placing'}`}
          onClick={(event) => {
            if (event.target !== event.currentTarget) return;
            if (tileBrush !== null) return;
            if (placementPreset === null) {
              onSelectObject(null);
              return;
            }
            const position = pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene);
            onPlaceObject(placementPreset, position.x, position.y);
            onPlacementComplete();
          }}
          onDragOver={(event) => event.preventDefault()}
          onDrop={placeFromDrag}
          onPointerCancel={() => setDraggingObjectId(null)}
          onPointerMove={moveDraggingObject}
          onPointerUp={(event) => {
            if (draggingObjectId !== null || paintingTiles) event.currentTarget.releasePointerCapture(event.pointerId);
            setDraggingObjectId(null);
            setPaintingTiles(false);
          }}
          onPointerDown={(event) => {
            if (tileBrush === null || event.target !== event.currentTarget) return;
            event.currentTarget.setPointerCapture(event.pointerId);
            setPaintingTiles(true);
            const position = pointerToGrid(event.currentTarget, event.clientX, event.clientY, scene);
            onPaintTile(position.x, position.y, tileBrush);
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
          {scene.objects.map((object) => {
            const definition = findPresetDefinition(object.preset);
            const sprite = object.components.find((component) => component.type === 'SPRITE');
            const spriteVisual = sprite?.type === 'SPRITE'
              ? resolveStaticImageVisual(assets.find((asset) => asset.id === sprite.assetId), assetUrls)
              : null;
            return (
              <button
                aria-label={`${definition.label} ${object.id}, X ${object.position.x}, Y ${object.position.y}`}
                className={`gss-map-object gss-map-object--${object.preset.toLowerCase()}${selectedObjectId === object.id ? ' is-selected' : ''}`}
                key={object.id}
                onClick={(event) => {
                  event.stopPropagation();
                  onSelectObject(object.id);
                }}
                onPointerDown={(event) => {
                  event.stopPropagation();
                  event.currentTarget.parentElement?.setPointerCapture(event.pointerId);
                  setDraggingObjectId(object.id);
                  onSelectObject(object.id);
                }}
                style={{
                  left: `${((object.position.x + 0.5) / scene.width) * 100}%`,
                  top: `${((object.position.y + 0.5) / scene.height) * 100}%`,
                  opacity: object.visible ? 1 : 0.45,
                  transform: `translate(-50%, -50%) scale(${sprite?.type === 'SPRITE' ? (sprite.scale ?? 100) / 100 : 1})`,
                  zIndex: selectedObjectId === object.id ? 40 : sprite?.type === 'SPRITE' ? 10 + (sprite.zIndex ?? 2) : 12,
                }}
                title={`${definition.label} · ${object.id}`}
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
