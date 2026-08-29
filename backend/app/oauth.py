"""OAuth 2.0 Authorization Code + PKCE for first-party native apps.

The Repere server is always self-hosted, so there is no central client registry:
the official Android app ships a well-known public ``client_id`` and every instance
accepts it for its own app. No client secret is used; PKCE (S256) is mandatory.
"""
from __future__ import annotations

import base64
import hashlib
import html
import secrets
from datetime import datetime, timedelta
from urllib.parse import urlencode, urlsplit

from fastapi import APIRouter, Depends, Form, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from sqlalchemy import select
from sqlalchemy.orm import Session

from .auth import verify_password
from .db import get_db
from .models import OAuthAuthCode, OAuthToken, User

router = APIRouter(prefix="/api/oauth")

# Well-known public clients. redirect_uris are matched exactly; loopback is always allowed.
OAUTH_CLIENTS: dict[str, list[str]] = {
    "repere-android": ["ca.repere.app://oauth2redirect"],
}
CODE_TTL = timedelta(minutes=5)
ACCESS_TTL = timedelta(days=30)
SCOPES = "profile drinks stats goals health"


def _sha(value: str) -> str:
    return hashlib.sha256(value.encode()).hexdigest()


def _b64url_sha256(value: str) -> str:
    digest = hashlib.sha256(value.encode()).digest()
    return base64.urlsafe_b64encode(digest).rstrip(b"=").decode()


def _redirect_allowed(client_id: str, redirect_uri: str) -> bool:
    allowed = OAUTH_CLIENTS.get(client_id)
    if allowed is None:
        return False
    if redirect_uri in allowed:
        return True
    try:
        parsed = urlsplit(redirect_uri)
        return parsed.scheme == "http" and parsed.hostname in {"127.0.0.1", "localhost"} and bool(parsed.port)
    except ValueError:
        return False


def _page(body: str, status: int = 200) -> HTMLResponse:
    return HTMLResponse(
        f"""<!doctype html><html lang="fr"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Repère</title><style>
:root{{color-scheme:light}}
body{{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#F7F9F5;color:#093B30;
display:flex;min-height:100vh;align-items:center;justify-content:center;padding:24px}}
.card{{background:#fff;border-radius:24px;padding:32px;max-width:360px;width:100%;
box-shadow:0 24px 60px rgba(9,59,48,.12)}}
h1{{font-size:1.25rem;margin:0 0 4px}}
p{{color:#0F5946;opacity:.8;font-size:.9rem;line-height:1.4}}
label{{display:block;font-size:.8rem;font-weight:700;margin:16px 0 6px;text-transform:uppercase;letter-spacing:.04em}}
input{{width:100%;box-sizing:border-box;padding:12px 14px;border:1px solid #cfe3da;border-radius:14px;font-size:1rem}}
.row{{display:flex;gap:10px;margin-top:24px}}
button{{flex:1;padding:12px 16px;border-radius:14px;border:0;font-size:1rem;font-weight:700;cursor:pointer}}
button.primary{{background:#0F5946;color:#fff}}
button.ghost{{background:#EAF3EE;color:#0F5946}}
.err{{background:#fdecec;color:#a12; padding:10px 14px;border-radius:12px;font-size:.85rem;margin-top:12px}}
</style></head><body><div class="card">{body}</div></body></html>""",
        status_code=status,
    )


@router.get("/authorize")
def authorize(
    request: Request,
    response_type: str = "",
    client_id: str = "",
    redirect_uri: str = "",
    code_challenge: str = "",
    code_challenge_method: str = "",
    state: str = "",
    scope: str = "",
    db: Session = Depends(get_db),
):
    if not _redirect_allowed(client_id, redirect_uri):
        return _page("<h1>Demande invalide</h1><p>Client ou URL de redirection non reconnu.</p>", 400)
    if response_type != "code" or code_challenge_method != "S256" or not code_challenge:
        return _redirect_error(redirect_uri, state, "invalid_request")

    uid = request.session.get("user_id")
    user = db.get(User, uid) if uid else None
    hidden = _hidden_fields(client_id, redirect_uri, code_challenge, state, scope)
    if user and request.session.get("session_version") == user.session_version:
        return _page(
            f"<h1>Autoriser l'accès</h1>"
            f"<p>L'application Repère souhaite accéder à ton compte <b>{_esc(user.username)}</b> "
            f"(consommations, statistiques, objectifs, données de santé).</p>"
            f"<form method='post' action='authorize'>{hidden}"
            f"<div class='row'><button class='ghost' name='decision' value='deny'>Refuser</button>"
            f"<button class='primary' name='decision' value='allow'>Autoriser</button></div></form>"
        )
    return _page(
        "<h1>Connexion à Repère</h1><p>Connecte-toi pour autoriser l'application.</p>"
        f"<form method='post' action='authorize'>{hidden}"
        "<label>Identifiant</label><input name='username' autocomplete='username' autofocus>"
        "<label>Mot de passe</label><input name='password' type='password' autocomplete='current-password'>"
        "<div class='row'><button class='primary' name='decision' value='allow'>Se connecter et autoriser</button></div></form>"
    )


@router.post("/authorize")
def authorize_submit(
    request: Request,
    client_id: str = Form(""),
    redirect_uri: str = Form(""),
    code_challenge: str = Form(""),
    state: str = Form(""),
    scope: str = Form(""),
    decision: str = Form("deny"),
    username: str = Form(""),
    password: str = Form(""),
    db: Session = Depends(get_db),
):
    if not _redirect_allowed(client_id, redirect_uri):
        return _page("<h1>Demande invalide</h1><p>URL de redirection non reconnue.</p>", 400)
    if decision != "allow":
        return _redirect_error(redirect_uri, state, "access_denied")

    uid = request.session.get("user_id")
    user = db.get(User, uid) if uid else None
    if not user or request.session.get("session_version") != user.session_version:
        user = db.scalar(select(User).where(User.username == username.strip()))
        if not user or not verify_password(user.password_hash, password):
            hidden = _hidden_fields(client_id, redirect_uri, code_challenge, state, scope)
            return _page(
                "<h1>Connexion à Repère</h1><div class='err'>Identifiants invalides.</div>"
                f"<form method='post' action='authorize'>{hidden}"
                "<label>Identifiant</label><input name='username' autocomplete='username' autofocus>"
                "<label>Mot de passe</label><input name='password' type='password' autocomplete='current-password'>"
                "<div class='row'><button class='primary' name='decision' value='allow'>Se connecter et autoriser</button></div></form>",
                401,
            )
        request.session["user_id"] = user.id
        request.session["session_version"] = user.session_version

    raw_code = secrets.token_urlsafe(32)
    db.add(OAuthAuthCode(
        user_id=user.id, code_hash=_sha(raw_code), client_id=client_id, redirect_uri=redirect_uri,
        code_challenge=code_challenge, scope=scope or SCOPES,
        expires_at=datetime.utcnow() + CODE_TTL,
    ))
    db.commit()
    params = {"code": raw_code}
    if state:
        params["state"] = state
    return RedirectResponse(_with_params(redirect_uri, params), status_code=302)


@router.post("/token")
def token(
    grant_type: str = Form(""),
    code: str = Form(""),
    redirect_uri: str = Form(""),
    client_id: str = Form(""),
    code_verifier: str = Form(""),
    refresh_token: str = Form(""),
    db: Session = Depends(get_db),
):
    if grant_type == "authorization_code":
        row = db.scalar(select(OAuthAuthCode).where(OAuthAuthCode.code_hash == _sha(code)))
        if (not row or row.used_at is not None or row.expires_at < datetime.utcnow()
                or row.client_id != client_id or row.redirect_uri != redirect_uri):
            return _token_error("invalid_grant")
        if _b64url_sha256(code_verifier) != row.code_challenge:
            return _token_error("invalid_grant", "Échec de la vérification PKCE")
        row.used_at = datetime.utcnow()
        issued = _issue(db, row.user_id, client_id, row.scope)
        db.commit()
        return issued

    if grant_type == "refresh_token":
        row = db.scalar(select(OAuthToken).where(
            OAuthToken.refresh_hash == _sha(refresh_token), OAuthToken.revoked_at.is_(None)))
        if not row:
            return _token_error("invalid_grant")
        row.revoked_at = datetime.utcnow()
        issued = _issue(db, row.user_id, row.client_id, row.scope)
        db.commit()
        return issued

    return _token_error("unsupported_grant_type")


@router.post("/revoke", status_code=200)
def revoke(token: str = Form(""), db: Session = Depends(get_db)):
    digest = _sha(token)
    row = db.scalar(select(OAuthToken).where(
        (OAuthToken.access_hash == digest) | (OAuthToken.refresh_hash == digest)))
    if row and row.revoked_at is None:
        row.revoked_at = datetime.utcnow()
        db.commit()
    return {"revoked": True}


def _issue(db: Session, user_id: int, client_id: str, scope: str) -> dict:
    access = secrets.token_urlsafe(32)
    refresh = secrets.token_urlsafe(32)
    db.add(OAuthToken(
        user_id=user_id, access_hash=_sha(access), refresh_hash=_sha(refresh), client_id=client_id,
        device_name="Application Repère", scope=scope,
        access_expires_at=datetime.utcnow() + ACCESS_TTL,
    ))
    return {
        "access_token": access, "token_type": "bearer",
        "expires_in": int(ACCESS_TTL.total_seconds()),
        "refresh_token": refresh, "scope": scope,
    }


def _token_error(code: str, detail: str = "") -> JSONResponse:
    return JSONResponse({"error": code, "error_description": detail or code}, status_code=400)


def _hidden_fields(client_id: str, redirect_uri: str, code_challenge: str, state: str, scope: str) -> str:
    fields = {"client_id": client_id, "redirect_uri": redirect_uri,
              "code_challenge": code_challenge, "state": state, "scope": scope}
    return "".join(
        f"<input type='hidden' name='{k}' value=\"{_esc(v)}\">" for k, v in fields.items())


def _esc(value: str) -> str:
    return html.escape(value, quote=True)


def _with_params(base: str, params: dict) -> str:
    sep = "&" if ("?" in base) else "?"
    return f"{base}{sep}{urlencode(params)}"


def _redirect_error(redirect_uri: str, state: str, error: str):
    params = {"error": error}
    if state:
        params["state"] = state
    return RedirectResponse(_with_params(redirect_uri, params), status_code=302)
