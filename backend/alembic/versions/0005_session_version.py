"""Revocable local sessions."""
from alembic import op
import sqlalchemy as sa
revision="0005";down_revision="0004";branch_labels=None;depends_on=None
def upgrade():op.add_column("users",sa.Column("session_version",sa.Integer(),nullable=False,server_default="1"))
def downgrade():op.drop_column("users","session_version")
