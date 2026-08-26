"""incremental mobile synchronization journal

Revision ID: 0013
Revises: 0012
"""
from alembic import op
import sqlalchemy as sa

revision="0013"; down_revision="0012"; branch_labels=None; depends_on=None

def upgrade():
    op.create_table("sync_events",
      sa.Column("id",sa.Integer(),primary_key=True),
      sa.Column("user_id",sa.Integer(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
      sa.Column("entity_type",sa.String(32),nullable=False),sa.Column("entity_id",sa.Integer(),nullable=False),
      sa.Column("operation",sa.String(12),nullable=False),sa.Column("payload",sa.JSON()),
      sa.Column("created_at",sa.DateTime(),nullable=False))
    op.create_index("ix_sync_events_user_id","sync_events",["user_id"])
    op.create_index("ix_sync_events_entity_type","sync_events",["entity_type"])
    op.create_index("ix_sync_events_created_at","sync_events",["created_at"])
    op.create_table("sync_mutations",
      sa.Column("id",sa.Integer(),primary_key=True),
      sa.Column("user_id",sa.Integer(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),
      sa.Column("mutation_id",sa.String(64),nullable=False),sa.Column("result",sa.JSON(),nullable=False),
      sa.Column("created_at",sa.DateTime(),nullable=False),
      sa.UniqueConstraint("user_id","mutation_id",name="uq_sync_mutation_user_id"))
    op.create_index("ix_sync_mutations_user_id","sync_mutations",["user_id"])

def downgrade():
    op.drop_table("sync_mutations");op.drop_table("sync_events")
