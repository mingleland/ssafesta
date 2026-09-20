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

## 알려진 부작용 — id/objectId 불일치 (2026-08-20 해소, 아래 결과 참조)

Unity `BoothObjectDto`는 `id` 필드를 읽는다(`Booth/Layout/BoothLayoutDto.cs`). 이 JSON은 계약대로 `objectId`를 쓰므로 `JsonUtility`가 이를 무시해 `dto.id == null`이 된다.

- **위치·회전·타입 매핑 판정에는 영향 없음** — position/rotationY/type은 정상 파싱됨
- 오브젝트 이름이 `Booth7_`로 비어 보이거나 라벨에 objectId가 안 뜨면 이 불일치가 원인 — 검증 실패가 아니라 계약 불일치의 실증
- 실사용 영향: `BOOTH_LAPTOP_INTERACT`도 같은 필드에서 objectId를 읽는다. `dto.id`가 null이면 `BoothInteractBridge.cs:18-26`의 `string.IsNullOrEmpty` 가드에 걸려 **이벤트 자체가 전송되지 않는다** — 빈 값이 담긴 이벤트가 나가는 게 아니라 노트북 클릭이 무반응이 된다. 이번 검증 JSON은 `LAPTOP`을 쓰지 않으므로 여기서는 재현되지 않음
- Unity 리드가 [Issue #6](https://github.com/kanghyunsoon/ssafesta/issues/6)에서 DTO 정식 필드를 `objectId`로 변경하고 구 `id`는 하위 호환 보정값으로만 지원하기로 회신 — **수정 후 검증하면 좌표와 식별자 경로를 한 번에 확인 가능**

## 결과 (2026-08-20 회신)

- [x] **판정: 일치** — 실측 world 좌표·forward 벡터가 기대표와 소수점까지 동일

  | objectId | 기대 world | 실측 world | 기대 방향 | 실측 forward |
  |---|---|---|---|---|
  | `survey-verify-1` | (6.5, 0, 7.0) | (6.500, 0.000, 7.000) | +X | (1, 0, 0) |
  | `desk-verify-1` | (3.0, 0, 4.0) | (3.000, 0.000, 4.000) | −Z | (0, 0, −1) |

  x 양·음 / z 양·음 / rotationY 90·180 — 설계한 4개 모호 축 전부 부호 오류 0건. `localPosition`도 JSON 값과 동일, 타입 파싱은 `SurveyKiosk`·`ConsultationDesk`로 정상.
- [x] 앵커 전제 검증됨 — `BoothSlot_7` world `(5.000, 0.000, 5.000)` rotationY 0. 기대표 baseline 정확
- [x] 스크린샷: `game` 브랜치 `docs/KHS/verify/roundtrip-front.png`·`roundtrip-top.png`
- [x] Sign-off: FE 이정헌 / Unity 강형순 (2026-08-20, [Issue #6](https://github.com/kanghyunsoon/ssafesta/issues/6))

육안 판정을 요청했으나 Unity 측이 실제 world 좌표·회전을 숫자로 뽑아 회신했다 — "부호 하나는 반드시 틀린다"는 spec 요구에는 숫자가 맞는 검증이라는 판단.

### 계약 불일치 3건 — 같은 실행으로 함께 확인

Unity 반영 커밋: `origin/game 8852861` "fix(unity): align booth layout contract"

- ① **`objectId` 정식 필드화** + 구 `id`는 fallback(`ResolvedObjectId`). `id` 없이 `objectId`만 보낸 JSON에서 `BoothRuntimeObject.ObjectId` 정상 수신 — 종전이라면 null로 조기 반환됐을 지점
- ② **복수형 엔드포인트 전환** — mock 액세스 로그 `GET /api/v1/booths/7/layouts/published 200`로 실증
- ③ **`assetCode` DTO·Registry 조회 연결** — `type + assetCode` 우선, 없으면 타입 기본 프리팹 fallback. 최초 실행 JSON엔 값이 없어 fallback 경로만 확인됐고, 지정 케이스는 아래 추가 검증에서 통과

### ③ `assetCode` 추가 검증 (2026-08-20, Issue #6)

`FURNITURE` 2개·`DECORATION` 1개로 hit·miss·대소문자를 한 번에 확인 — 전달 경로 통과.

| objectId | 보낸 assetCode | 런타임 `AssetCode` | 결과 |
|---|---|---|---|
| `furn-hit` | `FURNITURE_DEFAULT` | 동일 | 정상 생성 |
| `furn-miss` | `BOGUS_CODE_999` | 동일 | 타입 기본 fallback, 생성됨 |
| `deco-lower` | `decoration_default`(소문자) | 동일 | 정상 생성 |

DTO → `BoothRuntimeObject.AssetCode`까지 온전히 전달되고, 미등록 코드에도 오브젝트가 사라지지 않는다. 세 오브젝트 모두 `childCount=2`로 카탈로그 프리팹 인스턴스(placeholder는 자식 0개). error·warning 0건.

`BoothObjectRegistry.GetPrefab` 직접 호출로 로직도 확인 — 대소문자 무시(`OrdinalIgnoreCase`), 미등록 코드는 타입 기본 fallback, **조회 키가 `type:assetCode` 복합키라 같은 코드 문자열이 타입 경계를 넘어 오매칭되지 않음**(`(SurveyKiosk, "FURNITURE_DEFAULT")` → `SurveyKiosk` 반환). 현재 카탈로그 등록분은 `Furniture:FURNITURE_DEFAULT`·`Decoration:DECORATION_DEFAULT` 2건, 나머지 8종은 `assetCode`가 빈 문자열이라 asset map 제외.

**한계**: 타입마다 프리팹이 정확히 1개뿐이라 `assetCode` 일치로 찾은 프리팹과 fallback 프리팹이 **같은 자산**이다. 생성 결과만으로는 `assetCode`가 실제로 쓰였는지 구분할 수 없어 Registry 직접 호출로 확인한 것. **서로 다른 자산이 선택되는지는 한 타입에 프리팹 2개 이상 등록된 뒤에 검증 가능** — 카탈로그 확장 시 FE가 쓸 `assetCode` 목록을 주면 Unity가 그 값으로 등록하기로 함.

**부수 결정**: 잘못된 `assetCode`가 무경고로 대체되던 문제는 Unity가 `Debug.LogWarning` 추가로 처리(`assetCode`가 비어 있지 않은데 못 찾은 경우만, 미지정은 정상 경로라 로그 없음). **FE 편집기 저장 시점 검증은 추가하지 않기로 회신** — Unity 콘솔·WebGL 로그로 확인 가능. fallback 동작 자체는 유지(오브젝트가 사라지는 것보다 안전).

**정정**: 앞선 스크린샷을 "플레이스홀더 도형"이라 적었으나 부정확 — `SurveyKiosk`·`ConsultationDesk` 모두 카탈로그 등록 프리팹이 있어 실제 프리팹 인스턴스였다. 프리팹 자체가 Editor Builder 기본 도형이라 단순해 보였을 뿐. 좌표 판정에는 영향 없음.

### 잔여

- `BOOTH_LAPTOP_INTERACT` 실제 브라우저 왕복은 WebGL 빌드에서만 발생(에디터는 로그만) — 배포 후 별도 검증
- `assetCode`로 **서로 다른 자산이 선택되는지** — 프리팹 카탈로그 확장 후. FE가 쓸 코드 목록 제공이 선행.
  **주의**: Unity `BuildMap()`이 현재 last-wins라 한 타입에 자산 2개 이상 등록 시 미지정·오타 케이스가 Registry
  배열 순서에 좌우된다(Unity가 인지, 타입별 기본 자산 명시로 좁힐 예정 — FE 계약 무관)
- ~~mock 파일 경로 단수 잔존~~ → 2026-08-20 FE 회신으로 복수형 이동 승인(FE는 `Tools/mock-api` 미사용, 레포 확인 완료)

### Issue #6 종료 (2026-08-20, 강형순)

- mock 경로 복수형 이동 완료 — `git mv`, 커밋 `bad201b`. 부수: README의 `python -m http.server`가
  Windows Store 별칭으로 실행 안 됨 → `py -m http.server`로 주석 수정
- `assetCode` 오타 경고 로그 구현 완료 — 커밋 `6e03998`. 4케이스 검증(`null`·빈 문자열·정확 일치는
  무로그, 오타만 경고 1건)
- 본래 요청(좌표 왕복 검증)·계약 불일치 3건 전부 처리 확인 후 이슈 닫음. 위 잔여 2건은 FE 결정 대기로
  남겨두고 재오픈 가능 상태
