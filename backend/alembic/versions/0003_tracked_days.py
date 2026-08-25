"""explicit sober tracked days"""
from alembic import op
import sqlalchemy as sa
revision="0003";down_revision="0002";branch_labels=None;depends_on=None
def upgrade():
 op.create_table("tracked_days",sa.Column("id",sa.Integer(),primary_key=True),sa.Column("user_id",sa.Integer(),sa.ForeignKey("users.id",ondelete="CASCADE"),nullable=False),sa.Column("day",sa.Date(),nullable=False),sa.Column("sober",sa.Boolean(),nullable=False,server_default=sa.true()),sa.Column("notes",sa.Text()),sa.Column("created_at",sa.DateTime()),sa.UniqueConstraint("user_id","day"))
 op.create_index("ix_tracked_days_user_id","tracked_days",["user_id"]);op.create_index("ix_tracked_days_day","tracked_days",["day"])
def downgrade():op.drop_table("tracked_days")
