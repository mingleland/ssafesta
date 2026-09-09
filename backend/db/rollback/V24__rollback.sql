-- V24__ai_documents_processing_status_check.sql 되돌리기.
--
-- 잃는 것: processing_status 오타에 대한 유일한 방어선. 되돌린 뒤에는 'REDAY' 같은 값이
-- 그대로 저장되고, 그 문서는 중복 판정·쿼터 집계·검색 게이트에서 조용히 빠진다 (V24 주석).
--
-- 데이터는 건드리지 않는다. 제약만 사라진다.
ALTER TABLE ai_documents DROP CONSTRAINT IF EXISTS ck_ai_documents_processing_status;
