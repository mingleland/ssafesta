"""Declarative base for AI DB models (spec 007 data-model.md).

All tables here live in the AI database (`festa_{env}_ai`), a database
separate from Spring's Business DB (S15P21A604-262). No table in this
module may declare a ForeignKey into a Business DB table (e.g.
`ai_documents`) — cross-database FKs are not possible in PostgreSQL and
are explicitly excluded by the boundary contract.
"""

from __future__ import annotations

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass
