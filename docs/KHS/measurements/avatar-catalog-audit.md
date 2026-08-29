# 아바타 카탈로그 실측 (spec 013 C-01·C-03)

- 카탈로그: `Assets/_Project/ScriptableObjects/Avatar/AvatarCatalog.asset`
- 항목 총계: **105개**
- 팔레트 색상: 16개

## C-01 — 카테고리·항목 수 (실조사)

| 카테고리 | 남 | 여 | 공용 | 합계 | 기본값 |
|---|---:|---:|---:|---:|---|
| Head | 4 | 4 | 0 | **8** | Head.01, Head.01 |
| Hair | 0 | 0 | 31 | **31** | Hairstyle.01 |
| Hat | 0 | 0 | 11 | **11** | 볼캡 |
| Glasses | 0 | 0 | 2 | **2** | 사각 안경 |
| Top | 13 | 13 | 0 | **26** | 링거 티셔츠, Top.01 |
| Bottom | 6 | 6 | 0 | **12** | 일자 팬츠, Bot.01 |
| Outfit | 4 | 4 | 0 | **8** | 멜빵바지, Outfit.01 |
| Shoes | 0 | 0 | 7 | **7** | 레이스업 부츠 |

## C-03 — 용량 기여 (디스크 원본 기준)

**고유 = 이 항목만 참조하는 에셋.** 항목 하나를 더할 때 실제로 늘어나는 양이다.
공유분을 항목마다 더하면 중복 계산이라 실제보다 몇 배로 부풀려진다.

| 카테고리 | 항목 | 고유 KB | 항목당 평균 고유 KB |
|---|---:|---:|---:|
| Head | 8 | 7634 | 954 |
| Hair | 31 | 11658 | 376 |
| Hat | 11 | 2101 | 191 |
| Glasses | 2 | 1118 | 559 |
| Top | 26 | 7102 | 273 |
| Bottom | 12 | 3028 | 252 |
| Outfit | 8 | 3426 | 428 |
| Shoes | 7 | 3149 | 449 |
| **고유 합계** | 105 | **39219** | |

- 공유 에셋 합계: **74497 KB** (항목 수와 무관하게 한 번만 든다)
- 아바타 소계: **111.1 MB**

### 고유 용량 상위 10개

| 항목 | 카테고리 | 고유 KB |
|---|---|---:|
| Hairstyle.17 | Hair | 2165 |
| 헬멧 | Hat | 1953 |
| Hairstyle.02 | Hair | 1727 |
| Hairstyle.03 | Hair | 1659 |
| Hairstyle.15 | Hair | 1647 |
| Hairstyle.16 | Hair | 1577 |
| Hairstyle.01 | Hair | 1475 |
| Head.04 | Head | 998 |
| Head.03 | Head | 997 |
| Head.02 | Head | 996 |

## C-05 — 썸네일 보유 현황

- 보유 43 / 105
- **누락 62개** — 이 항목들은 이름 텍스트로만 고르게 된다

  - Head: Head.01, Head.01, Head.02, Head.02, Head.03, Head.03, Head.04, Head.04
  - Hair: Hairstyle.01, Hairstyle.02, Hairstyle.03, Hairstyle.04, Hairstyle.05, Hairstyle.06, Hairstyle.07, Hairstyle.08-1, Hairstyle.08-2, Hairstyle.08-3, Hairstyle.09-1, Hairstyle.09-2, Hairstyle.09-3, Hairstyle.10-1, Hairstyle.10-2, Hairstyle.10-3, Hairstyle.11-1, Hairstyle.11-2, Hairstyle.11-3, Hairstyle.12-1, Hairstyle.12-2, Hairstyle.12-3, Hairstyle.13-1, Hairstyle.13-2, Hairstyle.13-3, Hairstyle.14-1, Hairstyle.14-2, Hairstyle.14-3, Hairstyle.15, Hairstyle.16, Hairstyle.17
  - Top: Top.01, Top.02, Top.03, Top.04, Top.05, Top.06, Top.07-A, Top.07-B, Top.08-A, Top.08-B, Top.09, Top.10, Top.11
  - Bottom: Bot.01, Bot.02, Bot.03, Bot.04, Bot.05, Bot.06
  - Outfit: Outfit.01, Outfit.02, Outfit.03, Outfit.04

## 공유 에셋 상위 10개

항목 수를 늘려도 이 용량은 늘지 않는다. 반대로 여기를 줄이면 전체가 줄어든다.

| 에셋 | 참조 항목 수 | KB |
|---|---:|---:|
| `hair_tied.01_Normal.png` | 8 | 3355 |
| `mat_overalls_Normal.png` | 6 | 3213 |
| `mat_body_Normal.1002.png` | 4 | 2985 |
| `mat_body_Normal.1002.png` | 5 | 2855 |
| `mat_body_Normal.1001.png` | 4 | 2587 |
| `mat_body_Normal.1001.png` | 5 | 2576 |
| `mat_outfit.004_Normal.png` | 2 | 1981 |
| `mat_top.011_blazer_RGBMap.png` | 2 | 1653 |
| `hair_tied.01_RGBMap.png` | 8 | 1628 |
| `mat_top.009_hoodie_Normal.png` | 2 | 1422 |

