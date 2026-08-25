"""Persistent manual session grouping."""
from alembic import op
import sqlalchemy as sa
revision="0006";down_revision="0005";branch_labels=None;depends_on=None
def upgrade():
 op.add_column("drinks",sa.Column("manual_session_id",sa.String(length=36),nullable=True));op.create_index("ix_drinks_manual_session_id","drinks",["manual_session_id"])
def downgrade():
 op.drop_index("ix_drinks_manual_session_id",table_name="drinks");op.drop_column("drinks","manual_session_id")
