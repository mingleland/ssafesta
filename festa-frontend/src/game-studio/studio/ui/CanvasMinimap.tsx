import { useEffect, useRef, type MouseEvent } from 'react';
import type { WorldScene } from '../../contracts/gameProject.ts';
import type { GridViewport } from './canvasViewport.ts';

interface CanvasMinimapProps {
  readonly scene: WorldScene;
  readonly viewport: GridViewport | null;
  readonly selectedObjectId: string | null;
  readonly hiddenObjectIds: ReadonlySet<string>;
  readonly onNavigate: (position: { readonly x: number; readonly y: number }) => void;
}

const WIDTH = 184;
const HEIGHT = 116;
const PADDING = 8;
const LABEL_HEIGHT = 18;

const mapRect = (scene: WorldScene) => {
  const availableWidth = WIDTH - PADDING * 2;
  const availableHeight = HEIGHT - PADDING * 2 - LABEL_HEIGHT;
  const scale = Math.min(availableWidth / scene.width, availableHeight / scene.height);
  const width = scene.width * scale;
  const height = scene.height * scale;
  return {
    left: (WIDTH - width) / 2,
    top: PADDING + (availableHeight - height) / 2,
    width,
    height,
  };
};

export const CanvasMinimap = ({
  scene,
  viewport,
  selectedObjectId,
  hiddenObjectIds,
  onNavigate,
}: CanvasMinimapProps) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const context = canvasRef.current?.getContext('2d');
    if (context === undefined || context === null) return;
    const rect = mapRect(scene);
    context.clearRect(0, 0, WIDTH, HEIGHT);
    context.fillStyle = '#090d12';
    context.fillRect(0, 0, WIDTH, HEIGHT);
    context.fillStyle = '#151c25';
    context.fillRect(rect.left, rect.top, rect.width, rect.height);
    context.strokeStyle = '#3e4c5f';
    context.lineWidth = 1;
    context.strokeRect(rect.left + 0.5, rect.top + 0.5, rect.width - 1, rect.height - 1);

    for (const object of scene.objects) {
      if (hiddenObjectIds.has(object.id)) continue;
      const selected = object.id === selectedObjectId;
      const x = rect.left + ((object.position.x + 0.5) / scene.width) * rect.width;
      const y = rect.top + ((object.position.y + 0.5) / scene.height) * rect.height;
      context.fillStyle = selected ? '#f5c451' : object.preset === 'PLAYER_SPAWN' ? '#6fd7a5' : '#7daef5';
      context.beginPath();
      context.arc(x, y, selected ? 3 : 1.35, 0, Math.PI * 2);
      context.fill();
    }

    if (viewport !== null) {
      const left = rect.left + (viewport.minX / scene.width) * rect.width;
      const top = rect.top + (viewport.minY / scene.height) * rect.height;
      const width = ((viewport.maxX - viewport.minX + 1) / scene.width) * rect.width;
      const height = ((viewport.maxY - viewport.minY + 1) / scene.height) * rect.height;
      context.fillStyle = 'rgba(75, 145, 247, .16)';
      context.fillRect(left, top, width, height);
      context.strokeStyle = '#6aa8ff';
      context.lineWidth = 1.5;
      context.strokeRect(left, top, Math.max(2, width), Math.max(2, height));
    }

    context.fillStyle = '#8c99aa';
    context.font = '8px system-ui, sans-serif';
    context.fillText(`${scene.width} × ${scene.height} · 눌러서 이동`, PADDING, HEIGHT - 6);
  }, [hiddenObjectIds, scene, selectedObjectId, viewport]);

  const navigate = (event: MouseEvent<HTMLButtonElement>) => {
    const bounds = event.currentTarget.getBoundingClientRect();
    const rect = mapRect(scene);
    const x = ((event.clientX - bounds.left) / bounds.width) * WIDTH;
    const y = ((event.clientY - bounds.top) / bounds.height) * HEIGHT;
    onNavigate({
      x: Math.max(0, Math.min(scene.width - 1, ((x - rect.left) / rect.width) * scene.width)),
      y: Math.max(0, Math.min(scene.height - 1, ((y - rect.top) / rect.height) * scene.height)),
    });
  };

  return (
    <button className="gss-canvas-minimap" onClick={navigate} title="미니맵에서 보고 싶은 위치로 이동" type="button">
      <canvas aria-label={`${scene.name} 미니맵`} height={HEIGHT} ref={canvasRef} role="img" width={WIDTH} />
    </button>
  );
};
