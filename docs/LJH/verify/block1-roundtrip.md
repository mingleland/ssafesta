# 블록 1 — 좌표 왕복 검증 1회 (검증 키트)

spec 005 리뷰 ④칸, C-02 규칙(미터 / 부스 바닥 중앙 원점 / +Z 정면 / rotationY 0=+Z) 실측 검증용.
FE가 계약 그대로 작성한 Layout JSON을 Unity 담당이 렌더해 기대표와 육안 대조한다.

관련: [Issue #6](https://github.com/kanghyunsoon/ssafesta/issues/6)

---

## 검증 JSON

계약 필드 그대로(`objectId`, canonical type) 작성 — `specs/005-booth-studio-layout/spec.md:94-110` 샘플과 동일 스키마.

```json
{
  "boothId": 7,
  "template": "PROJECT_EXHIBITION",
  "version": 1,
  "objects": [
    { "objectId": "survey-verify-1", "type": "SURVEY_KIOSK",
      "position": { "x": 1.5, "y": 0, "z": 2.0 }, "rotationY": 90, "configId": 1 },
    { "objectId": "desk-verify-1", "type": "CONSULTATION_DESK",
      "position": { "x": -2.0, "y": 0, "z": -1.0 }, "rotationY": 180, "configId": 2 }
  ]
}
```

## 기대 결과표

부스 anchor는 `origin/game` `main.unity`의 `BoothSlot_7` world 좌표 `(5, 0, 5)` 기준.

| objectId | type | 부스 상대 좌표 | rotationY | world 좌표 | 향하는 방향 |
|---|---|---|---|---|---|
| survey-verify-1 | SURVEY_KIOSK | (1.5, 0, 2.0) | 90 | (6.5, 0, 7.0) | +X (오른쪽) |
| desk-verify-1 | CONSULTATION_DESK | (-2.0, 0, -1.0) | 180 | (3.0, 0, 4.0) | −Z (뒤) |

좌표 선택 근거 — x 양·음, z 양·음, rotationY 0 외 2종(90·180)까지 4개 모호 축을 한 번에 노출.

## 판정 기준

- 두 오브젝트가 anchor 대비 표에 적힌 world 좌표에 스폰됐는가
- TextMesh 라벨이 `SurveyKiosk`·`ConsultationDesk`로 뜨는가 (canonical 타입 매핑 확인)
- rotationY 90(오른쪽 향함)·180(뒤 향함)이 육안으로 구분되는가

## 실행 절차 (Unity 측)

1. `festa-unity/Tools/mock-api/api/v1/booths/7/layout/published` 내용을 위 JSON으로 교체
2. `ApiConfig.asset`에서 `useMockApi` 체크 해제 (Local `springBaseUrl: http://localhost:8000`)
3. `python -m http.server 8000 --directory Builds/web` (또는 README 안내 경로)로 서빙
4. 에디터 재생 — 정면·상공(위에서 내려다보는 각도) 스크린샷 2장 회신

## 알려진 부작용 — id/objectId 불일치

Unity `BoothObjectDto`는 `id` 필드를 읽는다(`Booth/Layout/BoothLayoutDto.cs`). 이 JSON은 계약대로 `objectId`를 쓰므로 `JsonUtility`가 이를 무시해 `dto.id == null`이 된다.

- **위치·회전·타입 매핑 판정에는 영향 없음** — position/rotationY/type은 정상 파싱됨
- 오브젝트 이름이 `Booth7_`로 비어 보이거나 라벨에 objectId가 안 뜨면 이 불일치가 원인 — 검증 실패가 아니라 계약 불일치의 실증
- 이 문제는 Issue에 별도로 `id`→`objectId` 수정 요청으로 전달 (BOOTH_LAPTOP_INTERACT의 objectId도 이 필드에서 읽으므로 실사용에 영향)

## 결과 (회신 후 기입)

- [ ] 스크린샷 첨부:
- [ ] 판정: 일치 / 불일치 (사유)
- [ ] Sign-off: FE ______ / Unity ______
