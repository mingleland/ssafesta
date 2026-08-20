# GameProject v1 계약 Fixture

`minimal-top-down-dialogue.json`은 v1 MVP의 최소 수직 흐름을 고정한다.

```text
TOP_DOWN 방 시작
→ 열쇠 ON_ENTER
→ 아이템 지급 + 열쇠 숨김
→ 문 ON_INTERACT
→ 아이템 조건 확인
→ DIALOGUE Scene 표시
→ 게임 완료
```

Frontend Preview/Web Runtime과 Backend Validator는 이 파일을 공통 소비자 계약 테스트에 사용한다.
JSON Schema 통과만으로 Publish할 수 없고, 상위 `README.md`의 의미 검증 규칙도 함께 만족해야 한다.

`validate-fixtures.mjs`는 외부 패키지 없이 참조·중복 ID·Scene 유형·Tile 크기 등 의미 규칙을 검사하는
실행 가능한 예시다. 정식 구현에서는 각 파트의 JSON Schema validator와 이 의미 검사를 함께 사용한다.
