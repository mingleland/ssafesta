import type { CSSProperties } from 'react';
import type { AssetReference } from '../../contracts/gameProject.ts';
import { findBuiltinStaticImage, type StaticImageDefinition } from './builtinAssetCatalog.ts';

export interface StaticImageVisual {
  readonly imageUrl: string;
  readonly columns: number;
  readonly rows: number;
  readonly frame: number;
}

export const resolveStaticImageVisual = (
  asset: AssetReference | undefined,
  assetUrls: Readonly<Record<string, string>>,
): StaticImageVisual | null => {
  if (asset === undefined || asset.kind !== 'IMAGE') return null;
  const builtIn: StaticImageDefinition | undefined = findBuiltinStaticImage(asset.source);
  if (builtIn !== undefined) return builtIn;
  const imageUrl = assetUrls[asset.id];
  return imageUrl === undefined ? null : { imageUrl, columns: 1, rows: 1, frame: 0 };
};

export const staticImageBackgroundStyle = (
  visual: StaticImageVisual,
): CSSProperties => {
  const column = visual.frame % visual.columns;
  const row = Math.floor(visual.frame / visual.columns);
  const x = visual.columns === 1 ? 0 : (column / (visual.columns - 1)) * 100;
  const y = visual.rows === 1 ? 0 : (row / (visual.rows - 1)) * 100;
  return {
    backgroundImage: `url(${visual.imageUrl})`,
    backgroundPosition: `${x}% ${y}%`,
    backgroundRepeat: 'no-repeat',
    backgroundSize: `${visual.columns * 100}% ${visual.rows * 100}%`,
  };
};

// S15P21A604-560 — 대화 인물 표시 박스(.gss-dialogue-preview-portrait,
// .grp-dialogue-portrait)는 -406에서 빌트인 4x3 시트 한 칸(256x512 = 1:2)에 맞춰
// aspect-ratio: 1/2로 고정됐다. staticImageBackgroundStyle은 그 박스에 항상
// "꽉 채워 늘리기"(backgroundSize: cols*100% rows*100%)로 그리는데, 커스텀
// 업로드 이미지(columns:1, rows:1)는 시트가 아니라 원본 비율 그대로인 사진이라
// 이 늘리기 때문에 가로가 눌려 보였다(-406의 반대 방향 재발). 시트 크롭
// 계산(columns>1 || rows>1)은 그대로 두고, 시트가 아닌 단일 이미지일 때만
// object-fit: contain에 해당하는 backgroundSize: contain으로 원본 비율을 지킨다.
//
// contain만 적용하면 두 가지가 남는다: ① 박스가 1:2로 세로로 아주 길쭉한데
// 실사용 인물 사진은 대부분 0.8~1.2 비율(정사각형에 가까움)이라, 위아래로
// 크게 남는 여백을 정중앙에 나눠 가지면서 사진이 필요 이상 작아 보인다.
// ② 그 중앙 정렬 때문에 사진 아래에도 빈 공간이 남아 대화 텍스트박스와
// 떨어져 붕 뜬 것처럼 보인다. `aspectRatio`를 인라인으로 4/5로 좁혀 박스를
// 실사진 비율에 가깝게 만들고(빌트인 시트는 이 값을 안 쓰므로 1:2 그대로
// 유지돼 영향 없다), `backgroundPosition`을 `center bottom`으로 바꿔 남는
// 여백을 위쪽에만 몰아 사진 밑변이 박스 밑변(대화 텍스트박스 바로 뒤)에
// 붙게 한다.
export const staticImagePortraitStyle = (
  visual: StaticImageVisual,
): CSSProperties => {
  if (visual.columns === 1 && visual.rows === 1) {
    return {
      aspectRatio: '4 / 5',
      backgroundImage: `url(${visual.imageUrl})`,
      backgroundPosition: 'center bottom',
      backgroundRepeat: 'no-repeat',
      backgroundSize: 'contain',
    };
  }
  return staticImageBackgroundStyle(visual);
};
