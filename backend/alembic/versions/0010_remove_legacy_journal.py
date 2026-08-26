"""remove legacy daily journal after EMA migration

Revision ID: 0010
Revises: 0009
"""
from alembic import op
import sqlalchemy as sa

revision="0010"; down_revision="0009"; branch_labels=None; depends_on=None

def upgrade():
    bind=op.get_bind(); inspector=sa.inspect(bind)
    if "journal" in inspector.get_table_names():
        # 0009 copied every legacy row before this destructive cleanup.
        journal_count=bind.scalar(sa.text("SELECT count(*) FROM journal")) or 0
        migrated_count=bind.scalar(sa.text("SELECT count(*) FROM ema_check_ins WHERE source='journal_migration'")) or 0
        if migrated_count < journal_count:
            raise RuntimeError("Legacy journal migration is incomplete; refusing to drop journal")
        op.drop_table("journal")

def downgrade():
    op.create_table("journal",
      sa.Column("id",sa.Integer(),primary_key=True),
      sa.Column("user_id",sa.Integer(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
      sa.Column("day",sa.Date(),nullable=False),sa.Column("mood",sa.Integer()),
      sa.Column("stress",sa.Integer()),sa.Column("fatigue",sa.Integer()),
      sa.Column("craving",sa.Integer()),sa.Column("notes",sa.Text()),
      sa.Column("tags",sa.JSON()),sa.UniqueConstraint("user_id","day"))
