# Quickstart: 관리자 부스 운영 권한 검증

## 사전 조건

- PostgreSQL·Redis 테스트 의존성이 실행 가능해야 한다.
- 마스터가 아닌 전역 Admin, 일반 Owner, 일반 회원, CONSULTANT 직원을 준비한다.
- Owner 부스에는 활성 임대를 부여한다.

## 검증 명령

```powershell
cd C:\Users\SSAFY\Desktop\ssafesta\.codex\worktrees\admin-742\backend
.\mvnw.cmd test -Dtest=AdminBoothAccessIntegrationTest,BoothAccessGuardRoleTest
```

## 검증 시나리오

1. 전역 Admin으로 타 회원 부스의 홈페이지 또는 외관을 변경한다. 성공 응답과 `admin_actions`의 `BOOTH_EDIT` 행을 확인한다.
2. 동일 Admin으로 초안을 저장하고 운영 대시보드를 조회한다. 기존 부스 운영 API가 성공하는지 확인한다.
3. 일반 회원과 CONSULTANT가 같은 요청을 보내면 기존 권한 오류를 받는지 확인한다.
4. 마스터 소유 부스에 대해 Admin이 변경·게시 요청을 보내면 `MASTER_PROTECTED`인지 확인하고, 같은 Admin의 대시보드 조회는 성공하는지 확인한다.
5. 마스터 자신이 자신의 부스를 변경하면 성공하는지 확인한다.
6. Admin을 강등한 뒤 같은 타 부스 변경을 재시도해 권한 오류를 받는지 확인한다.

## 회귀 범위

- `BoothAccessGuardRoleTest`
- 기존 레이아웃·홈페이지·프로젝트·설문·AI 직원 및 문서 관련 통합 테스트

## 2026-09-16 실행 결과

`AdminBoothAccessIntegrationTest` 3건과 `BoothAccessGuardRoleTest` 8건을 실행해 실패·오류 없이 통과했다.
