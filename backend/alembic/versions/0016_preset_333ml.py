"""default beer preset 341 ml -> 333 ml

Revision ID: 0016
Revises: 0015
"""
from alembic import op
import sqlalchemy as sa

revision = "0016"
down_revision = "0015"
branch_labels = None
depends_on = None


def upgrade():
    op.execute(
        "UPDATE presets SET name = 'Bière 333 ml', volume_ml = 333 "
        "WHERE user_id IS NULL AND name = 'Bière 341 ml' AND volume_ml = 341"
    )


def downgrade():
    op.execute(
        "UPDATE presets SET name = 'Bière 341 ml', volume_ml = 341 "
        "WHERE user_id IS NULL AND name = 'Bière 333 ml' AND volume_ml = 333"
    )
