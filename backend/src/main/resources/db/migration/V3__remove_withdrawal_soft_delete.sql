DROP INDEX IF EXISTS ix_users_deleted_at;
ALTER TABLE users DROP COLUMN IF EXISTS deleted_at;
