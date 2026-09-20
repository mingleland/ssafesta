# Feature Specification: ERD 초기 스키마 적용

**Feature Branch**: `feature/erd-schema`  
**Created**: 2026-08-18  
**Status**: Draft

## User Scenarios & Testing

### User Story 1 - 서비스 데이터 기반을 준비한다 (Priority: P1)

백엔드 개발자는 새 PostgreSQL 데이터베이스를 준비한 뒤, 서비스의 핵심 영구 데이터를 일관된 구조로 사용할 수 있다.

**Why this priority**: 인증, 부스, 코인, AI, 설문 등 모든 백엔드 기능의 선행 조건이다.

**Independent Test**: 빈 데이터베이스에서 애플리케이션을 실행했을 때 전체 테이블·관계·핵심 제약이 생성된다.

**Acceptance Scenarios**:

1. **Given** 빈 데이터베이스, **When** 백엔드가 시작되면, **Then** 영구 서비스 데이터용 테이블과 관계가 생성된다.
2. **Given** 생성된 스키마, **When** 동일 소셜 계정을 두 사용자에게 연결하려 하면, **Then** 거부된다.
3. **Given** 생성된 스키마, **When** 같은 사용자가 동일 설문에 다시 응답하려 하면, **Then** 중복 저장이 거부된다.

### User Story 2 - 영구 상태와 실시간 상태를 분리한다 (Priority: P2)

운영자는 데이터베이스에 영구 비즈니스 데이터만 저장되고, 게스트 및 Refresh Token 같은 단기 상태는 영구 테이블에 남지 않음을 보장받는다.

**Independent Test**: 생성된 테이블 목록에 Refresh Token 테이블이 없고, 게스트 방문 이벤트는 사용자 식별자 없이도 기록할 수 있다.

## Edge Cases

- 같은 슬롯에 동시에 활성 임대를 만들려는 요청은 데이터베이스 제약 또는 트랜잭션에서 차단돼야 한다.
- RAG 청크는 문서 안에서 같은 순번으로 중복 저장될 수 없어야 한다.
- 보상 원장 항목은 설문 응답 또는 미니게임 세션에 중복 연결될 수 없어야 한다.

## Requirements

### Functional Requirements

- **FR-001**: 시스템은 사용자, 소셜 식별, 지갑 및 코인 원장을 영구 저장해야 한다.
- **FR-002**: 시스템은 물리 슬롯, 논리 부스, 임대 이력, 레이아웃 작업본과 공개본을 분리해 저장해야 한다.
- **FR-003**: 시스템은 AI Agent, 문서, RAG 청크를 부스와 Agent 범위로 격리해 저장해야 한다.
- **FR-004**: 시스템은 프로젝트, 설문, 스태프, 상담, 인벤토리, 미니게임 및 대시보드 데이터를 저장해야 한다.
- **FR-005**: 시스템은 관계 무결성 및 중복 방지에 필요한 기본 키, 외래 키, 유니크 제약을 적용해야 한다.
- **FR-006**: 게스트와 Refresh Token의 단기 상태는 영구 스키마에 저장하지 않아야 한다.
- **FR-007**: 사용자 아바타 외형은 단일 `avatar_code` 값으로 저장해야 한다.

### Key Entities

- **User / OAuth Identity**: 회원과 소셜 로그인 식별자.
- **Wallet / Ledger Entry**: 현재 코인 잔액과 모든 증감 이력.
- **Booth / Slot / Lease / Layout**: 사용자 콘텐츠와 월드의 물리 위치 및 공개 상태.
- **AI Document Chunk**: RAG 검색 범위가 부스와 Agent로 제한된 문서 조각.
- **Survey / Response**: 보상과 1인 1회 응답을 보장하는 설문 데이터.

## Success Criteria

- **SC-001**: 빈 데이터베이스에서 초기 스키마 적용이 실패 없이 완료된다.
- **SC-002**: ERD의 29개 영구 엔티티와 관계가 데이터베이스에 생성된다.
- **SC-003**: 중복 OAuth 연결, 중복 설문 응답, 중복 청크 순번이 데이터베이스에서 차단된다.
- **SC-004**: Refresh Token 영구 테이블이 생성되지 않는다.

## Assumptions

- Refresh Token은 Redis의 TTL 키로 관리한다.
- 층 상세 메타데이터는 후속 기능에서 추가하며, 현재 슬롯의 `floor_no`만 저장한다.
- 아바타 파츠별 장착 테이블은 만들지 않고 Unity가 관리하는 `avatar_code` 문자열만 저장한다.
