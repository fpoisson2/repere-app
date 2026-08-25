"""configurable day boundary"""
from alembic import op
import sqlalchemy as sa
revision="0002";down_revision="0001";branch_labels=None;depends_on=None
def upgrade():op.add_column("users",sa.Column("day_start_hour",sa.Integer(),nullable=False,server_default="8"))
def downgrade():op.drop_column("users","day_start_hour")
