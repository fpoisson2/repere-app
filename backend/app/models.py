from datetime import date, datetime
from sqlalchemy import Boolean, Date, DateTime, Float, ForeignKey, Integer, JSON, String, Text, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column, relationship
from .db import Base

class User(Base):
    __tablename__ = "users"
    id: Mapped[int] = mapped_column(primary_key=True)
    username: Mapped[str] = mapped_column(String(80), unique=True, index=True)
    password_hash: Mapped[str]
    tracking_start_date: Mapped[date | None] = mapped_column(Date)
    tracking_start_explicit: Mapped[bool] = mapped_column(Boolean, default=False)
    weight_kg: Mapped[float] = mapped_column(Float, default=75)
    distribution_ratio: Mapped[float] = mapped_column(Float, default=.68)
    elimination_rate: Mapped[float] = mapped_column(Float, default=.015)
    session_gap_hours: Mapped[float] = mapped_column(Float, default=4)
    day_start_hour: Mapped[int] = mapped_column(Integer, default=8)
    session_version: Mapped[int] = mapped_column(Integer, default=1)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class ImportBatch(Base):
    __tablename__ = "import_batches"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    filename: Mapped[str]
    source_type: Mapped[str] = mapped_column(default="csv")
    imported_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    rows_detected: Mapped[int] = mapped_column(default=0)
    rows_imported: Mapped[int] = mapped_column(default=0)
    rows_skipped: Mapped[int] = mapped_column(default=0)
    rows_failed: Mapped[int] = mapped_column(default=0)

class Drink(Base):
    __tablename__ = "drinks"
    __table_args__ = (UniqueConstraint("user_id", "dedupe_key", name="uq_drink_dedupe"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    drink_type: Mapped[str | None] = mapped_column(String(80))
    drink_name: Mapped[str]
    volume_ml: Mapped[float]
    abv_percent: Mapped[float]
    quantity: Mapped[int] = mapped_column(default=1)
    started_at: Mapped[datetime] = mapped_column(DateTime, index=True)
    ended_at: Mapped[datetime]
    duration_minutes: Mapped[int] = mapped_column(default=30)
    notes: Mapped[str | None] = mapped_column(Text)
    cost: Mapped[float | None]
    source_icon: Mapped[str | None]
    import_source: Mapped[str | None]
    external_id: Mapped[str | None]
    import_batch_id: Mapped[int | None] = mapped_column(ForeignKey("import_batches.id", ondelete="CASCADE"))
    dedupe_key: Mapped[str]
    alcohol_grams: Mapped[float]
    canadian_standard_drinks: Mapped[float]
    manual_session_id: Mapped[str | None] = mapped_column(String(36), index=True)
    is_active: Mapped[bool] = mapped_column(Boolean, default=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class WearPairingCode(Base):
    __tablename__ = "wear_pairing_codes"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    used_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class WearToken(Base):
    __tablename__ = "wear_tokens"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    device_name: Mapped[str] = mapped_column(String(120), default="Wear OS")
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class AiInsight(Base):
    __tablename__ = "ai_insights"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    provider: Mapped[str] = mapped_column(String(40), default="openai")
    model: Mapped[str] = mapped_column(String(80))
    result: Mapped[dict] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

class Preset(Base):
    __tablename__ = "presets"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int | None] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"))
    name: Mapped[str]
    drink_type: Mapped[str]
    volume_ml: Mapped[float]
    abv_percent: Mapped[float]
    icon: Mapped[str] = mapped_column(default="glass")

class Goal(Base):
    __tablename__ = "goals"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    kind: Mapped[str]
    target: Mapped[float]
    active: Mapped[bool] = mapped_column(default=True)
    temporal_mode: Mapped[str] = mapped_column(String(24), default="consecutive_weeks")
    consecutive_weeks: Mapped[int | None] = mapped_column(Integer)
    due_date: Mapped[date | None] = mapped_column(Date)
    started_on: Mapped[date | None] = mapped_column(Date, default=date.today)

class Journal(Base):
    __tablename__ = "journal"
    __table_args__ = (UniqueConstraint("user_id", "day"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    day: Mapped[date] = mapped_column(Date, index=True)
    mood: Mapped[int | None]
    stress: Mapped[int | None]
    fatigue: Mapped[int | None]
    craving: Mapped[int | None]
    notes: Mapped[str | None] = mapped_column(Text)
    tags: Mapped[list] = mapped_column(JSON, default=list)

class TrackedDay(Base):
    __tablename__ = "tracked_days"
    __table_args__ = (UniqueConstraint("user_id", "day"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    day: Mapped[date] = mapped_column(Date, index=True)
    sober: Mapped[bool] = mapped_column(Boolean, default=True)
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
