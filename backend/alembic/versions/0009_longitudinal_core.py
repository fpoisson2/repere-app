"""longitudinal personal analysis core

Revision ID: 0009
Revises: 0008
"""
from alembic import op
import sqlalchemy as sa
from datetime import datetime, timedelta
import uuid

revision="0009"; down_revision="0008"; branch_labels=None; depends_on=None

def upgrade():
    for name, kind in (
        ("started_at_utc", sa.DateTime()), ("ended_at_utc", sa.DateTime()),
        ("local_date", sa.Date()), ("timezone_id", sa.String(64)),
        ("utc_offset_minutes", sa.Integer()), ("display_quantity", sa.Float()),
        ("display_unit", sa.String(24)), ("planned_grams_snapshot", sa.Float()),
        ("timezone_assumption", sa.String(32)),
    ): op.add_column("drinks", sa.Column(name, kind, nullable=True))
    op.create_index("ix_drinks_started_at_utc", "drinks", ["started_at_utc"])
    op.create_index("ix_drinks_local_date", "drinks", ["local_date"])
    # Keep migration definitions aligned with SQLAlchemy metadata. This additive
    # migration intentionally leaves journal/drinks available to legacy clients.
    from app.db import Base
    bind=op.get_bind()
    existing=set(sa.inspect(bind).get_table_names())
    for table in Base.metadata.sorted_tables:
        if table.name not in existing: table.create(bind=bind, checkfirst=True)
    users={row.id:row.day_start_hour for row in bind.execute(sa.text("SELECT id, day_start_hour FROM users"))}
    for row in bind.execute(sa.text("SELECT id, user_id, started_at, ended_at FROM drinks")):
        started=datetime.fromisoformat(str(row.started_at));ended=datetime.fromisoformat(str(row.ended_at))
        local_day=(started-timedelta(hours=users.get(row.user_id,8))).date()
        bind.execute(sa.text("UPDATE drinks SET started_at_utc=:started, ended_at_utc=:ended, local_date=:day, timezone_assumption='legacy_local_time' WHERE id=:id"),
          {"started":started,"ended":ended,"day":local_day,"id":row.id})
    # Historical journal entries remain explicitly retrospective. Unknown confidence
    # and context stay NULL/absent instead of being invented or interpreted as zero.
    for row in bind.execute(sa.text("SELECT id,user_id,day,mood,stress,fatigue,craving,notes FROM journal")):
        observed=datetime.combine(row.day if not isinstance(row.day,str) else datetime.fromisoformat(row.day).date(),datetime.min.time())+timedelta(hours=12)
        scale=lambda value: None if value is None else round((value-1)*2.5)
        bind.execute(sa.text("INSERT INTO ema_check_ins (id,user_id,observed_at_utc,local_date,timezone_id,phase,craving,confidence,stress,positive_affect,negative_affect,fatigue,notes,post_onset,source,schema_version,created_at_utc) VALUES (:id,:user_id,:observed,:day,'unknown','retrospective',:craving,NULL,:stress,:positive,NULL,:fatigue,:notes,NULL,'journal_migration','ema-v1',:created)"),
          {"id":str(uuid.uuid4()),"user_id":row.user_id,"observed":observed,"day":row.day,
           "craving":scale(row.craving) or 0,"stress":scale(row.stress),"positive":scale(row.mood),
           "fatigue":scale(row.fatigue),"notes":row.notes,"created":datetime.utcnow()})

def downgrade():
    # Data-bearing longitudinal tables are intentionally retained on downgrade.
    op.drop_index("ix_drinks_local_date", table_name="drinks")
    op.drop_index("ix_drinks_started_at_utc", table_name="drinks")
    for name in ("timezone_assumption","planned_grams_snapshot","display_unit","display_quantity","utc_offset_minutes","timezone_id","local_date","ended_at_utc","started_at_utc"):
        op.drop_column("drinks", name)
