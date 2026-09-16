# 관리자 부스 운영 접근 계약

## 적용 범위

기존 부스 API의 URL·요청·응답 형태는 변경하지 않는다. 이 계약은 같은 부스 운영 API에서 호출자 자격에 따라 달라지는 접근 결과만 정의한다.

## 읽기 권한

전역 Admin은 기존 Owner·편집 직원용 읽기 API를 타 부스에도 호출할 수 있다. 마스터 계정이 소유한 부스도 읽기 요청은 허용한다.

## 변경 권한

전역 Admin은 기존 변경·게시 API를 타 부스에도 호출할 수 있다. 단, 마스터 계정이 소유한 부스 대상 요청은 `403`과 `MASTER_PROTECTED`를 반환한다.

| 상태 | 응답 |
|---|---|
| 일반 회원·CONSULTANT가 타 부스 운영 요청 | 기존 `403 BOOTH_EDITOR_FORBIDDEN` |
| 게스트 운영 요청 | 기존 `403 MEMBER_ONLY` |
| 관리자의 마스터 소유 부스 변경 | `403 MASTER_PROTECTED` |
| 없는 부스 | 기존 `404 BOOTH_NOT_FOUND` |
| 활성 임대가 필요한 변경에서 임대 만료 | 기존 `409 BOOTH_LEASE_EXPIRED` |

## 감사

전역 Admin 자격만으로 타 부스를 변경할 때 `admin_actions`에 다음을 기록한다.

| 필드 | 값 |
|---|---|
| `actor_user_id` | 요청을 수행한 전역 Admin 회원 ID |
| `action` | `BOOTH_EDIT` |
| `target_type` | `BOOTH` |
| `target_id` | 대상 부스 ID |
| `detail` | `null` |

Owner 또는 부스 직원 권한으로 성공한 요청은 이 관리자 감사 행을 만들지 않는다.
