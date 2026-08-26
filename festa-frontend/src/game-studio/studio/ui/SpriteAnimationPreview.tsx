import { useEffect, useState } from 'react';
import type { AnimationClipDefinition, SpriteSheetDefinition } from '../assets/builtinAssetCatalog.ts';

interface SpriteAnimationPreviewProps {
  readonly sheet: SpriteSheetDefinition;
  readonly clip?: AnimationClipDefinition;
  readonly size?: number;
  readonly paused?: boolean;
}

export const SpriteAnimationPreview = ({
  sheet,
  clip = sheet.clips[0],
  size = 64,
  paused = false,
}: SpriteAnimationPreviewProps) => {
  const [frameIndex, setFrameIndex] = useState(0);

  useEffect(() => {
    setFrameIndex(0);
    if (paused || clip === undefined || clip.frames.length < 2 || clip.fps <= 0) return;
    const timer = window.setInterval(() => {
      setFrameIndex((current) => (current + 1) % clip.frames.length);
    }, 1_000 / clip.fps);
    return () => window.clearInterval(timer);
  }, [clip, paused]);

  if (clip === undefined) return null;
  const frame = clip.frames[frameIndex] ?? clip.frames[0] ?? 0;
  const x = sheet.columns === 1 ? 0 : (frame / (sheet.columns - 1)) * 100;
  const y = sheet.rows === 1 ? 0 : (clip.row / (sheet.rows - 1)) * 100;

  return (
    <span
      aria-label={`${sheet.label} ${clip.label} 애니메이션 미리보기`}
      className="gss-sprite-preview"
      role="img"
      style={{
        width: size,
        height: size,
        backgroundImage: `url(${sheet.imageUrl})`,
        backgroundPosition: `${x}% ${y}%`,
        backgroundSize: `${sheet.columns * 100}% ${sheet.rows * 100}%`,
      }}
    />
  );
};
