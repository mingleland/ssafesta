# BE Quickstart: Draft, Publish, and Portal

## Contract precheck

```powershell
node specs/019-game-studio/contracts/fixtures/validate-fixtures.mjs
```

Backend validator는 같은 manifest의 positive/negative fixture와 오류 코드를 재현해야 한다.

## Integration tests

Backend 구현 브랜치에서 저장소 루트를 기준으로 실행한다.

```powershell
Set-Location backend
.\mvnw.cmd test
```

최소 검증 행렬:

1. owner save 성공과 stale `expectedRevision` 409
2. Draft와 Publish 모두 invalid coordinate/reference/unknown field를 자동 보정 없이 거부
3. Draft와 Publish 모두 JSON 2,000,000 bytes·Scene 50·Scene당 Object 500/Event 300·Asset 300 상한 적용
4. `asset://local`·binary/base64·`data:`·`blob:`·`file:` 거부, `builtin://`·서버 stable `asset://` 허용
5. invalid Publish가 row/pointer/Draft를 부분 변경하지 않음
6. Published row update 금지와 version history 보존
7. 일반 soft delete 뒤 새 실행 거부, 회원 탈퇴 hard delete
8. `GAME_PORTAL` whitelist 저장과 `requiresConfig=true`
9. foreign/missing config는 `CONFIG_NOT_OWNED` error
10. own inactive config는 publish warning, runtime entry는 거부
11. `config_id` 0·음수·2147483648 거부, 2147483647 왕복 보존
12. Portal resolution 응답 `Cache-Control: no-store`
13. Published 포인터 URL은 `Cache-Control: no-cache` + ETag로 재검증

FE 수직 검증은 [FE quickstart](../FE/quickstart.md)를 따른다.
