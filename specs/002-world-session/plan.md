# Implementation Plan: 월드 세션 / 멀티플레이 접속

**Spec**: `specs/002-world-session/spec.md`
**Branch**: `game`
**Date**: 2026-08-12
**Status**: Unity 파트 실행 계획 (BE 증분은 BE repo에서 별도 plan)

---

## Summary

Unity 멀티플레이 접속은 **이미 동작한다** (브라우저 3탭 ↔ Docker 서버, 태그 `v0.0.1-poc`).
이 계획의 목표는 새 기능 개발이 아니라 **하드코딩된 접속 경로를 서버 응답 기반으로 전환**하고,
**접속 토큰 검증을 실제로 연결**하며, **AWS wss를 실측으로 확정**하는 것이다.

즉 "만드는 일"보다 "임시로 열어둔 것을 정식 경로로 바꾸는 일"이 대부분이다.

## Technical Context

| 항목 | 값 |
|---|---|
| Engine | Unity 6000.0.78f1 / URP 17.0.4 |
| Netcode | Netcode for GameObjects 2.4.3, Unity Transport 2.5.1 |
| 테스트 | Multiplayer Play Mode 1.5.0 (에디터), 브라우저 다중 탭, Docker 컨테이너 |
| Target | Unity Web (WebGL) 클라이언트 + Linux Dedicated Server |
| Transport | WebSocket 강제 (`UseWebSockets = true`) — 브라우저는 UDP 불가 |
| 배포 | Docker (`festa-world:dev`), 컨테이너명 `festa-world-01`, 포트 7777 |
| 성능 목표 | 1채널 30~40명 (부하 테스트로 확정) |
| 제약 | 서버 주소 하드코딩 금지, Refresh Token 미수신, 배포 시 wss 필수 |

## Constitution Check

| 조항 | 준수 방법 |
|---|---|
| 2조 실시간/영구 분리 | 게임 서버는 위치·스폰만 권위. Coin/Lease API를 호출하지 않는다 |
| 6조 Transport wss | `scheme == "wss"`일 때 `UseEncryption` + `SetClientSecrets`. 이미 구현, AWS 실측만 남음 |
| 8조 endpoint 하드코딩 금지 | `WorldSessionDto` 응답으로만 접속. 개발용 수동 입력 HUD는 `#if UNITY_EDITOR \|\| DEVELOPMENT_BUILD`로 격리 |
| 10조 Refresh 미전달 | Unity는 `connectionToken`만 payload로 전송 |
| 12조 클라이언트 불신 | 서버가 approval에서 토큰 검증. 클라가 보낸 userId를 신뢰하지 않는다 |
| 18조 기준선 동결 | `NetworkPlayer`, `NetworkBootstrap` 재작성 금지 — 델타만 추가 |

**위반 없음.** 신규 아키텍처 도입 없이 기존 경계 안에서 배선만 바꾼다.

## Project Structure

```text
festa-unity/Assets/_Project/Scripts/
├── Network/
│   ├── Bootstrap/NetworkBootstrap.cs      [수정 없음 — 기준선]
│   ├── Connection/ConnectionManager.cs    [수정] 세션 응답 경로 정리, 실패 사유 노출
│   └── Player/NetworkPlayer.cs            [수정 없음 — 기준선]
├── Integration/
│   ├── Contracts/
│   │   ├── Dtos.cs                        [기존] WorldSessionDto / WorldEndpointDto
│   │   └── IWorldSessionClient.cs         [신규] 세션 발급 인터페이스
│   ├── Spring/HttpWorldSessionClient.cs   [신규] 실제 API 호출
│   └── Mock/MockWorldSessionClient.cs     [신규] BE 완성 전 로컬 개발용
└── UI/ConnectionStatusHud.cs              [신규] 접속 실패·재시도 표시

festa-unity/Docker/                        [수정 없음 — 검증 완료]
festa-unity/Docs/deployment-handoff.md     [갱신] AWS 실측 결과 반영
```

## 접근 방식

1. **Mock 우선**: `MockWorldSessionClient`가 로컬 Docker 주소를 반환하게 해서, BE API가 없어도 정식 경로로 개발한다.
   BE가 완성되면 `ApiConfig.useMock`만 끄면 된다 (Booth와 동일 패턴).
2. **토큰 검증은 인터페이스 뒤에**: 서버 approval이 검증 방식(Spring 조회 vs 서명)에 직접 의존하지 않게 한다.
   C-01이 미결이므로 결정 전까지 "항상 승인"하는 구현으로 두되, **경계는 미리 만든다**.
3. **AWS 실측은 코드와 독립**: Infra 파트와 병행. 코드 변경 없이 `scheme`만 `wss`로 오면 동작해야 한다.

## Complexity Tracking

| 위험 | 대응 |
|---|---|
| 토큰 검증 방식 미결(C-01)로 서버 코드가 흔들릴 수 있음 | 인터페이스 분리로 결정 지연을 흡수 |
| Unity Web 로딩이 길어 토큰이 먼저 만료(C-04) | 토큰 발급을 **로딩 완료 후**에 요청하도록 순서 고정 |
| 재접속 정책 미결(C-03) | 이번 범위에서 제외. 실패 표시 + 수동 재시도까지만 |
| 정원 초과 처리 미결(C-02) | MVP 단일 채널이므로 서버가 거부, 클라는 사유 표시 |
