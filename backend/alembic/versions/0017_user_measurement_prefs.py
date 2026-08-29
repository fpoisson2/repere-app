"""per-user standard drink size and volume unit preference

Revision ID: 0017
Revises: 0016
"""
from alembic import op
import sqlalchemy as sa

revision = "0017"
down_revision = "0016"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("users") as batch:
        batch.add_column(sa.Column("standard_drink_grams", sa.Float(), nullable=False, server_default="13.45"))
        batch.add_column(sa.Column("volume_unit", sa.String(8), nullable=False, server_default="ml"))


def downgrade():
    with op.batch_alter_table("users") as batch:
        batch.drop_column("volume_unit")
        batch.drop_column("standard_drink_grams")
