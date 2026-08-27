"""per-user sex and height for the alcohol distribution factor

Revision ID: 0015
Revises: 0014
"""
from alembic import op
import sqlalchemy as sa

revision = "0015"
down_revision = "0014"
branch_labels = None
depends_on = None


def upgrade():
    with op.batch_alter_table("users") as batch:
        batch.add_column(sa.Column("height_cm", sa.Float(), nullable=True))
        batch.add_column(sa.Column("sex", sa.String(12), nullable=False, server_default="unspecified"))


def downgrade():
    with op.batch_alter_table("users") as batch:
        batch.drop_column("sex")
        batch.drop_column("height_cm")
