import { useEffect, useState } from 'react';
import type { SpriteSheetDefinition } from '../assets/builtinAssetCatalog.ts';
import { SpriteAnimationPreview } from './SpriteAnimationPreview.tsx';

interface SpriteAnimationInspectorProps {
  readonly sheet: SpriteSheetDefinition;
}

export const SpriteAnimationInspector = ({ sheet }: SpriteAnimationInspectorProps) => {
  const [clipId, setClipId] = useState(sheet.clips[0]?.id ?? '');
  const [playing, setPlaying] = useState(true);
  useEffect(() => {
    setClipId(sheet.clips[0]?.id ?? '');
    setPlaying(true);
  }, [sheet]);
  const clip = sheet.clips.find((candidate) => candidate.id === clipId) ?? sheet.clips[0];
  if (clip === undefined) return null;

  return (
    <div className="gss-animation-card">
      <SpriteAnimationPreview clip={clip} paused={!playing} sheet={sheet} size={74} />
      <div>
        <strong>{sheet.label}</strong>
        <label>
          <span>동작 미리보기</span>
          <select aria-label="애니메이션 동작 미리보기" onChange={(event) => setClipId(event.target.value)} value={clip.id}>
            {sheet.clips.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.label}</option>)}
          </select>
        </label>
        <small>{clip.frames.length}프레임 · {clip.fps}fps · {clip.loop ? '반복' : '한 번 재생'}</small>
        <button aria-pressed={playing} onClick={() => setPlaying((current) => !current)} type="button">
          {playing ? '일시정지' : '재생'}
        </button>
      </div>
      <p>이동 방향 애니메이션은 플레이 중 Runtime이 자동으로 선택합니다.</p>
    </div>
  );
};
