"""oauth2 authorization code + pkce for native apps

Revision ID: 0014
Revises: 0013
"""
from alembic import op
import sqlalchemy as sa

revision = "0014"
down_revision = "0013"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "oauth_auth_codes",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("code_hash", sa.String(64), nullable=False),
        sa.Column("client_id", sa.String(64), nullable=False),
        sa.Column("redirect_uri", sa.String(255), nullable=False),
        sa.Column("code_challenge", sa.String(128), nullable=False),
        sa.Column("scope", sa.String(255), nullable=False, server_default=""),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("used_at", sa.DateTime()),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_oauth_auth_codes_user_id", "oauth_auth_codes", ["user_id"])
    op.create_index("ix_oauth_auth_codes_code_hash", "oauth_auth_codes", ["code_hash"], unique=True)
    op.create_table(
        "oauth_tokens",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("user_id", sa.Integer(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("access_hash", sa.String(64), nullable=False),
        sa.Column("refresh_hash", sa.String(64)),
        sa.Column("client_id", sa.String(64), nullable=False),
        sa.Column("device_name", sa.String(120), nullable=False, server_default="Application"),
        sa.Column("scope", sa.String(255), nullable=False, server_default=""),
        sa.Column("access_expires_at", sa.DateTime(), nullable=False),
        sa.Column("last_used_at", sa.DateTime()),
        sa.Column("revoked_at", sa.DateTime()),
        sa.Column("created_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_oauth_tokens_user_id", "oauth_tokens", ["user_id"])
    op.create_index("ix_oauth_tokens_access_hash", "oauth_tokens", ["access_hash"], unique=True)
    op.create_index("ix_oauth_tokens_refresh_hash", "oauth_tokens", ["refresh_hash"], unique=True)


def downgrade():
    op.drop_table("oauth_tokens")
    op.drop_table("oauth_auth_codes")
