# Quickstart: Booth Studio Layout (FE 편집기)

`plan.md`·`data-model.md`의 설계가 실제로 동작하는지 확인하는 검증 시나리오다. 구현 코드는 여기 담지 않는다.

## §1. 기동

```bash
cd festa-frontend
npm install
npm run dev
```

`.env.local`에 `VITE_USE_MOCK=true`(research.md R-09 — 실 BE 없이도 검증 가능. 실 BE가 있으면 mock을 끄고 Base URL만 지정하면 된다, Issue #36에서 실서버 22단계 검증 완료됨).

브라우저에서 `http://localhost:5173/app/studio/7` 접속.

## §2. SC-001 — 기본 왕복

1. 팔레트에서 `AI_AGENT`·`VIDEO_SCREEN`·`FURNITURE` 3개를 캔버스에 배치
2. 각 위치·회전을 드래그로 조정 → `saveStatus`가 `dirty`로 바뀌는지 확인
3. 저장 버튼 클릭 → `saveStatus: saving` → `saved`
4. **새로고침 → 배치가 그대로 복원되는지 확인**
5. Publish 실행 → mock의 published 조회 결과에 오브젝트 3개가 존재하는지 확인

## §3. 좌표 부호 검증 (헌법 21조 핵심 시나리오)

기대값은 `docs/LJH/verify/block1-roundtrip.md`의 실측표를 재사용한다.

1. 오브젝트를 편집기 화면 **아래쪽**으로 드래그 → 저장 요청 JSON(devtools Network 또는 mock 콘솔 로그)에서 `z < 0` 확인
2. 오브젝트를 화면 **오른쪽**으로 드래그 → `x > 0` 확인
3. `rotationY = 90` 입력 → 오브젝트가 `+X`(화면 오른쪽)를 바라보는 표시로 렌더되는지 확인

## §4. 12개 상한

1. 오브젝트 12개를 배치 → 팔레트 전체가 비활성화되는지 확인
2. mock 데이터에 13개짜리 Draft를 직접 주입해 로드 → Publish 시도 시 차단되고 사유가 표시되는지 확인
3. **Draft 저장 시점에도 서버가 막는지** 확인 — 13개 상태에서 Publish가 아니라 저장을 시도해도 거부돼야 한다(spec.md §BE 검토 상세 A: "FE 버그로 200개짜리 작업본이 저장돼 JSONB만 부푸는 것"을 막기 위해 Draft 저장에도 같은 상한을 건다)

## §5. revision 충돌 UX

1. 브라우저 탭 2개로 같은 부스(`/app/studio/7`) 진입
2. 탭 A에서 저장 → 성공(`revision` +1)
3. 탭 B에서 저장(탭 A의 저장을 모르는 상태) → `409 LAYOUT_REVISION_CONFLICT` 수신
4. "다른 편집자가 저장했습니다. 새로고침 후 다시 시도해 주세요" 같은 안내가 뜨는지 확인
5. **자동 병합이 일어나지 않는 것**도 확인 항목 — 탭 B의 미저장 변경이 조용히 사라지거나 덮어써지면 안 되고, 사용자가 명시적으로 재로드해야 한다

## §6. C-04 경고 — 서버 warnings 렌더링 (확정, [#45](https://github.com/kanghyunsoon/ssafesta/issues/45))

1. `configId` 없이 `AI_AGENT` 오브젝트 배치 (연결 요건 미충족 — data-model.md ObjectType 판정표)
2. Publish 시도 → **응답의 `warnings`에 해당 오브젝트가 실려 오고**, `errors`는 비어 있어 진행 버튼이 열리는지 확인
3. `FURNITURE`(장식형, 연결 요건 없음)는 목록에 나타나지 않는 것도 함께 확인
4. **판정 주체 확인** — mock에서 같은 항목을 `warnings` 대신 `errors`로 옮겨 응답하게 바꾸면, FE 코드 수정 없이 진행 버튼이 잠기는지 확인한다. 이것이 통과해야 FR-016 설계가 성립한다(향후 결정이 뒤집혀도 같은 경로로 대응 가능함을 증명)

## §6b. §10 기하 계약 — FE 실시간 경고가 서버와 일치하는지 (신규, #19)

1. `VIDEO_SCREEN`(bounds `(−1.50,0,−0.15)`~`(1.20,2.10,0.15)`)을 부스 모서리 근처에 `rotationY=90`으로 배치 → 앵커는 영역 안인데 회전된 실물이 영역을 벗어나는 좌표를 찾아 배치
2. 저장 시도 → FE 사전 검증이 배치를 클램프하거나 경고하는지, 그리고 강제로 저장을 보내면 서버가 `409`+`AREA_OUT_OF_BOUNDS`로 거부하는지 **둘 다** 확인(같은 답이어야 한다)
3. 상호작용 파츠(`AI_AGENT` 등) 하나를 벽에 바짝 붙여 관람 띠(0.7m)가 확보 안 되게 배치 → FE가 배치 즉시 "이 배치는 접근할 수 없습니다" 류 경고를 보여주는지, Publish 응답의 `warnings`에 같은 `rule: FRONT_BLOCKED`가 오는지 확인
4. 두 결과가 갈리면 FE 알고리즘이 §10-2·§10-3과 다른 것 — `objectTypes.ts`의 bounds·회전식·통행 파라미터를 계약 문서와 재대조

## §7. facade 편집 (FR-018)

1. Studio에서 facade 패널 진입 → `themeCode`를 `SSAFY_BLUE`로, `primaryColor`를 `#1677C8`로 변경
2. 저장 → `PUT /booths/7/facade` 요청 1건, **Draft/Publish 흐름을 타지 않는 것** 확인(레이아웃 `revision`이 올라가지 않아야 한다)
3. 새로고침 → 값 유지 확인
4. `primaryColor`에 `#FFF`(3자리) 입력 → 저장 거부되고 한글 사유가 표시되는지 확인(hex 6자리 계약)
5. `logoUrl`에 `http://`(비 https) 또는 2048자 초과 입력 → 거부 확인
6. ⚠️ `GET /booth-facade-palette`는 아직 없어 이 시나리오에서 mock으로 12색 목록을 만들어 대체한다 — 실 BE 연동 전까지 팔레트 UI는 mock 데이터 기준으로만 검증 가능

## §8. 막혔을 때

- 배치가 상하 반전되어 보인다 → `coords.ts`의 부호 반전 로직 확인
- 오브젝트가 전부 한 점에 뭉친다 → `PX_PER_M`과 `viewBox` 단위가 섞였을 가능성(research.md R-03 — 표시 배율과 저장 좌표는 완전히 분리돼야 한다)
- 저장이 `MALFORMED_LAYOUT`으로 계속 실패한다 → 요청 JSON에 계약 외 필드가 섞여 있는지 확인(data-model.md — 미지 필드는 전부 거부됨, Issue #36)
- `409 LAYOUT_REVISION_CONFLICT`가 반복된다 → 재로드 후 `baseRevision` 갱신을 빠뜨렸는지 확인
- 충돌 후 UI에 "서버 revision: undefined"처럼 뜬다 → `errors[0]`에서 revision 숫자를 파싱하려는 코드가 있는 것. 그 필드는 없다(data-model.md) — `GET /draft` 재호출로 교체
- Publish는 통과하는데 저장이 `AREA_OUT_OF_BOUNDS`로 거부된다 → FE 사전 검증이 앵커 위치만 보고 실물(회전 AABB)을 안 본 것. §10-1 bounds·§10-2 회전식 재확인

단위 테스트(research.md R-10 채택 시): `npx vitest run`
