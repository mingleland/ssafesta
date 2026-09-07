# S15P21A604-480 evidence

**브라우저 시각 acceptance 를 받지 못했다. 이 디렉터리에 이미지가 없다.**

## 무슨 일이 있었나

캡처를 시도해 `CP0-baseline.png` · `CP4-runtime-consumer.png` 두 장을 저장했는데 **둘 다 백지**였다.
두 파일 크기가 정확히 같은 40,450 B 였던 것이 신호였는데, 나는 저장 성공(바이트 수)만 보고
PASS 로 적었다. 이미지를 열어보지 않았다.

## 원인 — 두 겹이다

1. **`preserveDrawingBuffer` 부재.** WebGL 은 합성 뒤 드로잉 버퍼를 비운다. 그래서
   `canvas.toDataURL()` 이 빈 이미지를 돌려줬다. → `R3FBoothRenderer` 의 `gl` 옵션에 추가해 고쳤다.

2. **그걸 고쳐도 버퍼가 비어 있다.** 이 세션의 Browser pane 이 `document.visibilityState = hidden`
   이라 `requestAnimationFrame` 이 멈춘다. 캔버스는 `frameloop="demand"` 로 돌아가므로
   **렌더 자체가 일어나지 않는다.** `gl.readPixels` 로 캔버스 중앙을 읽으면 전부 0 이다.

즉 이 하네스로 찍은 스크린샷은 화면에 보이더라도 이전 프레임의 잔상일 수 있다.
`-480` 라운드에서 내가 "브라우저에서 정상으로 보인다" 고 적은 서술은 그 잔상에 기댄 것이다.

## 그래서 이 회차에서 **증거로 유효한 것**

이미지가 아니라 도구가 직접 잰 값들이다.

```text
network      SURVEY_KIOSK_DEFAULT.glb 200 · intermediate(assets/booth/) 요청 0
DOM          Inspector 크기 0.62 / 0.93 / 0.32 · rotationY 17
compiler     bytes · triangles · vertices · nodes · texture before/after (Node 실측)
gates        npm run build 0 · vitest 720/720 · tsc 0 · oxlint 신규 0
```

## 남은 일

```text
CP0 ~ CP6 재캡처   pane 이 보이는 상태이거나 frameloop 을 always 로 두고 찍어야 한다
visual parity      래스터라이저 지오메트리 추출 결함 수정 후
```

## 커밋하지 않는 것

Compiler 가 만드는 썸네일(`*.webp`)은 여기 두지 않는다. 벤더 팩이 `REVIEW_REQUIRED` 인 동안
벤더 지오메트리 파생물을 저장소에 두지 않는다. 만들려면
`node tools/assets/compile-runtime-assets.mjs` 를 돌려 `.generated/runtime/` 에서 본다.
