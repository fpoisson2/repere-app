"""remove check-ins imported from the retired journal

Revision ID: 0011
Revises: 0010
"""
from alembic import op
import sqlalchemy as sa

revision="0011"; down_revision="0010"; branch_labels=None; depends_on=None

def upgrade():
    op.execute(sa.text("DELETE FROM ema_check_ins WHERE source='journal_migration' AND phase='retrospective'"))

def downgrade():
    # The legacy journal table was intentionally removed in 0010; deleted journal
    # observations cannot be reconstructed without inventing data.
    pass
