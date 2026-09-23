# SSAFY FESTA ERD — 현행 스키마

> **기준**: `develop` `caa1c60d` (2026-09-23) · Flyway `V1` ~ `V47` (V19 결번) · PostgreSQL 17 + pgvector
> **만든 방법**: 마이그레이션 46개를 빈 PostgreSQL 에 순서대로 적용한 뒤 `information_schema`·`pg_constraint` 에서 테이블·컬럼·외래 키·유일 인덱스를 뽑았다. 손으로 옮겨 적은 부분이 없다.
> **다른 문서와의 관계**: [09_DB_ERD_DB_설계서](./09_DB_ERD_DB_설계서.md) 는 착수 전 설계 초안(Draft)이다. 설계 원칙과 결정 배경은 그 문서가, 지금 DB 에 실제로 있는 구조는 이 문서가 기준이다.

| 항목 | 값 |
|---|---:|
| 테이블 | 47 |
| 외래 키 | 78 |
| 유일 인덱스 (PK 제외) | 42 |

Redis 에 두는 실시간 상태(Presence·채널·세션)와 Unity 게임 서버의 월드 상태는 영구 저장소가 아니라서 이 ERD 에 없다.

## 0. 영역 구성

| 영역 | 테이블 | 무엇을 저장하나 |
|---|---|---|
| 계정·권한 | users, oauth_identities, account_status_histories, admin_actions, avatar_presets | 소셜 계정 연결, 계정 정지 이력, 관리자 조치 감사, 아바타 프리셋 |
| 경제·아이템·이벤트 | wallets, coin_ledger_entries, coin_reconciliation_runs, catalog_items, user_inventory_items, event_prizes, event_purchases, feedback_submissions | 코인 잔액과 원장, 잔액 정합성 점검, 파츠 상점·보유 품목, 이벤트 경품, 피드백 |
| 부스·전시 | booth_slots, booths, booth_leases, booth_layout_drafts, booth_layout_published_versions, booth_staffs, staff_invitations, booth_visit_events, booth_daily_metrics, projects, project_likes, project_logo_uploads | 슬롯 임대, 배치 작업본·공개본, 직원, 방문 계측, 프로젝트 전시 |
| AI·상담 | ai_agents, ai_documents, ai_document_jobs, ai_document_chunks, ai_document_chunk_staging, storage_reconciliation_log, consultations, consultation_messages | AI 직원 설정, RAG 문서·청크(임베딩), 문서 처리 작업, 사람 상담 |
| 설문 | surveys, survey_questions, survey_options, survey_responses, survey_answers, survey_answer_options | 부스·이벤트 설문과 응답 |
| 게임·오락기 | games, game_drafts, game_published_versions, game_assets, game_asset_delete_queue, minigame_sessions, arcade_machine_bindings, arcade_score_records | Game Studio 게임 작업본·게시본, 미니게임 세션, 오락기 연결·점수 |

## 1. 전체 관계도

외래 키만 그린 개요다. 컬럼은 2장의 영역별 ERD에 있다. 선 이름은 외래 키 컬럼이고, `|o` 는 NULL 을 허용하는 외래 키다.

```mermaid
erDiagram
    USERS ||--o{ OAUTH_IDENTITIES : "user_id"
    USERS ||--o{ WALLETS : "user_id"
    WALLETS ||--o{ COIN_LEDGER_ENTRIES : "wallet_id"
    USERS ||--o{ BOOTHS : "owner_user_id"
    BOOTH_SLOTS |o--o{ BOOTHS : "current_slot_id"
    BOOTHS ||--o{ BOOTH_LEASES : "booth_id"
    BOOTH_SLOTS ||--o{ BOOTH_LEASES : "slot_id"
    USERS ||--o{ BOOTH_LEASES : "lessee_user_id"
    BOOTHS ||--o{ BOOTH_LAYOUT_DRAFTS : "booth_id"
    USERS ||--o{ BOOTH_LAYOUT_DRAFTS : "updated_by_user_id"
    BOOTHS ||--o{ BOOTH_LAYOUT_PUBLISHED_VERSIONS : "booth_id"
    USERS ||--o{ BOOTH_LAYOUT_PUBLISHED_VERSIONS : "published_by_user_id"
    BOOTHS ||--o{ AI_AGENTS : "booth_id"
    BOOTHS ||--o{ AI_DOCUMENTS : "booth_id"
    AI_AGENTS ||--o{ AI_DOCUMENTS : "agent_id"
    USERS ||--o{ AI_DOCUMENTS : "uploaded_by_user_id"
    BOOTHS ||--o{ AI_DOCUMENT_CHUNKS : "booth_id"
    AI_AGENTS ||--o{ AI_DOCUMENT_CHUNKS : "agent_id"
    BOOTHS ||--o{ BOOTH_STAFFS : "booth_id"
    USERS ||--o{ BOOTH_STAFFS : "user_id"
    BOOTHS ||--o{ STAFF_INVITATIONS : "booth_id"
    USERS ||--o{ STAFF_INVITATIONS : "invited_user_id"
    USERS ||--o{ STAFF_INVITATIONS : "invited_by_user_id"
    BOOTHS ||--o{ CONSULTATIONS : "booth_id"
    USERS |o--o{ CONSULTATIONS : "visitor_user_id"
    AI_AGENTS |o--o{ CONSULTATIONS : "agent_id"
    USERS |o--o{ CONSULTATIONS : "staff_user_id"
    CONSULTATIONS ||--o{ CONSULTATION_MESSAGES : "consultation_id"
    USERS ||--o{ CONSULTATION_MESSAGES : "sender_user_id"
    BOOTHS ||--o{ PROJECTS : "booth_id"
    PROJECTS ||--o{ PROJECT_LIKES : "project_id"
    USERS ||--o{ PROJECT_LIKES : "user_id"
    BOOTHS |o--o{ SURVEYS : "booth_id"
    USERS |o--o{ SURVEYS : "created_by_user_id"
    SURVEYS ||--o{ SURVEY_QUESTIONS : "survey_id"
    SURVEY_QUESTIONS ||--o{ SURVEY_OPTIONS : "question_id"
    SURVEYS ||--o{ SURVEY_RESPONSES : "survey_id"
    USERS |o--o{ SURVEY_RESPONSES : "respondent_user_id"
    COIN_LEDGER_ENTRIES |o--o{ SURVEY_RESPONSES : "reward_ledger_entry_id"
    SURVEY_RESPONSES ||--o{ SURVEY_ANSWERS : "response_id"
    SURVEY_QUESTIONS ||--o{ SURVEY_ANSWERS : "question_id"
    SURVEY_ANSWERS ||--o{ SURVEY_ANSWER_OPTIONS : "answer_id"
    SURVEY_OPTIONS ||--o{ SURVEY_ANSWER_OPTIONS : "option_id"
    USERS ||--o{ USER_INVENTORY_ITEMS : "user_id"
    CATALOG_ITEMS ||--o{ USER_INVENTORY_ITEMS : "catalog_item_id"
    USERS ||--o{ MINIGAME_SESSIONS : "user_id"
    COIN_LEDGER_ENTRIES |o--o{ MINIGAME_SESSIONS : "reward_ledger_entry_id"
    BOOTHS ||--o{ BOOTH_VISIT_EVENTS : "booth_id"
    USERS |o--o{ BOOTH_VISIT_EVENTS : "visitor_user_id"
    BOOTHS ||--o{ BOOTH_DAILY_METRICS : "booth_id"
    USERS |o--o{ ACCOUNT_STATUS_HISTORIES : "user_id"
    USERS |o--o{ ACCOUNT_STATUS_HISTORIES : "actor_user_id"
    BOOTH_LAYOUT_PUBLISHED_VERSIONS ||--o{ BOOTHS : "id"
    USERS ||--o{ GAMES : "owner_user_id"
    GAMES ||--o{ GAME_DRAFTS : "game_id"
    USERS ||--o{ GAME_DRAFTS : "updated_by_user_id"
    GAMES ||--o{ GAME_PUBLISHED_VERSIONS : "game_id"
    USERS ||--o{ GAME_PUBLISHED_VERSIONS : "published_by_user_id"
    GAME_PUBLISHED_VERSIONS ||--o{ GAMES : "id"
    GAMES ||--o{ GAME_ASSETS : "game_id"
    USERS ||--o{ GAME_ASSETS : "created_by_user_id"
    AI_DOCUMENTS ||--o{ AI_DOCUMENT_JOBS : "document_id"
    BOOTHS ||--o{ AI_DOCUMENT_JOBS : "booth_id"
    AI_AGENTS ||--o{ AI_DOCUMENT_JOBS : "agent_id"
    AI_DOCUMENTS ||--o{ AI_DOCUMENT_CHUNKS : "document_id"
    AI_DOCUMENT_JOBS ||--o{ AI_DOCUMENT_CHUNK_STAGING : "job_id"
    AI_DOCUMENTS ||--o{ STORAGE_RECONCILIATION_LOG : "document_id"
    GAMES ||--o{ ARCADE_MACHINE_BINDINGS : "game_id"
    AI_DOCUMENTS |o--o{ AI_DOCUMENTS : "replaces_document_id"
    AI_DOCUMENTS |o--o{ PROJECTS : "facts_document_id"
    EVENT_PRIZES ||--o{ EVENT_PURCHASES : "prize_id"
    USERS ||--o{ EVENT_PURCHASES : "buyer_user_id"
    COIN_LEDGER_ENTRIES |o--o{ EVENT_PURCHASES : "ledger_entry_id"
    USERS ||--o{ AVATAR_PRESETS : "user_id"
    BOOTHS ||--o{ PROJECT_LOGO_UPLOADS : "booth_id"
    USERS |o--o{ ARCADE_MACHINE_BINDINGS : "owner_user_id"
    USERS ||--o{ FEEDBACK_SUBMISSIONS : "user_id"
    USERS ||--o{ ARCADE_SCORE_RECORDS : "user_id"
```

## 2. 영역별 ERD

### 2-1. 계정·권한

`users` · `oauth_identities` · `account_status_histories` · `admin_actions` · `avatar_presets`

```mermaid
erDiagram
    USERS {
        bigint id PK
        varchar account_type
        varchar nickname
        text avatar_code
        varchar status
        timestamptz created_at
        timestamptz updated_at
        timestamptz suspended_at
        text suspension_reason
        boolean is_master
    }
    OAUTH_IDENTITIES {
        bigint id PK
        bigint user_id FK
        varchar provider
        varchar provider_subject
        timestamptz created_at
    }
    ACCOUNT_STATUS_HISTORIES {
        bigint id PK
        bigint user_id FK
        varchar previous_status
        varchar current_status
        text reason
        bigint actor_user_id FK
        timestamptz created_at
    }
    ADMIN_ACTIONS {
        bigint id PK
        bigint actor_user_id
        varchar action
        varchar target_type
        bigint target_id
        text detail
        timestamptz created_at
    }
    AVATAR_PRESETS {
        bigint user_id PK
        smallint slot PK
        text avatar_code
        timestamptz updated_at
    }
    USERS ||--o{ OAUTH_IDENTITIES : "user_id"
    USERS |o--o{ ACCOUNT_STATUS_HISTORIES : "user_id"
    USERS |o--o{ ACCOUNT_STATUS_HISTORIES : "actor_user_id"
    USERS ||--o{ AVATAR_PRESETS : "user_id"
```

다른 영역 테이블은 이름만 있는 박스로 나온다.

### 2-2. 경제·아이템·이벤트

`wallets` · `coin_ledger_entries` · `coin_reconciliation_runs` · `catalog_items` · `user_inventory_items` · `event_prizes` · `event_purchases` · `feedback_submissions`

```mermaid
erDiagram
    WALLETS {
        bigint id PK
        bigint user_id FK
        int balance
        timestamptz updated_at
    }
    COIN_LEDGER_ENTRIES {
        bigint id PK
        bigint wallet_id FK
        varchar entry_type
        int amount
        int balance_after
        varchar reason_type
        varchar reference_type
        varchar reference_id
        varchar idempotency_key
        timestamptz created_at
    }
    COIN_RECONCILIATION_RUNS {
        bigint id PK
        timestamptz started_at
        timestamptz finished_at
        varchar status
        int checked_wallet_count
        int mismatched_wallet_count
        jsonb mismatches
        text failure_reason
    }
    CATALOG_ITEMS {
        bigint id PK
        varchar item_code
        varchar name
        varchar item_type
        varchar equip_slot
        int price
        varchar asset_key
        boolean is_stackable
        boolean is_on_sale
        timestamptz created_at
    }
    USER_INVENTORY_ITEMS {
        bigint id PK
        bigint user_id FK
        bigint catalog_item_id FK
        int quantity
        varchar acquired_via
        timestamptz acquired_at
    }
    EVENT_PRIZES {
        bigint id PK
        varchar name
        int price_coin
        int stock
        boolean active
        timestamptz created_at
        timestamptz updated_at
        timestamptz closes_at
        int winner_count
        timestamptz drawn_at
    }
    EVENT_PURCHASES {
        bigint id PK
        bigint prize_id FK
        bigint buyer_user_id FK
        int quantity
        int coin_spent
        bigint ledger_entry_id FK
        varchar fulfillment
        text note
        varchar idempotency_key
        timestamptz purchased_at
        timestamptz updated_at
        varchar campus
        varchar team_name
        varchar recipient_name
        boolean won
    }
    FEEDBACK_SUBMISSIONS {
        bigint id PK
        bigint user_id FK
        text content
        boolean first_found
        timestamptz created_at
    }
    USERS ||--o{ WALLETS : "user_id"
    WALLETS ||--o{ COIN_LEDGER_ENTRIES : "wallet_id"
    USERS ||--o{ USER_INVENTORY_ITEMS : "user_id"
    CATALOG_ITEMS ||--o{ USER_INVENTORY_ITEMS : "catalog_item_id"
    EVENT_PRIZES ||--o{ EVENT_PURCHASES : "prize_id"
    USERS ||--o{ EVENT_PURCHASES : "buyer_user_id"
    COIN_LEDGER_ENTRIES |o--o{ EVENT_PURCHASES : "ledger_entry_id"
    USERS ||--o{ FEEDBACK_SUBMISSIONS : "user_id"
```

다른 영역 테이블은 이름만 있는 박스로 나온다.

### 2-3. 부스·전시

`booth_slots` · `booths` · `booth_leases` · `booth_layout_drafts` · `booth_layout_published_versions` · `booth_staffs` · `staff_invitations` · `booth_visit_events` · `booth_daily_metrics` · `projects` · `project_likes` · `project_logo_uploads`

```mermaid
erDiagram
    BOOTH_SLOTS {
        bigint id PK
        varchar slot_code
        smallint floor_no
        varchar slot_type
        varchar status
        timestamptz created_at
    }
    BOOTHS {
        bigint id PK
        bigint owner_user_id FK
        bigint current_slot_id FK
        varchar name
        text description
        varchar facade_theme_code
        varchar homepage_url
        varchar status
        timestamptz created_at
        timestamptz updated_at
        int published_layout_version
        varchar facade_primary_color
        varchar facade_sign_text
        varchar facade_logo_url
        boolean admin_owned
    }
    BOOTH_LEASES {
        bigint id PK
        bigint booth_id FK
        bigint slot_id FK
        bigint lessee_user_id FK
        varchar status
        timestamptz starts_at
        timestamptz ends_at
        int charged_coin
        timestamptz created_at
        timestamptz expiry_warning_sent_at
        boolean permanent
    }
    BOOTH_LAYOUT_DRAFTS {
        bigint booth_id PK
        int schema_version
        jsonb layout_json
        bigint revision
        bigint updated_by_user_id FK
        timestamptz updated_at
    }
    BOOTH_LAYOUT_PUBLISHED_VERSIONS {
        bigint id PK
        bigint booth_id FK
        int version_no
        int schema_version
        jsonb layout_json
        bigint published_by_user_id FK
        timestamptz published_at
    }
    BOOTH_STAFFS {
        bigint booth_id PK
        bigint user_id PK
        varchar role
        timestamptz joined_at
        varchar consultation_status
    }
    STAFF_INVITATIONS {
        bigint id PK
        bigint booth_id FK
        bigint invited_user_id FK
        varchar role
        varchar status
        bigint invited_by_user_id FK
        timestamptz expires_at
        timestamptz accepted_at
        timestamptz created_at
    }
    BOOTH_VISIT_EVENTS {
        bigint id PK
        bigint booth_id FK
        bigint visitor_user_id FK
        varchar world_channel
        timestamptz entered_at
        timestamptz exited_at
    }
    BOOTH_DAILY_METRICS {
        bigint id PK
        bigint booth_id FK
        date metric_date
        int visit_count
        int ai_conversation_count
        int survey_response_count
        int consultation_count
        int project_like_count
        int revenue_coin
        timestamptz updated_at
    }
    PROJECTS {
        bigint id PK
        bigint booth_id FK
        varchar name
        text description
        varchar thumbnail_url
        varchar video_url
        varchar deploy_url
        varchar git_url
        varchar portfolio_url
        timestamptz created_at
        timestamptz updated_at
        text target_audience
        text tech_stack
        bigint facts_document_id FK
        bigint facts_job_id
        timestamptz facts_updated_at
    }
    PROJECT_LIKES {
        bigint project_id PK
        bigint user_id PK
        timestamptz created_at
    }
    PROJECT_LOGO_UPLOADS {
        bigint id PK
        bigint booth_id FK
        varchar logo_id
        varchar status
        varchar content_type
        bigint byte_size
        int width
        int height
        varchar sha256
        varchar declared_content_type
        bigint declared_byte_size
        varchar provider
        text storage_bucket
        text object_key
        varchar failure_rule
        timestamptz created_at
        timestamptz completed_at
        timestamptz expires_at
        timestamptz unreferenced_since
    }
    USERS ||--o{ BOOTHS : "owner_user_id"
    BOOTH_SLOTS |o--o{ BOOTHS : "current_slot_id"
    BOOTHS ||--o{ BOOTH_LEASES : "booth_id"
    BOOTH_SLOTS ||--o{ BOOTH_LEASES : "slot_id"
    USERS ||--o{ BOOTH_LEASES : "lessee_user_id"
    BOOTHS ||--o{ BOOTH_LAYOUT_DRAFTS : "booth_id"
    USERS ||--o{ BOOTH_LAYOUT_DRAFTS : "updated_by_user_id"
    BOOTHS ||--o{ BOOTH_LAYOUT_PUBLISHED_VERSIONS : "booth_id"
    USERS ||--o{ BOOTH_LAYOUT_PUBLISHED_VERSIONS : "published_by_user_id"
    BOOTHS ||--o{ BOOTH_STAFFS : "booth_id"
    USERS ||--o{ BOOTH_STAFFS : "user_id"
    BOOTHS ||--o{ STAFF_INVITATIONS : "booth_id"
    USERS ||--o{ STAFF_INVITATIONS : "invited_user_id"
    USERS ||--o{ STAFF_INVITATIONS : "invited_by_user_id"
    BOOTHS ||--o{ PROJECTS : "booth_id"
    PROJECTS ||--o{ PROJECT_LIKES : "project_id"
    USERS ||--o{ PROJECT_LIKES : "user_id"
    BOOTHS ||--o{ BOOTH_VISIT_EVENTS : "booth_id"
    USERS |o--o{ BOOTH_VISIT_EVENTS : "visitor_user_id"
    BOOTHS ||--o{ BOOTH_DAILY_METRICS : "booth_id"
    BOOTH_LAYOUT_PUBLISHED_VERSIONS ||--o{ BOOTHS : "id"
    AI_DOCUMENTS |o--o{ PROJECTS : "facts_document_id"
    BOOTHS ||--o{ PROJECT_LOGO_UPLOADS : "booth_id"
```

다른 영역 테이블은 이름만 있는 박스로 나온다.

### 2-4. AI·상담

`ai_agents` · `ai_documents` · `ai_document_jobs` · `ai_document_chunks` · `ai_document_chunk_staging` · `storage_reconciliation_log` · `consultations` · `consultation_messages`

```mermaid
erDiagram
    AI_AGENTS {
        bigint id PK
        bigint booth_id FK
        varchar name
        varchar role_code
        varchar tone_code
        text system_prompt
        varchar response_length
        int service_price
        boolean handoff_enabled
        jsonb forbidden_topics
        varchar status
        timestamptz created_at
        timestamptz updated_at
    }
    AI_DOCUMENTS {
        bigint id PK
        bigint booth_id FK
        bigint agent_id FK
        varchar original_filename
        varchar content_type
        bigint size_bytes
        varchar s3_key
        varchar processing_status
        bigint uploaded_by_user_id FK
        timestamptz created_at
        timestamptz updated_at
        varchar content_sha256
        varchar storage_provider
        varchar storage_bucket
        timestamptz uploaded_at
        timestamptz expired_at
        bigint replaces_document_id FK
        timestamptz replaced_at
    }
    AI_DOCUMENT_JOBS {
        bigint id PK
        bigint document_id FK
        bigint booth_id FK
        bigint agent_id FK
        varchar source_hash
        text original_filename
        varchar content_type
        bigint file_size_bytes
        varchar storage_provider
        text storage_bucket
        text object_key
        varchar status
        int attempt_no
        int max_retries
        varchar worker_id
        timestamptz lease_expires_at
        timestamptz next_retry_at
        varchar last_error_code
        text last_error
        int chunk_count
        timestamptz created_at
        timestamptz updated_at
        timestamptz finished_at
    }
    AI_DOCUMENT_CHUNKS {
        bigint id PK
        bigint document_id FK
        bigint booth_id FK
        bigint agent_id FK
        int chunk_no
        text content
        vector embedding
        text embedding_model_id
        timestamptz created_at
        bigint job_id
        int page_number
        text section
        boolean searchable
    }
    AI_DOCUMENT_CHUNK_STAGING {
        bigint job_id PK
        int batch_seq PK
        int chunk_no PK
        text content
        vector embedding
        text embedding_model_id
        int page_number
        text section
        timestamptz created_at
    }
    STORAGE_RECONCILIATION_LOG {
        bigint id PK
        varchar run_id
        bigint document_id FK
        varchar object_key
        varchar source_provider
        varchar target_provider
        varchar status
        varchar apply_result
        bigint expected_size
        bigint actual_size
        varchar expected_content_type
        varchar actual_content_type
        varchar expected_sha256
        varchar actual_sha256
        int attempt_count
        varchar failure_reason
        timestamptz checked_at
        timestamptz resolved_at
        timestamptz created_at
    }
    CONSULTATIONS {
        bigint id PK
        bigint booth_id FK
        bigint visitor_user_id FK
        bigint agent_id FK
        bigint staff_user_id FK
        varchar ai_conversation_id
        text summary
        varchar status
        timestamptz requested_at
        timestamptz accepted_at
        timestamptz ended_at
    }
    CONSULTATION_MESSAGES {
        bigint id PK
        bigint consultation_id FK
        bigint sender_user_id FK
        text content
        timestamptz created_at
    }
    BOOTHS ||--o{ AI_AGENTS : "booth_id"
    BOOTHS ||--o{ AI_DOCUMENTS : "booth_id"
    AI_AGENTS ||--o{ AI_DOCUMENTS : "agent_id"
    USERS ||--o{ AI_DOCUMENTS : "uploaded_by_user_id"
    BOOTHS ||--o{ AI_DOCUMENT_CHUNKS : "booth_id"
    AI_AGENTS ||--o{ AI_DOCUMENT_CHUNKS : "agent_id"
    BOOTHS ||--o{ CONSULTATIONS : "booth_id"
    USERS |o--o{ CONSULTATIONS : "visitor_user_id"
    AI_AGENTS |o--o{ CONSULTATIONS : "agent_id"
    USERS |o--o{ CONSULTATIONS : "staff_user_id"
    CONSULTATIONS ||--o{ CONSULTATION_MESSAGES : "consultation_id"
    USERS ||--o{ CONSULTATION_MESSAGES : "sender_user_id"
    AI_DOCUMENTS ||--o{ AI_DOCUMENT_JOBS : "document_id"
    BOOTHS ||--o{ AI_DOCUMENT_JOBS : "booth_id"
    AI_AGENTS ||--o{ AI_DOCUMENT_JOBS : "agent_id"
    AI_DOCUMENTS ||--o{ AI_DOCUMENT_CHUNKS : "document_id"
    AI_DOCUMENT_JOBS ||--o{ AI_DOCUMENT_CHUNK_STAGING : "job_id"
    AI_DOCUMENTS ||--o{ STORAGE_RECONCILIATION_LOG : "document_id"
    AI_DOCUMENTS |o--o{ AI_DOCUMENTS : "replaces_document_id"
```

다른 영역 테이블은 이름만 있는 박스로 나온다.

### 2-5. 설문

`surveys` · `survey_questions` · `survey_options` · `survey_responses` · `survey_answers` · `survey_answer_options`

```mermaid
erDiagram
    SURVEYS {
        bigint id PK
        bigint booth_id FK
        varchar title
        text description
        int reward_coin
        varchar status
        timestamptz starts_at
        timestamptz ends_at
        bigint created_by_user_id FK
        timestamptz created_at
        timestamptz updated_at
        varchar survey_key
    }
    SURVEY_QUESTIONS {
        bigint id PK
        bigint survey_id FK
        varchar question_type
        text question_text
        boolean is_required
        smallint display_order
        smallint rating_min
        smallint rating_max
    }
    SURVEY_OPTIONS {
        bigint id PK
        bigint question_id FK
        varchar option_text
        smallint display_order
    }
    SURVEY_RESPONSES {
        bigint id PK
        bigint survey_id FK
        bigint respondent_user_id FK
        bigint reward_ledger_entry_id FK
        timestamptz submitted_at
        varchar respondent_guest_key
        timestamptz respondent_session_expires_at
    }
    SURVEY_ANSWERS {
        bigint id PK
        bigint response_id FK
        bigint question_id FK
        text text_answer
        smallint rating_value
    }
    SURVEY_ANSWER_OPTIONS {
        bigint answer_id PK
        bigint option_id PK
    }
    BOOTHS |o--o{ SURVEYS : "booth_id"
    USERS |o--o{ SURVEYS : "created_by_user_id"
    SURVEYS ||--o{ SURVEY_QUESTIONS : "survey_id"
    SURVEY_QUESTIONS ||--o{ SURVEY_OPTIONS : "question_id"
    SURVEYS ||--o{ SURVEY_RESPONSES : "survey_id"
    USERS |o--o{ SURVEY_RESPONSES : "respondent_user_id"
    COIN_LEDGER_ENTRIES |o--o{ SURVEY_RESPONSES : "reward_ledger_entry_id"
    SURVEY_RESPONSES ||--o{ SURVEY_ANSWERS : "response_id"
    SURVEY_QUESTIONS ||--o{ SURVEY_ANSWERS : "question_id"
    SURVEY_ANSWERS ||--o{ SURVEY_ANSWER_OPTIONS : "answer_id"
    SURVEY_OPTIONS ||--o{ SURVEY_ANSWER_OPTIONS : "option_id"
```

다른 영역 테이블은 이름만 있는 박스로 나온다.

### 2-6. 게임·오락기

`games` · `game_drafts` · `game_published_versions` · `game_assets` · `game_asset_delete_queue` · `minigame_sessions` · `arcade_machine_bindings` · `arcade_score_records`

```mermaid
erDiagram
    GAMES {
        bigint id PK
        bigint owner_user_id FK
        varchar title
        varchar visibility
        int published_version
        timestamptz deleted_at
        timestamptz created_at
        timestamptz updated_at
    }
    GAME_DRAFTS {
        bigint game_id PK
        varchar schema_version
        int revision
        jsonb project_json
        bigint updated_by_user_id FK
        timestamptz updated_at
    }
    GAME_PUBLISHED_VERSIONS {
        bigint id PK
        bigint game_id FK
        int version_no
        varchar schema_version
        jsonb project_json
        bigint published_by_user_id FK
        timestamptz published_at
    }
    GAME_ASSETS {
        bigint id PK
        bigint game_id FK
        varchar asset_id
        varchar kind
        varchar status
        varchar content_type
        bigint byte_size
        int width
        int height
        varchar sha256
        varchar provider
        text storage_bucket
        text object_key
        varchar failure_rule
        varchar declared_content_type
        bigint declared_byte_size
        timestamptz upload_expires_at
        bigint created_by_user_id FK
        timestamptz created_at
        timestamptz updated_at
        timestamptz deleted_at
    }
    GAME_ASSET_DELETE_QUEUE {
        bigint id PK
        varchar provider
        text storage_bucket
        text object_key
        int attempts
        varchar last_error
        timestamptz created_at
        timestamptz next_attempt_at
    }
    MINIGAME_SESSIONS {
        bigint id PK
        bigint user_id FK
        varchar game_type
        uuid nonce
        varchar status
        numeric target_value
        numeric result_value
        int reward_coin
        bigint reward_ledger_entry_id FK
        timestamptz started_at
        timestamptz completed_at
    }
    ARCADE_MACHINE_BINDINGS {
        varchar machine_id PK
        bigint game_id FK
        timestamptz created_at
        timestamptz updated_at
        bigint owner_user_id FK
    }
    ARCADE_SCORE_RECORDS {
        varchar machine_id PK
        bigint user_id PK
        int best_score
        timestamptz achieved_at
    }
    USERS ||--o{ MINIGAME_SESSIONS : "user_id"
    COIN_LEDGER_ENTRIES |o--o{ MINIGAME_SESSIONS : "reward_ledger_entry_id"
    USERS ||--o{ GAMES : "owner_user_id"
    GAMES ||--o{ GAME_DRAFTS : "game_id"
    USERS ||--o{ GAME_DRAFTS : "updated_by_user_id"
    GAMES ||--o{ GAME_PUBLISHED_VERSIONS : "game_id"
    USERS ||--o{ GAME_PUBLISHED_VERSIONS : "published_by_user_id"
    GAME_PUBLISHED_VERSIONS ||--o{ GAMES : "id"
    GAMES ||--o{ GAME_ASSETS : "game_id"
    USERS ||--o{ GAME_ASSETS : "created_by_user_id"
    GAMES ||--o{ ARCADE_MACHINE_BINDINGS : "game_id"
    USERS |o--o{ ARCADE_MACHINE_BINDINGS : "owner_user_id"
    USERS ||--o{ ARCADE_SCORE_RECORDS : "user_id"
```

다른 영역 테이블은 이름만 있는 박스로 나온다.

## 3. 유일성 제약

중복 지급·중복 임대·중복 응답을 DB 가 직접 막는 인덱스다. `WHERE` 가 붙은 것은 부분 유일 인덱스다.

| 테이블 | 인덱스 |
|---|---|
| `ai_agents` | `ux_ai_agents_booth (booth_id)` |
| `ai_document_chunks` | `ai_document_chunks_document_id_chunk_no_key (document_id, chunk_no)` |
| `ai_document_jobs` | `ix_ai_document_jobs_active_document (document_id) WHERE ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying, 'RETRY_WAIT'::character varying])::text[]))` |
| `ai_documents` | `ai_documents_s3_key_key (s3_key)` |
| `ai_documents` | `ux_ai_documents_agent_active_sha (agent_id, content_sha256) WHERE ((processing_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'READY'::character varying])::text[]))` |
| `ai_documents` | `ux_ai_documents_active_replacement (replaces_document_id) WHERE ((replaces_document_id IS NOT NULL) AND ((processing_status)::text = ANY ((ARRAY['QUEUED'::character varying, 'PROCESSING'::character varying, 'READY'::character varying])::text[])))` |
| `booth_daily_metrics` | `booth_daily_metrics_booth_id_metric_date_key (booth_id, metric_date)` |
| `booth_layout_published_versions` | `booth_layout_published_versions_booth_id_version_no_key (booth_id, version_no)` |
| `booth_leases` | `ux_booth_leases_active_slot (slot_id) WHERE ((status)::text = 'ACTIVE'::text)` |
| `booth_leases` | `ux_booth_leases_active_lessee (lessee_user_id) WHERE (((status)::text = 'ACTIVE'::text) AND (NOT permanent))` |
| `booth_slots` | `booth_slots_slot_code_key (slot_code)` |
| `booths` | `booths_current_slot_id_key (current_slot_id)` |
| `booths` | `ux_booths_owner (owner_user_id) WHERE (NOT admin_owned)` |
| `catalog_items` | `catalog_items_item_code_key (item_code)` |
| `catalog_items` | `ux_catalog_items_type_asset_key (item_type, asset_key)` |
| `coin_ledger_entries` | `coin_ledger_entries_idempotency_key_key (idempotency_key)` |
| `consultations` | `ux_consultations_active_staff (staff_user_id) WHERE ((status)::text = 'ACCEPTED'::text)` |
| `consultations` | `ux_consultations_pending_visitor (booth_id, visitor_user_id) WHERE ((status)::text = 'REQUESTED'::text)` |
| `event_purchases` | `ux_event_purchases_idempotency_key (idempotency_key)` |
| `game_asset_delete_queue` | `game_asset_delete_queue_provider_storage_bucket_object_key_key (provider, storage_bucket, object_key)` |
| `game_assets` | `game_assets_game_id_asset_id_key (game_id, asset_id)` |
| `game_published_versions` | `game_published_versions_game_id_version_no_key (game_id, version_no)` |
| `minigame_sessions` | `minigame_sessions_nonce_key (nonce)` |
| `minigame_sessions` | `minigame_sessions_reward_ledger_entry_id_key (reward_ledger_entry_id)` |
| `oauth_identities` | `oauth_identities_provider_provider_subject_key (provider, provider_subject)` |
| `oauth_identities` | `oauth_identities_user_id_provider_key (user_id, provider)` |
| `project_logo_uploads` | `ux_project_logo_uploads_logo_id (logo_id)` |
| `projects` | `ux_projects_booth (booth_id)` |
| `staff_invitations` | `ux_staff_invitations_pending (booth_id, invited_user_id) WHERE ((status)::text = 'PENDING'::text)` |
| `storage_reconciliation_log` | `ux_storage_reconciliation_log_run_document (run_id, document_id)` |
| `survey_answers` | `survey_answers_response_id_question_id_key (response_id, question_id)` |
| `survey_options` | `survey_options_question_id_display_order_key (question_id, display_order)` |
| `survey_questions` | `survey_questions_survey_id_display_order_key (survey_id, display_order)` |
| `survey_responses` | `survey_responses_reward_ledger_entry_id_key (reward_ledger_entry_id)` |
| `survey_responses` | `ux_survey_responses_member (survey_id, respondent_user_id) WHERE (respondent_user_id IS NOT NULL)` |
| `survey_responses` | `ux_survey_responses_guest (survey_id, respondent_guest_key) WHERE (respondent_guest_key IS NOT NULL)` |
| `surveys` | `ux_surveys_key (survey_key) WHERE (survey_key IS NOT NULL)` |
| `surveys` | `ux_surveys_booth (booth_id)` |
| `user_inventory_items` | `user_inventory_items_user_id_catalog_item_id_key (user_id, catalog_item_id)` |
| `users` | `users_nickname_key (nickname)` |
| `users` | `ux_users_single_master (is_master) WHERE is_master` |
| `wallets` | `wallets_user_id_key (user_id)` |
