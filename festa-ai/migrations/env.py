from logging.config import fileConfig
import os

from alembic import context
from sqlalchemy import engine_from_config, pool

from app.db.base import Base
from app.db.models import DocumentChunk, DocumentJob  # noqa: F401 — populate Base.metadata

config = context.config

if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# Tables live directly in the AI database (`festa_{env}_ai`), not in a
# shared-schema namespace (S15P21A604-262 replaced the earlier single-DB
# `ai` schema design with a separate database — Infra owns creating that
# database and its `vector` extension; this migration only manages tables).
target_metadata = Base.metadata

database_url = os.getenv("MIGRATION_DATABASE_URL") or os.getenv("DATABASE_URL")
if database_url:
    config.set_main_option("sqlalchemy.url", database_url)


def run_migrations_offline() -> None:
    url = config.get_main_option("sqlalchemy.url")
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
    )

    with context.begin_transaction():
        context.run_migrations()


def run_migrations_online() -> None:
    connectable = engine_from_config(
        config.get_section(config.config_ini_section, {}),
        prefix="sqlalchemy.",
        poolclass=pool.NullPool,
    )

    with connectable.connect() as connection:
        context.configure(
            connection=connection,
            target_metadata=target_metadata,
        )

        with context.begin_transaction():
            context.run_migrations()


if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
