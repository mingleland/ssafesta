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

\connect festa_dev_business
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_dev_back_app;

\connect festa_dev_ai
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_dev_ai_app;
CREATE EXTENSION IF NOT EXISTS vector;

\connect festa_demo_business
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_demo_back_app;

\connect festa_demo_ai
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO festa_demo_ai_app;
CREATE EXTENSION IF NOT EXISTS vector;
