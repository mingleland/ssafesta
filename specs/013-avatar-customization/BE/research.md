# Research: 013a 아바타 저장 API (BE분)

**Phase 0** | 2026-08-24 | 입력: [spec.md](../spec.md) · [contracts/avatar-profile-api.md](../contracts/avatar-profile-api.md) · 헌법 v1.2 · `docs/sdd/parts/BE.md` §013a

모든 결정은 **실측**(코드·이슈·계약 문서)에서 나왔다. 추측으로 확정한 값은 없다.

---

## R-01. 메서드는 `PUT` — Unity mock 주석의 `PATCH`는 구식이다

- **Decision**: `PUT /api/v1/users/me/avatar`
- **Rationale**: 계약 정본 3곳이 일치한다 — `contracts/avatar-profile-api.md:33`(PUT), `docs/sdd/parts/BE.md:82`("PUT /users/me/avatar — 2026-08-21 #24 확정"), `docs/27` 013a 행. 의미상으로도 aggregate 필드 전체 교체라 PUT이 맞다.
- **Alternatives**: `PATCH` — `festa-unity/.../Mock/MockUserApiClient.cs:23` 주석이 "실제 구현: PATCH"라고 적고 있으나, 이 주석은 #24 확정(2026-08-21) **이전**의 POC C 시점 것이다. 계약이 정본이므로 PUT으로 가고, **구현 통보(헌법 24조) 때 Unity에 주석 정정을 요청**한다. 코드 동작은 Mock이라 영향 없다.

## R-02. 복원 경로는 `GET /users/me` 응답 포함 — 별도 GET 엔드포인트는 만들지 않는다

- **Decision**: `MyAccountResponse`에 `avatarCode` 필드를 추가한다(저장 전 `null`). `GET /api/v1/users/me/avatar`는 **구현하지 않는다.**
- **Rationale**: 계약 문서가 두 형태를 모두 허용한다(*"또는 기존 사용자 정보 조회에 avatarCode 필드를 포함시키는 형태도 가능하다"* — `avatar-profile-api.md:41`). **소비자 실측이 이 분기를 정한다**: Unity `UserProfileDto`(`Integration/Contracts/Dtos.cs:11`)에 `avatarCode` 필드가 이미 있고 `MockUserApiClient.GetMyProfileAsync()`가 그 필드로 복원값을 돌려주고 있다. 즉 Unity 복원 경로는 프로필 조회다. `BE.md:82`도 같은 형태다("GET /users/me 에 포함해 반환한다").
- **Alternatives**: 별도 `GET /users/me/avatar`(404 분기 포함) — 소비자가 없다. 만들면 "404 = 저장 없음"이라는 처리 분기를 클라이언트가 하나 더 갖게 되는데, 프로필 포함 방식은 `null` 하나로 끝난다. 나중에 필요해지면 추가는 비파괴적이다.

## R-03. 문자셋 검증 = **인쇄 가능 ASCII** (`0x20`–`0x7E`)

- **Decision**: `avatarCode`의 모든 문자가 `0x20`(space)–`0x7E`(`~`) 범위여야 한다. 위반 시 400.
- **Rationale**: 계약이 BE에 위임한 검증은 "길이 상한과 허용 문자셋만"이다(`avatar-profile-api.md:59`). 허용 문자셋의 값은 어디에도 못 박혀 있지 않아 **생산자(Unity 인코더)의 실제 알파벳에서 유도**했다 — `AvatarAppearance.Encode()`가 만드는 문자열은 프리셋 코드(`sk_01`), 런타임 마커(`fa`), 구분자(`|`·`=`), 슬롯 번호(십진수), 파츠 이름(Sidekick 에셋 이름 — ASCII), 색상(`c=RRGGBB`)로 전부 인쇄 가능 ASCII의 부분집합이다. 여기서 더 좁히면(예: `[A-Za-z0-9_|=]`) 파츠 이름에 `-`나 공백이 들어간 에셋이 추가되는 순간 저장이 거부된다 — **T-24(조용한 길이 초과 거부)와 같은 유형의 사고를 검증 규칙으로 재생산하는 것**이라 하지 않는다. 제어문자(개행·탭 포함)와 비ASCII만 막으면 로그 오염·인코딩 사고를 차단하면서 인코더 진화를 막지 않는다.
- **Alternatives**: ① 인코더 알파벳 정확 매칭 — 위 이유로 기각. ② 검증 없음 — 계약 위반("문자셋만 검증한다"가 명령이다). ③ 비ASCII 허용 — 생산자가 만들 수 없는 값이라 허용할 이유가 없고, 조작된 값 차단(헌법 16조) 폭만 넓어진다.
- **절차**: 이 값은 BE가 위임받은 검증의 구현이지만 생산자에 닿는 값이므로, **구현 통보(헌법 24조)에 명시해 Unity 확인을 받는다.** 계약 문서에도 확정값으로 기입한다.

## R-04. 길이 상한 = **3800자**, 하한 = 1자(blank 거부)

- **Decision**: `1 ≤ length(avatarCode) ≤ 3800`. 컬럼은 `TEXT`(V10 완료)라 DB 제약은 두지 않고 요청 검증으로만 건다.
- **Rationale**: 3800은 Unity가 소유한 값이다 — `AvatarAppearance.MaxEncodedLength = 3800`(`AvatarAppearance.cs:32`). 인코더가 이 길이를 넘는 값을 거부·경고하므로 서버가 같은 값을 상한으로 쓰면 정상 생산물은 전부 통과하고, 조작된 폭주 payload만 걸린다. **이 값을 낮추지 않는다** — `fa|g=1|i=…` 모듈러 형식은 파츠 이름이 그대로 들어가 길다(실측 최대 414자, 파츠 추가 시 증가). "29~32자"·"최대 500자"류의 과거 값은 전부 무효다(헌법 23조, T-24).
- **Alternatives**: 상한 없음 — `TEXT`라 저장은 되지만 조작 payload가 행 크기·대역폭을 잠식한다(헌법 16조 위반 방향).

## R-05. 서버는 문자열을 **파싱하지 않는다** — FR-011과의 관계

- **Decision**: 값 내부 구조(카테고리·itemId·색상)를 해석·검증·정규화하지 않는다. 받은 그대로 저장하고 그대로 돌려준다.
- **Rationale**: 계약이 명시한다 — *"문자열 내용을 파싱하지 않는다. 항목 해석은 클라이언트 책임"*(`avatar-profile-api.md:60`). spec FR-011("서버는 ID가 카탈로그 범위 내인지 검증")과 긴장이 있어 보이지만, FR-011의 "서버"는 **Unity Dedicated Server의 네트워크 struct 검증**을 가리킨다(외형 전파 경로 — Unity/plan 소관). Spring 경로의 항목 검증(미보유 차단)은 C-06 리드 확정이 spec 012로 미뤘고, 계약도 *"상점 도입 시 이 지점에 추가"*로 자리를 잡아뒀다.
- **파생 원칙**: 정규화 금지. facade 팔레트(T059)는 대문자 정규화를 **했지만** 그건 계약이 팔레트 소속 검증을 명령했기 때문이다. 여기는 반대 명령("파싱하지 않는다")이므로 대소문자 하나도 건드리지 않는다 — 왕복 무손실을 테스트로 고정한다(005의 layout_json 원칙과 동일).

## R-06. 검증 위치 = `AvatarCodePolicy` (신설, `NicknamePolicy` 대칭)

- **Decision**: `user` 패키지에 `AvatarCodePolicy` `@Component`를 만들고 길이·문자셋 검증을 담는다. 컨트롤러는 기존 `MyAccountController`에 `@PutMapping("/avatar")` 한 개를 추가한다(별도 컨트롤러·서비스 신설 없음).
- **Rationale**: 같은 패키지의 닉네임 검증이 정확히 이 모양이다(`NicknamePolicy` + 컨트롤러 인라인 `@Transactional`). 엔드포인트 하나에 서비스 층을 새로 세우면 기존 코드와 층이 어긋난다.
- **Alternatives**: `MyAccountService` 신설 — 지금 파일 구조에 없는 층이다. 013a가 커지면(012 소유권 검증) 그때 뽑는다.

## R-07. 실패 응답 = 5필드 봉투 + `FIELD_INVALID`/`field: "avatarCode"`

- **Decision**: 검증 실패는 `400 VALIDATION_FAILED`, `errors[0] = { rule: "FIELD_INVALID", field: "avatarCode", message: <사유 문장> }`. 게스트는 `403 MEMBER_ONLY`(기존 `ErrorCode:24`), 미인증은 리소스 서버 기본 401.
- **Rationale**: #58 C 확정(전 endpoint 5필드 봉투)과 `docs/08` §1.3-1 전역 rule(`FIELD_INVALID`는 문제 필드를 `field`에 담는다 — T058 구현 완료)을 그대로 탄다. 새 오류 코드·rule이 **하나도 필요 없다.** 길이 초과와 문자셋 위반은 **다른 문장**으로 구분한다 — 사용자가 무엇이 틀렸는지 알 수 있어야 한다는 T059의 검증 순서 원칙과 같다(FR-012: 조용히 대체 금지).
- **Alternatives**: 전용 rule(`AVATAR_CODE_INVALID`) 신설 — 요청 필드 하나의 제약 위반이라 `FIELD_INVALID`의 정의에 정확히 들어맞는다. rule 어휘를 늘릴 근거가 없다.

## R-08. 게스트 차단은 `MemberPrincipal.requireMemberId` 재사용

- **Decision**: 기존 헬퍼를 그대로 쓴다. 게스트 토큰(role ≠ MEMBER)은 `403 MEMBER_ONLY`.
- **Rationale**: 게스트 비영속(헌법 12조, FR-015, C-08)의 서버측 방어다. 계약은 "Unity가 게스트면 호출 자체를 안 한다"고 했지만 클라이언트 판단만 믿지 않는다(헌법 16조). `MemberPrincipal` javadoc이 정확히 이 목적("guest slips through" 방지)으로 만들어졌다.

## 미해결 NEEDS CLARIFICATION

없음. C-04(저장 문자열 형식)는 Unity 몫 미확정이지만 **BE에 영향이 없다** — 서버는 파싱하지 않으므로(R-05) 형식이 뭐로 확정되든 길이·문자셋 게이트만 유지하면 된다. 이것이 계약이 "불투명 문자열"을 고른 이유다.
