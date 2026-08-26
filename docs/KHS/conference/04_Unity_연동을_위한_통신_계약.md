# Unity 연동을 위한 통신 계약

## 1. 이 문서의 범위

이 문서는 Frontend나 Backend 담당자의 내부 구현을 설명하지 않는다. KHS가 Unity 기능을 구현하면서 **Unity가 외부 파트에서 무엇을 받아야 하고 무엇을 전달해야 하는지** 정의·정리한 계약만 다룬다.

## 2. 해결하려던 문제

React, Spring, Unity가 같은 기능을 각자 구현하더라도 URL, 필드명, 좌표 기준이 다르면 통합 시 실패한다. 실제로 초기 문서에는 공개 Layout 경로가 `/layouts/published`와 `/layout/published`, 오브젝트 ID가 `objectId`와 `id`로 갈린 적이 있었다.

이 문제를 해결하기 위해 Canonical Contract를 한 번 정하고 Unity DTO와 HTTP Client를 그 계약에 맞췄다.

## 3. Published Layout 계약

Unity가 사용하는 조회 경로는 다음과 같다.

```http
GET /api/v1/booths/{boothId}/layouts/published
Authorization: Bearer {accessToken}
```

핵심 JSON 형태는 다음과 같다.

```json
{
  "boothId": 7,
  "template": "PROJECT_EXHIBITION",
  "version": 2,
  "objects": [
    {
      "objectId": "ai-1",
      "type": "AI_AGENT",
      "assetCode": "ai_staff_01",
      "position": { "x": 1.2, "y": 0.0, "z": 1.5 },
      "rotationY": 0.0,
      "configId": 78
    }
  ]
}
```

### KHS가 Unity 관점에서 고정한 규칙

- 배치 인스턴스 식별자는 `objectId`다.
- Unity 자산 식별자는 `assetCode`다.
- 좌표는 Booth Anchor 기준 로컬 좌표이며 Unity 1 단위를 1m로 본다.
- 회전은 MVP에서 Y축 `rotationY`만 사용한다.
- AI·설문·홈페이지 원문은 넣지 않고 Spring 설정의 `configId`만 가진다.
- Unity는 Draft가 아니라 Published만 조회한다.
- 모르는 `type`은 부스 전체를 중단하지 않고 그 항목만 건너뛴다.

## 4. Draft와 Published를 나눈 이유

Unity 방문자에게 운영자의 편집 중 상태가 즉시 보이면 미완성 배치나 잘못된 설정이 노출된다. 그래서 Unity가 읽는 공개 계약은 Publish 시점의 Version으로 한정했다.

```text
편집 중 Draft 저장
        └─ 방문자에게 보이지 않음
Publish
        └─ 새 Published Version 생성
Unity 입장/갱신
        └─ Published만 조회·렌더링
```

Draft 저장·Publish의 Backend 내부 구현은 다른 담당 범위이며, KHS 작업은 Unity가 Published만 소비하도록 경계를 정한 것이다.

## 5. World Session 계약

Unity Client가 서버 주소를 하드코딩하면 Channel 확장이 불가능하다. 따라서 접속 전 World Session 응답이 다음 값을 준다는 경계를 사용한다.

```json
{
  "connectionToken": "short-lived-token",
  "endpoint": {
    "scheme": "wss",
    "host": "world.example.com",
    "port": 443
  }
}
```

Unity는 `scheme`에 따라 Transport 암호화를 설정하고 `connectionToken`을 NGO Connection Payload에 포함한다. 현재 서버 검증은 POC이므로 운영 검증 방식은 팀 합의가 필요하다.

## 6. Web-Unity 기능 경계

KHS가 구현한 Unity 오브젝트는 클릭 가능한 3D 진입점을 제공하고, 한글 입력이나 긴 업무 UI는 React Overlay로 넘기는 방향을 문서화했다.

```text
Unity 오브젝트 클릭
→ window.FestaUnity.onBoothInteract(json)
→ BOOTH_LAPTOP_INTERACT { boothId, objectId, url? }
→ React Overlay 열기
→ React가 Spring/FastAPI 호출
→ 결과 UI 표시
```

Unity가 담당하는 것은 3D 오브젝트, 거리/클릭 판정, 현재 Booth 문맥이다. React 내부 화면과 API 상태 관리는 이 자료의 범위가 아니다.

2026-08-18 기준 FE·Unity가 위 노트북 이벤트 계약을 확정했고 Unity LAPTOP 타입·클릭 송신부·WebGL 브리지까지 구현했다. `boothId`와 `objectId`는 필수이고 `url`은 선택이며, URL이 없으면 React가 오류 대신 안내를 표시한다.

주의할 실제 실패 형태는 다음과 같다. 계약 JSON이 `objectId`를 보내는데 Unity DTO가 구 `id`만 읽으면 Runtime Object 식별자가 null이 되고, Bridge의 guard가 조기 반환한다. 따라서 **빈 식별자 이벤트가 전달되는 것이 아니라 클릭해도 이벤트가 전혀 전달되지 않는다.** 현재는 `objectId`를 정식 필드로 사용하며 `id`는 구 데이터 fallback으로만 지원한다.

## 7. 실패 처리 계약

| 상황 | Unity 처리 |
|---|---|
| 200 + 정상 JSON | Layout 재생성 |
| 404 | Published 없음으로 보고 빈 부스 유지 |
| 401/403 | 권한 오류 기록, 생성 중단 |
| 5xx/Timeout/CORS | 통신 오류 기록, Client 전체는 유지 |
| 알 수 없는 `type` | 해당 Object만 Skip |
| 빈 `objects` | 정상 빈 Layout으로 처리 |

## 8. 현재 상태와 다음 연결 작업

### KHS가 반영한 부분

- Unity DTO의 `objectId` 기준 정리 완료 (`id`는 하위 호환 fallback)
- `FURNITURE`·`DECORATION`의 `assetCode` 기반 Registry 조회 완료
- Published Endpoint 통일
- `configId`를 Runtime Object에 보존
- Mock/실제 HTTP Client 인터페이스 분리
- 404·Protocol·Network·Parse 실패 분리
- World Session Endpoint를 받을 수 있는 접속 함수

### 타 파트와 함께 검증해야 하는 부분

- 실제 Spring 응답과 Unity JSON 역직렬화 E2E
- 인증 토큰 전달·갱신 방식
- 확정된 React ↔ Unity 이벤트 계약의 Unity 송신부·WebGL 브리지 구현
- Publish 직후 새로고침 방식
- 좌표 편집기 픽셀↔Unity meter 변환 규칙

## 9. 관련 파일과 문서

- `festa-unity/Assets/_Project/Scripts/Integration/Spring/HttpBoothApiClient.cs`
- `festa-unity/Assets/_Project/Scripts/Booth/Layout/BoothLayoutDto.cs`
- `festa-unity/Assets/_Project/Scripts/Booth/Runtime/BoothRuntimeObject.cs`
- `festa-unity/Assets/_Project/Scripts/Network/Connection/ConnectionManager.cs`
- [Backend API 계약](../../08_Backend_API_명세서.md)
- [Realtime 통신 계약](../../16_Realtime_통신_명세서.md)
