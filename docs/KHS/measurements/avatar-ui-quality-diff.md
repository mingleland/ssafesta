# UI 텍스처 화질 차이 — 원본 PNG vs 임포트 결과

표시 크기 **188×156** 로 양쪽을 축소해 비교한다.
사용자가 보는 크기에서 재야 의미가 있다 (T-222).

| 파일 | 평균 차이 | 최대 차이 | 판정 |
|---|---:|---:|---|
| `CategoryIcons/category_bottom_cutout.png` | 7.05 | 604 | **확인 필요** |
| `CategoryIcons/category_glasses.png` | 22.34 | 765 | **확인 필요** |
| `CategoryIcons/category_hair.png` | 14.78 | 769 | **확인 필요** |
| `CategoryIcons/category_hat.png` | 2.91 | 580 | 미미 |
| `CategoryIcons/category_head.png` | 8.83 | 769 | **확인 필요** |
| `CategoryIcons/category_outfit_cutout.png` | 15.87 | 769 | **확인 필요** |
| `CategoryIcons/category_shoes_cutout.png` | 3.71 | 321 | **확인 필요** |
| `CategoryIcons/category_top_cutout.png` | 3.76 | 330 | **확인 필요** |
| `ColorIcons/color_eyebrow.png` | 0.72 | 79 | 차이 없음 |
| `ColorIcons/color_iris.png` | 0.87 | 272 | 차이 없음 |
| `ColorIcons/color_lips.png` | 0.72 | 146 | 차이 없음 |
| `ColorIcons/color_pupil.png` | 1.19 | 325 | 미미 |
| `ColorIcons/color_sclera.png` | 1.14 | 240 | 미미 |
| `ColorIcons/color_skin.png` | 0.80 | 279 | 차이 없음 |
| `FaceThumbnails/face_shapes.png` | 0.88 | 369 | 차이 없음 |
| `HairThumbnails/Individual/hair_01.png` | 1.68 | 465 | 미미 |
| `HairThumbnails/Individual/hair_02.png` | 1.62 | 645 | 미미 |
| `HairThumbnails/Individual/hair_03.png` | 1.78 | 511 | 미미 |
| `HairThumbnails/Individual/hair_04.png` | 1.37 | 329 | 미미 |
| `HairThumbnails/Individual/hair_05.png` | 1.99 | 494 | 미미 |
| `HairThumbnails/Individual/hair_06.png` | 1.86 | 558 | 미미 |
| `HairThumbnails/Individual/hair_07.png` | 1.51 | 557 | 미미 |
| `HairThumbnails/Individual/hair_08.png` | 1.69 | 651 | 미미 |
| `HairThumbnails/Individual/hair_09.png` | 1.66 | 409 | 미미 |
| `HairThumbnails/Individual/hair_10.png` | 1.62 | 582 | 미미 |
| `HairThumbnails/Individual/hair_11.png` | 1.48 | 339 | 미미 |
| `HairThumbnails/Individual/hair_12.png` | 1.35 | 633 | 미미 |
| `HairThumbnails/Individual/hair_13.png` | 1.35 | 633 | 미미 |
| `HairThumbnails/Individual/hair_14.png` | 1.49 | 544 | 미미 |
| `HairThumbnails/Individual/hair_15.png` | 1.27 | 432 | 미미 |
| `HairThumbnails/Individual/hair_16.png` | 1.65 | 466 | 미미 |
| `HairThumbnails/Individual/hair_17.png` | 1.51 | 630 | 미미 |
| `HairThumbnails/Individual/hair_18.png` | 1.53 | 617 | 미미 |
| `HairThumbnails/Individual/hair_19.png` | 1.60 | 584 | 미미 |
| `HairThumbnails/Individual/hair_20.png` | 1.44 | 496 | 미미 |
| `HairThumbnails/Individual/hair_21.png` | 0.27 | 148 | 차이 없음 |
| `HairThumbnails/Individual/hair_22.png` | 0.27 | 149 | 차이 없음 |
| `HairThumbnails/Individual/hair_23.png` | 0.26 | 166 | 차이 없음 |
| `HairThumbnails/Individual/hair_24.png` | 0.26 | 142 | 차이 없음 |
| `HairThumbnails/Individual/hair_25.png` | 0.26 | 192 | 차이 없음 |
| `HairThumbnails/Individual/hair_26.png` | 0.42 | 143 | 차이 없음 |
| `HairThumbnails/Individual/hair_27.png` | 0.46 | 160 | 차이 없음 |
| `HairThumbnails/Individual/hair_28.png` | 0.45 | 154 | 차이 없음 |
| `HairThumbnails/Individual/hair_29.png` | 0.35 | 168 | 차이 없음 |
| `HairThumbnails/Individual/hair_30.png` | 0.26 | 176 | 차이 없음 |
| `HairThumbnails/Individual/hair_31.png` | 0.52 | 158 | 차이 없음 |
| `HatThumbnails/hat_ballcap.png` | 5.62 | 765 | **확인 필요** |
| `HatThumbnails/hat_beanie.png` | 0.92 | 348 | 차이 없음 |
| `HatThumbnails/hat_helmet.png` | 14.54 | 765 | **확인 필요** |

- 최악 평균 차이: **22.34 / 255**

> 평균 차이는 RGBA 채널값(0~255) 기준이다. 원본 PNG 자체를 같은 크기로
> 줄인 것과 비교하므로, 축소로 인한 차이는 양쪽에 똑같이 들어가 상쇄된다.
> 즉 여기 남는 차이는 **해상도 감축과 압축이 만든 것**이다.
