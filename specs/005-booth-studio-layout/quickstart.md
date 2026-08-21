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

## §5. revision 충돌 UX

1. 브라우저 탭 2개로 같은 부스(`/app/studio/7`) 진입
2. 탭 A에서 저장 → 성공(`revision` +1)
3. 탭 B에서 저장(탭 A의 저장을 모르는 상태) → `409 LAYOUT_REVISION_CONFLICT` 수신
4. "다른 편집자가 저장했습니다. 새로고침 후 다시 시도해 주세요" 같은 안내가 뜨는지 확인
5. **자동 병합이 일어나지 않는 것**도 확인 항목 — 탭 B의 미저장 변경이 조용히 사라지거나 덮어써지면 안 되고, 사용자가 명시적으로 재로드해야 한다

## §6. C-04 경고 (PROVISIONAL)

1. `configId` 없이 `AI_AGENT` 오브젝트 배치 (연결 요건 미충족 — data-model.md ObjectType 판정표)
2. Publish 시도 → 차단되지 않고 미연결 목록에 표시된 채로 진행 가능한지 확인
3. `FURNITURE`(장식형, 연결 요건 없음)는 목록에 나타나지 않는 것도 함께 확인

## §7. 막혔을 때

- 배치가 상하 반전되어 보인다 → `coords.ts`의 부호 반전 로직 확인
- 오브젝트가 전부 한 점에 뭉친다 → `PX_PER_M`과 `viewBox` 단위가 섞였을 가능성(research.md R-03 — 표시 배율과 저장 좌표는 완전히 분리돼야 한다)
- 저장이 `MALFORMED_LAYOUT`으로 계속 실패한다 → 요청 JSON에 계약 외 필드가 섞여 있는지 확인(data-model.md — 미지 필드는 전부 거부됨, Issue #36)
- `409 LAYOUT_REVISION_CONFLICT`가 반복된다 → 재로드 후 `baseRevision` 갱신을 빠뜨렸는지 확인

단위 테스트(research.md R-10 채택 시): `npx vitest run`
