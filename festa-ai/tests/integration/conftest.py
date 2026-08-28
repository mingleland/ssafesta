"""Fixtures for tests that need a real local PostgreSQL + pgvector instance.

These tests are opt-in: they run only when `TEST_MIGRATION_DATABASE_URL`
points at a disposable database (spec 007 quickstart.md Prerequisites).
Nothing here touches Business DB or any shared/staging database — point it
at a throwaway local database only.
"""

from __future__ import annotations

import asyncio
import os
import pathlib
import sys

import pytest
import sqlalchemy as sa
from alembic import command
from alembic.config import Config

FESTA_AI_ROOT = pathlib.Path(__file__).resolve().parents[2]

TEST_DATABASE_URL = os.getenv("TEST_MIGRATION_DATABASE_URL")

pytestmark = pytest.mark.skipif(
    not TEST_DATABASE_URL,
    reason="TEST_MIGRATION_DATABASE_URL not set — skipping tests that need a real Postgres instance",
)


@pytest.fixture(scope="session")
def event_loop_policy():
    """Use the Windows loop implementation supported by psycopg async."""
    if sys.platform == "win32":
        return asyncio.WindowsSelectorEventLoopPolicy()
    return asyncio.DefaultEventLoopPolicy()


@pytest.fixture(scope="session")
def migrated_engine() -> sa.Engine:
    alembic_cfg = Config(str(FESTA_AI_ROOT / "alembic.ini"))
    alembic_cfg.set_main_option("script_location", str(FESTA_AI_ROOT / "migrations"))
    os.environ["MIGRATION_DATABASE_URL"] = TEST_DATABASE_URL  # read by migrations/env.py
    command.upgrade(alembic_cfg, "head")

    engine = sa.create_engine(TEST_DATABASE_URL)
    yield engine
    engine.dispose()


@pytest.fixture
def db_connection(migrated_engine: sa.Engine):
    """A connection wrapped in a transaction that is always rolled back."""
    connection = migrated_engine.connect()
    transaction = connection.begin()
    try:
        yield connection
    finally:
        transaction.rollback()
        connection.close()
