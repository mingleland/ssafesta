-- dev/demo의 business·AI 데이터베이스와 최소 권한 로그인 역할을 멱등 생성한다.
\set ON_ERROR_STOP on

SELECT 'CREATE ROLE festa_dev_back_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'festa_dev_back_app')\gexec
SELECT 'CREATE ROLE festa_dev_ai_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'festa_dev_ai_app')\gexec
SELECT 'CREATE ROLE festa_demo_back_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'festa_demo_back_app')\gexec
SELECT 'CREATE ROLE festa_demo_ai_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'festa_demo_ai_app')\gexec

-- 조회 전용 운영 역할. demo 데이터 확인 요청을 호스트 권한 없이 처리하려고 둔다. 런타임 서비스는
-- 쓰지 않는다. 비밀번호를 주지 않으므로 scram-sha-256 을 쓰는 host 경로로는 로그인할 수 없고,
-- 컨테이너 안 unix socket 으로만 붙는다. 네트워크 접속이 필요해지면 그때 secret 을 추가한다.
SELECT 'CREATE ROLE festa_demo_readonly LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'festa_demo_readonly')\gexec

DO $$
DECLARE
  item record;
  role_auth text;
BEGIN
  FOR item IN
    SELECT * FROM (VALUES
      ('festa_dev_back_app', '/run/secrets/postgres_dev_back_password'),
      ('festa_dev_ai_app', '/run/secrets/postgres_dev_ai_password'),
      ('festa_demo_back_app', '/run/secrets/postgres_demo_back_password'),
      ('festa_demo_ai_app', '/run/secrets/postgres_demo_ai_password')
    ) AS credentials(role_name, file_path)
  LOOP
    role_auth := btrim(pg_read_file(item.file_path));
    IF role_auth = '' THEN
      RAISE EXCEPTION 'empty PostgreSQL credential file for role %', item.role_name;
    END IF;
    EXECUTE format('ALTER ROLE %I PASSWORD %L', item.role_name, role_auth);
  END LOOP;
END
$$;

SELECT 'CREATE DATABASE festa_dev_business OWNER festa_dev_back_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'festa_dev_business')\gexec
SELECT 'CREATE DATABASE festa_dev_ai OWNER festa_dev_ai_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'festa_dev_ai')\gexec
SELECT 'CREATE DATABASE festa_demo_business OWNER festa_demo_back_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'festa_demo_business')\gexec
SELECT 'CREATE DATABASE festa_demo_ai OWNER festa_demo_ai_app'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'festa_demo_ai')\gexec

REVOKE CONNECT ON DATABASE festa_dev_business, festa_dev_ai, festa_demo_business, festa_demo_ai FROM PUBLIC;
GRANT CONNECT ON DATABASE festa_dev_business TO festa_dev_back_app;
GRANT CONNECT ON DATABASE festa_dev_ai TO festa_dev_ai_app;
GRANT CONNECT ON DATABASE festa_demo_business TO festa_demo_back_app;
GRANT CONNECT ON DATABASE festa_demo_ai TO festa_demo_ai_app;
GRANT CONNECT ON DATABASE festa_demo_business TO festa_demo_readonly;
GRANT CONNECT ON DATABASE festa_demo_ai TO festa_demo_readonly;

\connect festa_dev_business
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_dev_back_app;
CREATE EXTENSION IF NOT EXISTS vector;

\connect festa_dev_ai
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_dev_ai_app;
CREATE EXTENSION IF NOT EXISTS vector;

\connect festa_demo_business
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_demo_back_app;
GRANT USAGE ON SCHEMA public TO festa_demo_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO festa_demo_readonly;
-- Flyway 가 테이블을 festa_demo_back_app 소유로 만든다. default privileges 가 없으면 마이그레이션이
-- 돌 때마다 새로 생긴 테이블이 조회 역할에 보이지 않는다.
ALTER DEFAULT PRIVILEGES FOR ROLE festa_demo_back_app IN SCHEMA public
  GRANT SELECT ON TABLES TO festa_demo_readonly;
CREATE EXTENSION IF NOT EXISTS vector;

\connect festa_demo_ai
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_demo_ai_app;
GRANT USAGE ON SCHEMA public TO festa_demo_readonly;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO festa_demo_readonly;
ALTER DEFAULT PRIVILEGES FOR ROLE festa_demo_ai_app IN SCHEMA public
  GRANT SELECT ON TABLES TO festa_demo_readonly;
CREATE EXTENSION IF NOT EXISTS vector;
