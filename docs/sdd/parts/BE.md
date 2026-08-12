# BE(Spring) 파트 — SDD 권장 브리프

> **상태**: 권장안 (2026-08-12) — BE 담당자가 검토·수정 후 사용한다. 공식 결정이 아니다.
> 사용법: 각 spec의 "specify 입력" 블록을 `/speckit.specify`에 붙여넣고, "예상 clarify"를 `/speckit.clarify`에서 확정한다.
> 공통 전제: 헌법(sdd/constitution.md) — Spring이 영구 상태의 Source of Truth, 경제 변경은 REST/트랜잭션만, Ledger+idempotency.

---

## 담당 spec 목록 (순서 = 착수 순서)

| Spec | 이름 | BE 역할 | 선행 |
|---|---|---|---|
| 001 | auth-user | 주담당 | — |
| 003 | wallet-coin | 주담당 | 001 |
| 004 | booth-slot-lease | 주담당 | 001, 003 |
| 002 | world-session-multiplayer | world-sessions API + connection token 발급 | 001 |
| 005 | booth-studio-layout | Layout 저장·Publish API | 004 |
| 009 | project-exhibition | CRUD + S3 | 004, 005 |
| 013a | avatar-customization | 아바타 저장 API | 001 |
| 016 | booth-laptop-homepage | 홈페이지 URL 필드 + 검증 | 004, 005 |

## spec 001 — auth-user

**specify 입력 (초안)**

```text
SSAFY FESTA의 회원가입/로그인/토큰 발급 기능. 사용자는 이메일+비밀번호로 가입·로그인한다.
토큰은 4계층: Refresh(HttpOnly Cookie, 장수명) / Access(단수명, API 호출) /
Connection(1회용·짧은 TTL, Unity 월드 접속 전용) / WS(상담 WebSocket 전용).
Refresh는 Unity·게임 서버에 절대 전달되지 않는다. 로그아웃·토큰 재발급·회원 탈퇴 포함.
```

**예상 clarify**: Access/Connection TTL 수치, 탈퇴 데이터 보존 기간(docs/26 ②), 닉네임 중복·변경 정책, 비밀번호 정책.

## spec 003 — wallet-coin

**specify 입력 (초안)**

```text
Coin 지갑과 원장(Ledger). 모든 지급/차감은 Ledger 행으로만 발생하며 잔액은 원장에서 유도되거나
원장과 항상 일치해야 한다. 타입: CHARGE/SPEND/REWARD/REFUND. 동일 요청 재시도에 대해
idempotency key로 중복 차감을 방지한다. 잔액 부족 시 명확한 오류. 관리자 수동 조정도 원장에 남긴다.
```

**예상 clarify**: 초기 지급량, 충전 수단 존재 여부(MVP는 지급만?), 수수료율(012로 미룸).

## spec 004 — booth-slot-lease

**specify 입력 (초안)**

```text
USER_RENTAL 슬롯 7개의 임대. 100 Coin/1일. 한 슬롯에는 하나의 활성 임대만 허용하며
Coin 차감과 임대 생성은 하나의 트랜잭션(+비관적 락 또는 유니크 제약)으로 처리한다.
임대 종료 후 콘텐츠(Layout/AI/문서/설문/프로젝트/인벤토리)는 보존된다.
만료 시 신규 입장·신규 AI 요청은 차단하되 진행 중 세션은 짧은 유예 후 종료한다.
```

**예상 clarify (docs/SSAFY_FESTA_임대부스_팀_논의사항.md D01~D08과 동일 — 회의 결과를 그대로 반영)**:
사용자당 활성 임대 한도(권장 1개), '1일'의 의미(권장: 결제 시각부터 24h), 만료 순간 처리(권장: 신규 차단+유예),
연장/재임대(권장: P0는 재임대만), 환불(권장: 변심 환불 없음, 오류는 REFUND 원장), 재임대 시 Draft 시작(권장).

## spec 002 — world-session-multiplayer (Unity 소급 spec의 BE분)

**specify 입력 (초안)**

```text
Unity 클라이언트가 월드에 접속하기 위한 world-sessions API.
GET /world-sessions 응답: { endpoint: { scheme: "wss", host, port }, connectionToken }.
connectionToken은 1회용·짧은 TTL이며 Unity 서버의 Connection Approval에서 검증된다.
Unity에 endpoint를 하드코딩하지 않는다 (헌법 8조). Unity 측 멀티플레이는 POC 검증 완료 — BE는 이 API와 토큰 검증 연동만 신규.
```

**예상 clarify**: 토큰 검증 방식(Unity 서버 → Spring 콜백 vs 서명 검증), 재접속 정책, 동시 접속 상한.

## spec 005 — booth-studio-layout (BE분)

Layout JSON의 저장(Draft)·게시(Published) API. **Layout JSON 스키마는 React/Spring/Unity 3파트 합의로만 변경** (헌법 21조). Unity 기준 구현: `festa-unity` BoothLayoutDto + `Tools/mock-api/` 응답 예시. version 필드 필수.

## spec 013a — avatar-customization (BE분)

```text
사용자 아바타 저장. PUT /users/me/avatar — 인코딩 문자열(최대 500자, 예: "rt|10=Torso_A|17=Hips_B|c=E85D5D")을
저장하고 GET /users/me 에 포함해 반환한다. 서버는 문자열을 파싱하지 않고 길이·문자셋만 검증한다(외형 해석은 클라이언트).
재접속 시 Unity가 이 값으로 외형을 복원한다. 기존 계약안: festa-unity/Docs/avatar-customization-contract.md.
```

**예상 clarify**: 프리셋 마스터 소유(권장: Spring), 부적절 값 정책, 아바타 구매 개념(012로 미룸).

## spec 016 — booth-laptop-homepage (BE분)

```text
부스 소유자가 자기 부스에 홈페이지 URL 1개를 등록한다. Layout 또는 Booth 설정에 homepageUrl 필드 추가.
서버 검증: http/https 형식만 허용, 길이 제한. 방문자에게는 Published 상태에서만 노출된다.
```

**예상 clarify**: URL을 Layout JSON에 넣을지 Booth 엔티티에 넣을지(3파트 합의), 악성 링크 신고 처리(D09 회의 결과 반영).

## 공통 비기능 요구 (모든 spec의 plan 단계에 포함)

- 다른 부스 데이터 접근 차단(권한 검사) 및 테스트 — 업로드된 설문 문서의 권한 테스트 항목 참조
- 시간은 UTC 저장, 표시 Asia/Seoul
- 내부 설문(010, P1)은 결과 집계 API 형식이 이미 문서화됨 — `docs/SSAFY_FESTA_내부설문_관련_업데이트.md`의 응답 예시를 spec 010 작성 시 그대로 입력으로 사용
