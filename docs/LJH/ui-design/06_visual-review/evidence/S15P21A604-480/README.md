# S15P21A604-480 evidence

브라우저 Checkpoint 캡처. **R3F 캔버스 영역만** 담겨 있다 — 이 세션의 브라우저 도구는
스크린샷을 파일로 쓰지 못해서, 페이지가 `canvas.toDataURL()` 로 만든 PNG 를 로컬 sink 로
보내 저장했다. Studio 셸·Inspector·Asset Library 는 같은 화면에서 눈으로 확인했지만
이 파일 안에는 들어 있지 않다.

| 파일 | 내용 |
|---|---|
| `CP0-baseline.png` | 변경 전 기준. intermediate 기반 렌더 |
| `CP4-runtime-consumer.png` | runtime manifest → runtime GLB 소비 상태, `rotationY 17°` |

## 여기 없는 것

- CP1·CP2·CP3·CP5·CP6 — 브라우저 하네스가 캔버스 폭을 자주 0 으로 만들어 안정적으로 못 찍었다
- Compiler 가 만든 썸네일(`*.webp`) — **의도적으로 커밋하지 않는다.** 벤더 팩이
  `REVIEW_REQUIRED` 인 동안 벤더 지오메트리에서 파생된 산출물을 저장소에 두지 않는다.
  `node tools/assets/compile-runtime-assets.mjs` 를 돌리면 `.generated/runtime/` 에 생긴다
- source ↔ runtime 비교 시트 — 래스터라이저 결함이 남아 있어 만들지 않았다
