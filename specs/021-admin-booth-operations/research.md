# Research: 관리자 부스 운영 권한

## R-01. 관리자 권한을 어느 경계에서 적용할 것인가

- **Decision**: `BoothAccessGuard`를 유일한 전역 관리자 진입점으로 확장한다.
- **Rationale**: 레이아웃·외관·홈페이지·프로젝트·설문·AI 직원 및 문서·Dashboard·방문 지표가 이미 이 게이트를 사용한다. 각 Controller에 관리자 분기를 복제하면 일부 경로가 빠질 위험이 있다.
- **Alternatives considered**:
  - 관리자 전용 부스 API를 별도로 만든다 — 기존 계약을 중복하고 기능별 동기화 누락 위험이 크다.
  - 각 서비스에서 직접 관리자 여부를 확인한다 — 권한 모델이 흩어져 역할 회귀를 보장할 수 없다.

## R-02. 마스터 부스 조회와 변경을 어떻게 구분할 것인가

- **Decision**: 읽기용 `requireEditor`와 변경용 `requireModifier`를 분리한다. 전역 관리자는 마스터 부스를 읽을 수 있지만, 관리자로서 바꾸거나 게시할 수 없다.
- **Rationale**: 742 불변식은 마스터 계정에 대한 관리자 **조치**를 막되 조회는 허용한다. 현 게이트는 조회와 쓰기가 함께 쓰여 단일 예외를 넣으면 둘 중 하나를 잘못 처리한다.
- **Alternatives considered**:
  - `requireEditor`에서 마스터 부스를 전면 차단한다 — 관리자 목록·대시보드·초안 등 읽기까지 불필요하게 막는다.
  - `requireEditor`에서 전면 허용한다 — 마스터 부스 수정 우회가 생긴다.

## R-03. 기존 수정 경로의 보호 방식

- **Decision**: 활성 임대가 필요한 쓰기는 `requireActiveEditor` 내부에서 변경 권한을 사용하게 하고, 활성 임대가 없는 레이아웃 초안·게시 쓰기만 `requireModifier`로 명시 전환한다.
- **Rationale**: 현재 `requireActiveEditor` 호출부는 외관·홈페이지·프로젝트·설문·AI 직원·AI 문서의 변경 경로이고, `requireEditor` 직접 호출 중 레이아웃 저장·게시만 변경이다. 이 구분이 읽기 규칙과 임대 오류 순서를 보존한다.
- **Alternatives considered**:
  - 모든 호출부를 새 메서드로 바꾼다 — 코드 변경량만 늘고 경로별 누락 가능성이 생긴다.

## R-04. 관리자 타 부스 변경을 어떻게 감사할 것인가

- **Decision**: 관리자로서 타 부스 변경 권한을 통과할 때 같은 트랜잭션 안에 `BOOTH_EDIT` 감사 행을 남긴다. Owner·부스 직원 자격으로 한 작업은 기존과 같이 관리자 감사 대상이 아니다.
- **Rationale**: 742의 감사 원칙을 부스 권한 우회에도 적용하며, `AdminActionRecorder`의 MANDATORY 트랜잭션 규칙이 실패한 변경의 거짓 기록을 막는다.
- **Alternatives considered**:
  - 서비스마다 개별 동작명을 기록한다 — 첫 슬라이스의 서비스별 변경 범위를 불필요하게 넓히고, 기존 경로 일부 누락 가능성이 있다.
  - 감사를 생략한다 — 관리자가 타 부스를 바꾼 근거가 남지 않아 기존 관리자 조치 기준과 불일치한다.

## R-05. 기존 오류 계약

- **Decision**: 부스 없음은 기존 `BOOTH_NOT_FOUND`, 일반 회원·상담 직원은 기존 `BOOTH_EDITOR_FORBIDDEN`, 마스터 대상 관리자 변경은 `MASTER_PROTECTED`, 임대 만료는 기존 `BOOTH_LEASE_EXPIRED`를 유지한다.
- **Rationale**: 기존 FE와 서비스가 오류 코드로 상태를 분기한다. 관리자 예외는 새로운 권한 경로이지 기존 상태 계약을 바꾸는 기능이 아니다.
