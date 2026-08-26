"""persist successes for achieved goals

Revision ID: 0012
Revises: 0011
"""
from alembic import op
import sqlalchemy as sa

revision="0012"; down_revision="0011"; branch_labels=None; depends_on=None

def upgrade():
    op.create_table("goal_achievements",
      sa.Column("id",sa.Integer(),primary_key=True),
      sa.Column("user_id",sa.Integer(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
      sa.Column("goal_id",sa.Integer(),sa.ForeignKey("goals.id",ondelete="SET NULL"),nullable=True),
      sa.Column("goal_kind",sa.String(48),nullable=False),sa.Column("target_snapshot",sa.Float(),nullable=False),
      sa.Column("temporal_mode",sa.String(24),nullable=False),sa.Column("achieved_at_utc",sa.DateTime(),nullable=False),
      sa.Column("evidence",sa.JSON(),nullable=False),sa.UniqueConstraint("goal_id"))
    op.create_index("ix_goal_achievements_user_id","goal_achievements",["user_id"])
    op.create_index("ix_goal_achievements_goal_id","goal_achievements",["goal_id"],unique=True)
    op.create_index("ix_goal_achievements_achieved_at_utc","goal_achievements",["achieved_at_utc"])

def downgrade():op.drop_table("goal_achievements")
