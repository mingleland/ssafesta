// S15P21A604-560 — 대화 인물 이미지가 가로로 눌려 보이던 결함의 회귀 방지.
// 원인: staticImageBackgroundStyle()은 시트든 단일 이미지든 항상
// "박스에 꽉 채워 늘리기"(backgroundSize: cols*100% rows*100%)로 그린다.
// 빌트인 4x3 시트(columns:4, rows:3)는 표시 박스 비율(-406에서 고정한
// aspect-ratio: 1/2)과 시트 한 칸(256x512=1:2)의 비율이 우연히 같아서
// 늘려도 안 찌그러지지만, 커스텀 업로드 단일 이미지(columns:1, rows:1)는
// 원본 비율이 제각각이라 그대로 쓰면 가로가 눌려 보인다.
// staticImagePortraitStyle()은 이 케이스만 골라 backgroundSize: contain으로
// 바꿔 원본 비율을 지키고, 시트 크롭 계산(columns>1 || rows>1)은 그대로
// staticImageBackgroundStyle에 위임해 손대지 않는다.
import { describe, expect, it } from 'vitest';
import {
  staticImageBackgroundStyle,
  staticImagePortraitStyle,
  type StaticImageVisual,
} from '../../studio/assets/staticImageVisual.ts';

describe('staticImagePortraitStyle(S15P21A604-560)', () => {
  it('커스텀 단일 이미지(columns:1, rows:1)는 원본 비율을 지키는 contain으로, 박스 하단에 붙여 그린다', () => {
    const visual: StaticImageVisual = { imageUrl: 'blob:custom-portrait', columns: 1, rows: 1, frame: 0 };
    expect(staticImagePortraitStyle(visual)).toEqual({
      aspectRatio: '4 / 5',
      backgroundImage: 'url(blob:custom-portrait)',
      backgroundPosition: 'center bottom',
      backgroundRepeat: 'no-repeat',
      backgroundSize: 'contain',
    });
  });

  it('빌트인 4x3 시트(columns:4, rows:3)는 기존 크롭 계산(늘려 채우기)을 그대로 유지한다 — 회귀 없음', () => {
    const visual: StaticImageVisual = { imageUrl: 'sheet.webp', columns: 4, rows: 3, frame: 5 };
    expect(staticImagePortraitStyle(visual)).toEqual(staticImageBackgroundStyle(visual));
    // frame 5(0-indexed), columns 4 → column = 5%4 = 1, row = floor(5/4) = 1 → x = 1/3*100, y = 1/2*100
    expect(staticImagePortraitStyle(visual)).toEqual({
      backgroundImage: 'url(sheet.webp)',
      backgroundPosition: `${(1 / 3) * 100}% 50%`,
      backgroundRepeat: 'no-repeat',
      backgroundSize: '400% 300%',
    });
  });

  it('가로 1줄짜리 시트(columns>1, rows:1)도 시트로 취급해 늘려 채우기를 유지한다', () => {
    const visual: StaticImageVisual = { imageUrl: 'strip.webp', columns: 3, rows: 1, frame: 1 };
    expect(staticImagePortraitStyle(visual)).toEqual(staticImageBackgroundStyle(visual));
  });

  it('staticImageBackgroundStyle 자체는 그대로 둔다 — 배경·맵 스프라이트 등 다른 소비자 영향 없음', () => {
    const visual: StaticImageVisual = { imageUrl: 'custom-bg.png', columns: 1, rows: 1, frame: 0 };
    expect(staticImageBackgroundStyle(visual)).toEqual({
      backgroundImage: 'url(custom-bg.png)',
      backgroundPosition: '0% 0%',
      backgroundRepeat: 'no-repeat',
      backgroundSize: '100% 100%',
    });
  });
});
