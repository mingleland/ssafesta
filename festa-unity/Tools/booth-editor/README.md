# Booth Studio 임시 편집기 — Unity 검증 전용

> **정식 편집기는 FE 파트(React)가 만든다.** 이 도구는 FE 착수 전에
> "프론트에서 배치한 것만 Unity가 생성한다"를 검증하기 위한 임시 하네스다.
> 정식 구현으로 착각하지 않도록 `Tools/` 아래 둔다.

## 실행

```powershell
# festa-unity 폴더에서
py Tools/booth-editor/serve_editor.py
```

- 편집기: <http://localhost:8000/>
- API: `http://localhost:8000/api/v1/booths/7/layouts/published`

Unity 쪽은 `ApiConfig` 에서 **`useMockApi` 체크를 해제**해야 이 서버를 읽는다
(`springBaseUrl` 이 이미 `http://localhost:8000`). 서버를 끄고 작업할 때는
`useMockApi` 를 다시 켜야 콘솔 오류가 안 난다.

## 조작

| 입력 | 동작 |
|---|---|
| 좌측 파츠 버튼 | 부스 중앙에 추가 |
| 드래그 | 이동 (0.25 m 격자 스냅, 부스 영역 밖으로 못 나감) |
| 클릭 | 선택 |
| `R` | 90° 회전 |
| `Del` / `Backspace` | 삭제 |
| 우측 패널 입력칸 | `objectId` · x · z · `rotationY` · `configId` 직접 편집 |

평면도는 위에서 내려다본 것이고 **화면 오른쪽 = +X, 화면 아래 = +Z** 다.
오브젝트에서 뻗은 선이 정면 방향(`rotationY` 0 = +Z)이다.

## 구현한 계약

`docs/08_Backend_API_명세서.md` §4 와 `specs/005-booth-studio-layout` 을 따른다.

| 엔드포인트 | 비고 |
|---|---|
| `GET /api/v1/booths/{id}` | Facade 조회 (`primaryColor` 포함) |
| `GET /api/v1/booths/{id}/layouts/draft` | Draft 조회 |
| `PUT /api/v1/booths/{id}/layouts/draft` | Draft 저장 — **version 낙관적 잠금** |
| `POST /api/v1/booths/{id}/layouts/publish` | 공개 |
| `GET /api/v1/booths/{id}/layouts/published` | **Unity 가 읽는 것** |
| `PUT /api/v1/booths/{id}/facade` | 임시 — 정식 계약 없음 (Issue #17) |

검증하는 규칙:

- **C-05 낙관적 잠금** — `version` 이 서버와 다르면 `409` + `{code, message, requestId}`.
  `code = LAYOUT_VERSION_CONFLICT`, 참고용으로 `currentVersion` 도 준다.
  클라이언트는 **`code` 로만 분기**한다 (`docs/08` §1.3 확정).
- **FR-010 오브젝트 상한** — 13개 이상이면 `400 TOO_MANY_OBJECTS`.
- **FR-005 Draft/Published 분리** — 저장만 하면 `published` 는 바뀌지 않는다.
- **FR-007 공개 전 검사** — 빈 배치는 `400 EMPTY_LAYOUT`.

좌표는 **미터 / 부스 바닥 중앙 원점 / +Z 정면 / `rotationY` 0 = +Z** (spec 005 C-02 확정).
Unity 는 부스 앵커 스케일 10 으로 미터를 world unit 으로 환산한다 (1 m = 10 unit, T-153).

## 저장 위치

`Tools/mock-api/api/v1/booths/{id}/` 아래 `draft`, `published`, `facade.json` 파일로 쓴다.
기존 mock-api 트리를 그대로 쓰므로 정적 서빙(`py -m http.server`)으로도 `published` 를 읽을 수 있다.

## catalog.json

파츠 목록과 실측 치수(m)다. **Unity 레지스트리에서 생성한 값**이라 손으로 고치지 않는다.
파츠를 바꾸면 Unity 쪽에서 다시 뽑아야 편집기 박스 크기가 맞는다.

```json
{ "type": "CONSULTATION_DESK", "label": "상담 데스크", "w": 1.86, "d": 1.16, "h": 0.92, "ox": 0.00, "oz": -0.42 }
```

`ox`/`oz` 는 피벗과 바운즈 중심의 차이다 (상담 데스크는 의자가 뒤에 있어 중심이 -Z 로 치우친다).

## 이 도구가 다루지 않는 것

- **오브젝트별 색상** — Layout 계약에 필드가 없다. Issue #17 합의 대기
- **파츠 잠금·구매** — spec 012 FR-008 은 **서버 검증**이다. Issue #18 대기
- **template → 셸 매핑** — `template` 값이 무엇을 결정하는지 미정. Issue #19 대기
- **인증** — 토큰 검사 없음. 로컬 검증 전용이므로 외부에 노출하지 않는다
