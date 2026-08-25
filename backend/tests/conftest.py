import os
os.environ["DATABASE_URL"]="sqlite:///:memory:"
os.environ["DATA_DIR"]="/tmp"
import pytest
from sqlalchemy.pool import StaticPool
from fastapi.testclient import TestClient
from app.db import Base, engine
from app.main import app

@pytest.fixture(autouse=True)
def clean():
    Base.metadata.drop_all(engine); Base.metadata.create_all(engine); yield

@pytest.fixture
def client():
    with TestClient(app) as c:
        c.post("/api/auth/register",json={"username":"test","password":"motdepasse"})
        yield c

