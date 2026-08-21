# Data Model: ERD 초기 스키마 적용

## 도메인 묶음

| 묶음 | 엔티티 |
|---|---|
| 인증·경제 | users, oauth_identities, wallets, coin_ledger_entries |
| 부스 | booth_slots, booths, booth_leases, booth_layout_drafts, booth_layout_published_versions |
| AI | ai_agents, ai_documents, ai_document_chunks |
| 협업·상담 | booth_staffs, staff_invitations, consultations, consultation_messages |
| 전시·설문 | projects, project_likes, surveys, survey_questions, survey_options, survey_responses, survey_answers, survey_answer_options |
| 아이템·게임·통계 | catalog_items, user_inventory_items, minigame_sessions, booth_visit_events, booth_daily_metrics |

## 핵심 무결성

- OAuth: `(provider, provider_subject)`, `(user_id, provider)` 유니크.
- 지갑: 사용자당 하나.
- 설문: `(survey_id, respondent_user_id)` 유니크.
- RAG: `(document_id, chunk_no)` 유니크, 검색 범위는 booth와 agent로 필터.
- 활성 임대·대기 초대는 Partial Unique Index.
- 게스트 방문은 `booth_visit_events.visitor_user_id` NULL로 기록한다.
