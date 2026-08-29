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
    height_cm: Mapped[float | None] = mapped_column(Float)
    sex: Mapped[str] = mapped_column(String(12), default="unspecified")
    distribution_ratio: Mapped[float] = mapped_column(Float, default=.68)
    elimination_rate: Mapped[float] = mapped_column(Float, default=.015)
    session_gap_hours: Mapped[float] = mapped_column(Float, default=4)
    day_start_hour: Mapped[int] = mapped_column(Integer, default=8)
    standard_drink_grams: Mapped[float] = mapped_column(Float, default=13.45)
    volume_unit: Mapped[str] = mapped_column(String(8), default="ml")
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
    started_at_utc: Mapped[datetime | None] = mapped_column(DateTime, index=True)
    ended_at_utc: Mapped[datetime | None] = mapped_column(DateTime)
    local_date: Mapped[date | None] = mapped_column(Date, index=True)
    timezone_id: Mapped[str | None] = mapped_column(String(64))
    utc_offset_minutes: Mapped[int | None] = mapped_column(Integer)
    display_quantity: Mapped[float | None] = mapped_column(Float)
    display_unit: Mapped[str | None] = mapped_column(String(24))
    planned_grams_snapshot: Mapped[float | None] = mapped_column(Float)
    timezone_assumption: Mapped[str | None] = mapped_column(String(32))

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

class OAuthAuthCode(Base):
    __tablename__ = "oauth_auth_codes"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    client_id: Mapped[str] = mapped_column(String(64))
    redirect_uri: Mapped[str] = mapped_column(String(255))
    code_challenge: Mapped[str] = mapped_column(String(128))
    scope: Mapped[str] = mapped_column(String(255), default="")
    expires_at: Mapped[datetime] = mapped_column(DateTime)
    used_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class OAuthToken(Base):
    __tablename__ = "oauth_tokens"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    access_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    refresh_hash: Mapped[str | None] = mapped_column(String(64), unique=True, index=True)
    client_id: Mapped[str] = mapped_column(String(64))
    device_name: Mapped[str] = mapped_column(String(120), default="Application")
    scope: Mapped[str] = mapped_column(String(255), default="")
    access_expires_at: Mapped[datetime] = mapped_column(DateTime)
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime)
    revoked_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class SyncEvent(Base):
    __tablename__ = "sync_events"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    entity_type: Mapped[str] = mapped_column(String(32), index=True)
    entity_id: Mapped[int] = mapped_column(Integer)
    operation: Mapped[str] = mapped_column(String(12))
    payload: Mapped[dict | None] = mapped_column(JSON)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)

class SyncMutation(Base):
    __tablename__ = "sync_mutations"
    __table_args__ = (UniqueConstraint("user_id", "mutation_id", name="uq_sync_mutation_user_id"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    mutation_id: Mapped[str] = mapped_column(String(64))
    result: Mapped[dict] = mapped_column(JSON)
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

class GoalAchievement(Base):
    __tablename__ = "goal_achievements"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    goal_id: Mapped[int | None] = mapped_column(ForeignKey("goals.id", ondelete="SET NULL"), unique=True, index=True)
    goal_kind: Mapped[str] = mapped_column(String(48))
    target_snapshot: Mapped[float]
    temporal_mode: Mapped[str] = mapped_column(String(24))
    achieved_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow, index=True)
    evidence: Mapped[dict] = mapped_column(JSON, default=dict)

class TrackedDay(Base):
    __tablename__ = "tracked_days"
    __table_args__ = (UniqueConstraint("user_id", "day"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    day: Mapped[date] = mapped_column(Date, index=True)
    sober: Mapped[bool] = mapped_column(Boolean, default=True)
    notes: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class DailyPlan(Base):
    __tablename__ = "daily_plans"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    planned_grams: Mapped[float] = mapped_column(Float)
    display_quantity: Mapped[float | None] = mapped_column(Float)
    display_unit: Mapped[str | None] = mapped_column(String(24))
    created_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)
    timezone_id: Mapped[str] = mapped_column(String(64), default="UTC")
    supersedes_id: Mapped[int | None] = mapped_column(ForeignKey("daily_plans.id"))

class EmaCheckIn(Base):
    __tablename__ = "ema_check_ins"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    observed_at_utc: Mapped[datetime] = mapped_column(DateTime, index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    timezone_id: Mapped[str] = mapped_column(String(64))
    phase: Mapped[str] = mapped_column(String(24), default="pre_drinking")
    craving: Mapped[int]
    confidence: Mapped[int | None]
    stress: Mapped[int | None]
    positive_affect: Mapped[int | None]
    negative_affect: Mapped[int | None]
    fatigue: Mapped[int | None]
    notes: Mapped[str | None] = mapped_column(Text)
    post_onset: Mapped[bool | None] = mapped_column(Boolean)
    source: Mapped[str] = mapped_column(String(24), default="web")
    schema_version: Mapped[str] = mapped_column(String(24), default="ema-v1")
    created_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class ContextObservation(Base):
    __tablename__ = "context_observations"
    id: Mapped[int] = mapped_column(primary_key=True)
    check_in_id: Mapped[str] = mapped_column(ForeignKey("ema_check_ins.id", ondelete="CASCADE"), unique=True, index=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    observed_at_utc: Mapped[datetime]
    social_context: Mapped[str] = mapped_column(String(32))
    others_drinking: Mapped[str] = mapped_column(String(12))
    alcohol_available: Mapped[bool]
    event_type: Mapped[str | None] = mapped_column(String(80))

class HealthDailyAggregate(Base):
    __tablename__ = "health_daily_aggregates"
    __table_args__ = (UniqueConstraint("user_id", "local_date", "record_type", "origin_package", name="uq_health_daily_origin"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    record_type: Mapped[str] = mapped_column(String(48))
    value: Mapped[float | None] = mapped_column(Float)
    unit: Mapped[str] = mapped_column(String(24))
    window_start_utc: Mapped[datetime]
    window_end_utc: Mapped[datetime]
    origin_package: Mapped[str] = mapped_column(String(160))
    origin_device: Mapped[str | None] = mapped_column(String(160))
    aggregation_method: Mapped[str] = mapped_column(String(32))
    imported_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class HealthDataQuality(Base):
    __tablename__ = "health_data_quality"
    __table_args__ = (UniqueConstraint("user_id", "local_date", "record_type", name="uq_health_quality_day"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    record_type: Mapped[str] = mapped_column(String(48))
    coverage_ratio: Mapped[float | None] = mapped_column(Float)
    sample_count: Mapped[int] = mapped_column(default=0)
    expected_window_minutes: Mapped[int | None]
    observed_minutes: Mapped[int | None]
    quality_flags: Mapped[list] = mapped_column(JSON, default=list)

class DerivedDailyFeature(Base):
    __tablename__ = "derived_daily_features"
    __table_args__ = (UniqueConstraint("user_id", "local_date", "cutoff_at_utc", "feature_definition_version", name="uq_derived_feature_version"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    cutoff_at_utc: Mapped[datetime] = mapped_column(DateTime, index=True)
    feature_definition_version: Mapped[str] = mapped_column(String(32))
    values: Mapped[dict] = mapped_column(JSON)
    source_hash: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(16), default="final")
    computed_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class DrinkingEpisode(Base):
    __tablename__ = "drinking_episodes"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    started_at_utc: Mapped[datetime] = mapped_column(DateTime, index=True)
    ended_at_utc: Mapped[datetime]
    amplitude_grams: Mapped[float]
    duration_minutes: Mapped[int]
    baseline_grams: Mapped[float | None]
    cumulative_excess_grams: Mapped[float]
    definition_version: Mapped[str] = mapped_column(String(32))

class RecoveryEpisode(Base):
    __tablename__ = "recovery_episodes"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    drinking_episode_id: Mapped[int] = mapped_column(ForeignKey("drinking_episodes.id", ondelete="CASCADE"), unique=True)
    recovered_at_utc: Mapped[datetime | None]
    recovery_days: Mapped[float | None]
    status: Mapped[str] = mapped_column(String(20))
    definition_version: Mapped[str] = mapped_column(String(32))

class ModelVersion(Base):
    __tablename__ = "model_versions"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    outcome_kind: Mapped[str] = mapped_column(String(48))
    model_kind: Mapped[str] = mapped_column(String(48))
    feature_definition_version: Mapped[str] = mapped_column(String(32))
    threshold: Mapped[float | None] = mapped_column(Float)
    calibration_start: Mapped[date | None]
    calibration_end: Mapped[date | None]
    holdout_start: Mapped[date | None]
    holdout_end: Mapped[date | None]
    holdout_frozen: Mapped[bool] = mapped_column(Boolean, default=True)
    artifact: Mapped[dict] = mapped_column(JSON, default=dict)
    metrics: Mapped[dict] = mapped_column(JSON, default=dict)
    created_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class Prediction(Base):
    __tablename__ = "predictions"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    model_version_id: Mapped[int | None] = mapped_column(ForeignKey("model_versions.id"))
    target_local_date: Mapped[date] = mapped_column(Date, index=True)
    predicted_at_utc: Mapped[datetime] = mapped_column(DateTime, index=True)
    cutoff_at_utc: Mapped[datetime]
    outcome_kind: Mapped[str] = mapped_column(String(48))
    probability: Mapped[float | None] = mapped_column(Float)
    predicted_value: Mapped[float | None] = mapped_column(Float)
    explanation: Mapped[dict] = mapped_column(JSON, default=dict)

class InterventionDecision(Base):
    __tablename__ = "intervention_decisions"
    id: Mapped[str] = mapped_column(String(36), primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    check_in_id: Mapped[str | None] = mapped_column(ForeignKey("ema_check_ins.id", ondelete="SET NULL"))
    decided_at_utc: Mapped[datetime] = mapped_column(DateTime, index=True)
    decision: Mapped[str] = mapped_column(String(32))
    rule_id: Mapped[str | None] = mapped_column(String(64))
    rule_version: Mapped[str] = mapped_column(String(24))
    explanation: Mapped[dict] = mapped_column(JSON, default=dict)

class InterventionExposure(Base):
    __tablename__ = "intervention_exposures"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    decision_id: Mapped[str] = mapped_column(ForeignKey("intervention_decisions.id", ondelete="CASCADE"), index=True)
    exposed_at_utc: Mapped[datetime | None]
    response: Mapped[str | None] = mapped_column(String(32))

class Outcome(Base):
    __tablename__ = "outcomes"
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    local_date: Mapped[date] = mapped_column(Date, index=True)
    kind: Mapped[str] = mapped_column(String(48))
    value: Mapped[float | None] = mapped_column(Float)
    observed: Mapped[bool] = mapped_column(Boolean, default=True)
    definition_version: Mapped[str] = mapped_column(String(32))

class ConsentAndPermissionState(Base):
    __tablename__ = "consent_permission_states"
    __table_args__ = (UniqueConstraint("user_id", "permission_type", name="uq_consent_permission"),)
    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), index=True)
    permission_type: Mapped[str] = mapped_column(String(64))
    status: Mapped[str] = mapped_column(String(24))
    history_allowed: Mapped[bool] = mapped_column(Boolean, default=False)
    background_allowed: Mapped[bool] = mapped_column(Boolean, default=False)
    consent_version: Mapped[str] = mapped_column(String(24), default="v1")
    decided_at_utc: Mapped[datetime] = mapped_column(DateTime, default=datetime.utcnow)

class JitaiConfig(Base):
    __tablename__ = "jitai_configs"
    user_id: Mapped[int] = mapped_column(ForeignKey("users.id", ondelete="CASCADE"), primary_key=True)
    enabled: Mapped[bool] = mapped_column(Boolean, default=False)
    craving_threshold: Mapped[int] = mapped_column(default=7)
    confidence_threshold: Mapped[int] = mapped_column(default=4)
    max_notifications_per_week: Mapped[int] = mapped_column(default=3)
    cooldown_hours: Mapped[int] = mapped_column(default=24)
    recovery_rule_enabled: Mapped[bool] = mapped_column(Boolean, default=True)
