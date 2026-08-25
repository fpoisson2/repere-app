"""Add timeframes to goals.

Revision ID: 0004_goal_timeframes
Revises: 0003_tracked_days
"""
from alembic import op
import sqlalchemy as sa

revision = "0004"
down_revision = "0003"
branch_labels = None
depends_on = None

def upgrade():
    op.add_column("goals", sa.Column("temporal_mode", sa.String(length=24), nullable=False, server_default="consecutive_weeks"))
    op.add_column("goals", sa.Column("consecutive_weeks", sa.Integer(), nullable=True))
    op.add_column("goals", sa.Column("due_date", sa.Date(), nullable=True))
    op.add_column("goals", sa.Column("started_on", sa.Date(), nullable=True))
    op.execute("UPDATE goals SET consecutive_weeks = 3 WHERE consecutive_weeks IS NULL")

def downgrade():
    op.drop_column("goals", "started_on")
    op.drop_column("goals", "due_date")
    op.drop_column("goals", "consecutive_weeks")
    op.drop_column("goals", "temporal_mode")
