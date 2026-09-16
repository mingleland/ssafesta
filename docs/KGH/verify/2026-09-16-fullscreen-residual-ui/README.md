# S15P21A604-733 전체화면 전환 구간 잔존 UI 영상 근거

## 원본

- 파일명: `화면 녹화 중 2026-09-16 105057.mp4`
- 촬영 시각: 2026-09-16 10:50 KST
- 길이: 20.20초
- 영상: H.264, 2880×1704, 30fps
- SHA-256: `2B119BB466EC92F5E63A42666A2AB947F16AF7F8FBBEB45D92FCCEE7602219CB`

원본에는 로그인 계정 선택 화면이 포함돼 있어 저장소에는 넣지 않는다. 아래 프레임에는 계정 정보가 노출되지 않는다.

## 대표 프레임

| 시점 | 파일 | 관측 |
|---:|---|---|
| 10.4초 | [`01-before-transition.png`](01-before-transition.png) | 브라우저 창 상태의 커스터마이징 화면 |
| 10.8초 | [`02-residual-under-dim.png`](02-residual-under-dim.png) | 전체화면 안내 배너와 로딩 dim 아래에 직전 커스터마이징 프레임이 보임 |
| 12.2초 | [`03-next-scene-under-dim.png`](03-next-scene-under-dim.png) | 같은 로딩 dim 아래의 canvas가 다음 씬 프레임으로 변경됨 |

## 추출 명령

```bash
ffmpeg -ss 10.4 -i "화면 녹화 중 2026-09-16 105057.mp4" -frames:v 1 -vf "scale=1440:-2" 01-before-transition.png
ffmpeg -ss 10.8 -i "화면 녹화 중 2026-09-16 105057.mp4" -frames:v 1 -vf "scale=1440:-2" 02-residual-under-dim.png
ffmpeg -ss 12.2 -i "화면 녹화 중 2026-09-16 105057.mp4" -frames:v 1 -vf "scale=1440:-2" 03-next-scene-under-dim.png
```

## 판정 한계

영상은 반투명 상태층 아래에 이전 canvas 프레임이 노출된다는 사실을 입증한다. `requestAnimationFrame` 정지 여부나 Fullscreen 자동 진입이 유일한 원인인지는 입증하지 못한다. 그 인과관계가 필요하면 같은 배포본에서 fullscreen intent ON/OFF A/B 재현과 프레임 간격 계측을 별도로 수행한다.
