"""wear os pairing and active drinks

Revision ID: 0007
Revises: 0006
"""
from alembic import op
import sqlalchemy as sa

revision = "0007"
down_revision = "0006"
branch_labels = None
depends_on = None

def upgrade():
    with op.batch_alter_table("drinks") as batch:
        batch.add_column(sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.false()))
        batch.create_index("ix_drinks_is_active", ["is_active"])
    op.create_table(
        "wear_pairing_codes",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("code_hash", sa.String(64), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("used_at", sa.DateTime()),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_wear_pairing_codes_user_id", "wear_pairing_codes", ["user_id"])
    op.create_index("ix_wear_pairing_codes_code_hash", "wear_pairing_codes", ["code_hash"], unique=True)
    op.create_table(
        "wear_tokens",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("device_name", sa.String(120), nullable=False),
        sa.Column("last_used_at", sa.DateTime()),
        sa.Column("revoked_at", sa.DateTime()),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_wear_tokens_user_id", "wear_tokens", ["user_id"])
    op.create_index("ix_wear_tokens_token_hash", "wear_tokens", ["token_hash"], unique=True)

def downgrade():
    op.drop_table("wear_tokens")
    op.drop_table("wear_pairing_codes")
    with op.batch_alter_table("drinks") as batch:
        batch.drop_index("ix_drinks_is_active")
        batch.drop_column("is_active")
