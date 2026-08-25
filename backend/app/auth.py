import hashlib
from datetime import datetime
from fastapi import Depends, Header, HTTPException, Request
from argon2 import PasswordHasher
from argon2.exceptions import VerifyMismatchError
from sqlalchemy.orm import Session
from .db import get_db
from .models import User, WearToken

ph = PasswordHasher()
def hash_password(value: str) -> str: return ph.hash(value)
def verify_password(hashed: str, value: str) -> bool:
    try: return ph.verify(hashed, value)
    except VerifyMismatchError: return False

def current_user(request: Request, db: Session = Depends(get_db)) -> User:
    uid = request.session.get("user_id")
    user = db.get(User, uid) if uid else None
    if not user or request.session.get("session_version") != user.session_version:
        request.session.clear()
        raise HTTPException(401, "Authentification requise")
    return user

def wear_user(authorization: str | None = Header(None), db: Session = Depends(get_db)) -> User:
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "Jeton Wear OS requis")
    token_hash = hashlib.sha256(authorization[7:].encode()).hexdigest()
    token = db.query(WearToken).filter(WearToken.token_hash == token_hash, WearToken.revoked_at.is_(None)).first()
    if not token:
        raise HTTPException(401, "Jeton Wear OS invalide")
    token.last_used_at = datetime.utcnow()
    user = db.get(User, token.user_id)
    if not user:
        raise HTTPException(401, "Utilisateur introuvable")
    db.commit()
    return user
