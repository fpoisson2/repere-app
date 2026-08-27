from datetime import date, datetime, timedelta
from app.services import aggregate_periods, alcohol, bac_at, bac_projection, import_csv, parse_time, daily_series, sessions
from app.models import Drink, User
from app.db import SessionLocal

def test_alcohol_examples():
    assert alcohol(473,5)[0] == pytest.approx(18.65985,rel=1e-5)
    assert alcohol(473,5)[1] == pytest.approx(1.387,rel=1e-3)
    assert alcohol(750,13)[0] == pytest.approx(76.9275,rel=1e-5)
    assert alcohol(750,13)[1] == pytest.approx(5.72,rel=1e-2)

def test_am_pm():
    assert parse_time("1:25 PM").hour==13
    assert parse_time("12:05 AM").hour==0

def test_import_dedupe_cost_and_tracking(client):
    content=b"id;name;start_date;start_time;duration_min;volume_ml;abv_pct;cost;glass_icon\n42;IPA;2026-02-15;8:30 PM;45;473;6.5;-1;beer\n"
    a=client.post("/api/import",files={"file":("data.csv",content,"text/csv")}).json()
    b=client.post("/api/import",files={"file":("data.csv",content,"text/csv")}).json()
    assert a["rows_imported"]==1 and b["rows_skipped"]==1
    assert client.get("/api/auth/me").json()["tracking_start_date"]=="2026-02-15"
    drinks=client.get("/api/drinks").json(); assert drinks[0]["duration_minutes"]==45

def test_stats_never_precede_tracking_start(client):
    client.patch("/api/settings",json={"tracking_start_date":"2026-08-20"})
    data=client.get("/api/days?start=2026-01-01&end=2026-08-25").json()
    assert data[0]["date"]=="2026-08-20" and len(data)==6

def test_bac_nonnegative_absorption_elimination():
    u=User(weight_kg=75,distribution_ratio=.68,elimination_rate=.015)
    start=datetime(2026,1,1,20)
    d=Drink(started_at=start,ended_at=start+timedelta(minutes=30),duration_minutes=30,alcohol_grams=20)
    early=bac_at([d],u,start+timedelta(minutes=15))[0]
    peak=bac_at([d],u,start+timedelta(minutes=60))[0]
    late=bac_at([d],u,start+timedelta(hours=24))[0]
    assert 0<early<peak and late==0

def test_bac_already_zero_has_no_future_return_time():
    user=User(weight_kg=75,distribution_ratio=.68,elimination_rate=.015)
    result=bac_projection([],user,datetime(2026,8,25,13,0))
    assert result["already_zero"] is True
    assert result["estimated_zero_at"] is None

def test_sessions_gap():
    start=datetime(2026,1,1,18)
    def d(h): return Drink(started_at=start+timedelta(hours=h),ended_at=start+timedelta(hours=h,minutes=30),quantity=1,alcohol_grams=10,canadian_standard_drinks=.74)
    assert len(sessions([d(0),d(2),d(7)],4))==2

def test_configurable_day_starts_at_8(client):
    client.patch("/api/settings",json={"tracking_start_date":"2026-08-20","day_start_hour":8})
    for stamp in ("2026-08-21T02:00:00","2026-08-21T09:00:00"):
        client.post("/api/drinks",json={"drink_name":"Test","volume_ml":100,"abv_percent":10,"started_at":stamp,"duration_minutes":30})
    rows=client.get("/api/days?start=2026-08-20&end=2026-08-21").json()
    assert rows[0]["drinks"]==1 and rows[1]["drinks"]==1

def test_wear_pair_start_and_finish(client):
    pairing=client.post("/api/wear/pairing-code").json()
    assert len(pairing["code"])==6
    paired=client.post("/api/wear/pair",json={"code":pairing["code"],"device_name":"Pixel Watch"}).json()
    headers={"Authorization":f"Bearer {paired['token']}","Idempotency-Key":"wear-test-1"}
    presets=client.get("/api/wear/presets",headers=headers).json()
    started=client.post("/api/wear/start",headers=headers,json={"preset_id":presets[0]["id"],"quantity":2,"started_at":"2026-08-25T18:00:00"}).json()
    assert started["is_active"] is True and started["quantity"]==2
    assert client.get("/api/wear/state",headers=headers).json()["active"]["id"]==started["id"]
    finished=client.post("/api/wear/finish",headers=headers,json={"ended_at":"2026-08-25T18:42:00"}).json()
    assert finished["is_active"] is False and finished["duration_minutes"]==42
    state=client.get("/api/wear/state",headers=headers).json()
    assert state["active"] is None and state["today_standard_drinks"]==0
    dated=client.get("/api/wear/state?now=2026-08-25T20:00:00",headers=headers).json()
    assert dated["today_standard_drinks"]==pytest.approx(finished["canadian_standard_drinks"],abs=0.05)
    today=client.post("/api/wear/start",headers={**headers,"Idempotency-Key":"wear-test-2"},json={"preset_id":presets[0]["id"],"quantity":1}).json()
    assert client.get("/api/wear/state",headers=headers).json()["today_standard_drinks"]==pytest.approx(today["canadian_standard_drinks"],abs=0.05)

def test_wear_pairing_code_is_single_use(client):
    code=client.post("/api/wear/pairing-code").json()["code"]
    assert client.post("/api/wear/pair",json={"code":code}).status_code==200
    assert client.post("/api/wear/pair",json={"code":code}).status_code==401

def test_advanced_endpoints_respect_tracking_start(client):
    client.patch("/api/settings",json={"tracking_start_date":"2026-08-20"})
    data=client.get("/api/stats/advanced").json()
    assert data["tracking_start_date"]=="2026-08-20"
    assert all(row["period_end"] >= "2026-08-20" for row in data["weekly"])

def test_weekly_aggregation_excludes_initial_partial_week():
    rows=[{"date":(date(2026,8,5)+timedelta(days=i)).isoformat(),"grams":10.,"standards":.74,"drinks":1} for i in range(17)]
    weeks=aggregate_periods(rows,"week")
    assert weeks[0]["period_start"]=="2026-08-10"
    assert weeks[0]["is_complete"] is True

def test_current_week_compares_same_elapsed_days(monkeypatch):
    class FixedDate(date):
        @classmethod
        def today(cls): return cls(2026,8,25)
    monkeypatch.setattr("app.services.date",FixedDate)
    rows=[{"date":(date(2026,8,10)+timedelta(days=i)).isoformat(),"grams":10.,"standards":.74,"drinks":1} for i in range(16)]
    weeks=aggregate_periods(rows,"week")
    assert len(weeks)==3
    assert weeks[-1]["is_current"] is True
    assert weeks[-1]["comparison_basis"]=="same_elapsed_days"
    assert weeks[-1]["change_percent"]==0

def test_success_badges_never_reward_high_consumption(client):
    data=client.get("/api/success").json()
    assert data["total_count"]>=8
    forbidden=("maximum consommé","forte consommation","record de grammes")
    descriptions=" ".join(x["description"].lower() for x in data["badges"])
    assert not any(term in descriptions for term in forbidden)
    assert all("progress_percent" in badge for badge in data["badges"])
    assert any(badge["id"]=="weekend_2" for badge in data["badges"])
    tracking=[badge for badge in data["badges"] if badge["category"]=="tracking"]
    assert [badge["target"] for badge in tracking]==[3,7,14,30,60,90,180,365]

def test_goal_suggestions_wait_for_complete_week(client):
    data=client.get("/api/goals/suggestions").json()
    assert data["basis_weeks"]==0 and data["suggestions"]==[]

def test_general_goal_can_be_updated_and_paused(client):
    created=client.post("/api/goals",json={"kind":"max_moving_7_grams","target":20,"temporal_mode":"consecutive_weeks","consecutive_weeks":3}).json()
    goal=client.get("/api/goals").json()[0]
    assert goal["target"]==20 and goal["on_track"] is True and "history" in goal
    assert client.patch(f"/api/goals/{created['id']}",json={"target":15,"active":False,"temporal_mode":"deadline","due_date":"2026-09-30"}).status_code==200
    updated=client.get("/api/goals").json()[0]
    assert updated["target"]==15 and updated["active"] is False and updated["due_date"]=="2026-09-30"

def test_explicit_sober_day_is_distinct_from_missing_day(client):
    client.patch("/api/settings",json={"tracking_start_date":"2026-08-20"})
    assert client.post("/api/days/sober",json={"date":"2026-08-20"}).status_code==200
    rows=client.get("/api/days?start=2026-08-20&end=2026-08-21").json()
    assert rows[0]["status"]=="sober" and rows[0]["observed"] is True
    assert rows[1]["status"]=="no_data" and rows[1]["observed"] is False
    stats=client.get("/api/stats?days=30").json()["period"]
    assert stats["alcohol_free_days"]==1 and stats["days_observed"]==1
    assert client.delete("/api/days/sober/2026-08-20").status_code==204
    assert client.delete("/api/days/sober/2026-08-20").status_code==204
    row=client.get("/api/days?start=2026-08-20&end=2026-08-20").json()[0]
    assert row["status"]=="no_data" and row["observed"] is False

def test_stats_accepts_custom_date_range(client):
    client.patch("/api/settings",json={"tracking_start_date":"2026-08-01"})
    data=client.get("/api/stats?start=2026-08-10&end=2026-08-14").json()
    assert data["range"]=={"start":"2026-08-10","end":"2026-08-14"}
    assert len(data["days"])==5
    assert client.get("/api/stats?start=2026-08-15&end=2026-08-14").status_code==422

def test_cannot_mark_day_with_drink_as_sober(client):
    client.patch("/api/settings",json={"tracking_start_date":"2026-08-20","day_start_hour":8})
    client.post("/api/drinks",json={"drink_name":"Test","volume_ml":100,"abv_percent":10,"started_at":"2026-08-20T12:00:00","duration_minutes":30})
    assert client.post("/api/days/sober",json={"date":"2026-08-20"}).status_code==409

def test_manual_drink_idempotency_and_history_filters(client):
    payload={"drink_name":"Copie sûre","volume_ml":341,"abv_percent":5,"started_at":"2026-08-24T20:00:00","duration_minutes":30}
    headers={"Idempotency-Key":"offline-123"}
    assert client.post("/api/drinks",json=payload,headers=headers).status_code==201
    assert client.post("/api/drinks",json=payload,headers=headers).status_code==201
    rows=client.get("/api/drinks?q=copie&start=2026-08-24&end=2026-08-24").json()
    assert len(rows)==1 and rows[0]["drink_name"]=="Copie sûre"

def test_mobile_sync_snapshot_changes_deletions_and_idempotency(client):
    code=client.post("/api/wear/pairing-code").json()["code"]
    token=client.post("/api/wear/pair",json={"code":code,"device_name":"Android"}).json()["token"]
    auth={"Authorization":f"Bearer {token}"}
    payload={"drink_name":"Hors ligne","volume_ml":341,"abv_percent":5,
      "started_at":"2026-08-24T20:00:00","duration_minutes":30}
    mutation={"mutation_id":"mobile-create-1","operation":"create","data":payload}
    first=client.post("/api/sync",headers=auth,json={"mutations":[mutation]}).json()["results"][0]
    replay=client.post("/api/sync",headers=auth,json={"mutations":[mutation]}).json()["results"][0]
    assert first==replay
    snapshot=client.get("/api/sync?cursor=0",headers=auth).json()
    assert snapshot["snapshot"] is True and snapshot["changes"][0]["payload"]["drink_name"]=="Hors ligne"
    assert snapshot["bac_profile"]["weight_kg"]>0 and snapshot["bac_profile"]["distribution_ratio"]>0
    cursor=snapshot["cursor"]
    deletion={"mutation_id":"mobile-delete-1","operation":"delete","server_id":first["server_id"]}
    assert client.post("/api/sync",headers=auth,json={"mutations":[deletion]}).status_code==200
    changes=client.get(f"/api/sync?cursor={cursor}",headers=auth).json()
    assert changes["changes"][-1]["operation"]=="delete"

def test_bac_day_projection(client):
    client.post("/api/drinks",json={"drink_name":"Test","volume_ml":341,"abv_percent":5,"started_at":"2026-08-24T20:00:00","duration_minutes":30})
    data=client.get("/api/bac/day?day=2026-08-24").json()
    assert len(data["points"])==433 and data["peak"]["bac_percent"]>=0

def test_change_password_logs_out(client):
    response=client.post("/api/auth/change-password",json={"current_password":"motdepasse","new_password":"nouveaumotdepasse"})
    assert response.status_code==204
    assert client.get("/api/auth/me").status_code==401

def test_sqlite_backup_is_downloadable(client):
    response=client.get("/api/backup")
    assert response.status_code==200 and response.content.startswith(b"SQLite format 3")

def test_consumptions_csv_is_downloadable(client):
    client.post("/api/drinks",json={"drink_name":"Export test","volume_ml":341,"abv_percent":5,"started_at":"2026-08-25T20:00:00","duration_minutes":30})
    response=client.get("/api/export?format=csv")
    assert response.status_code==200
    assert response.headers["content-type"].startswith("text/csv")
    assert "attachment" in response.headers["content-disposition"]
    assert "Export test" in response.text

def test_pwa_and_foldable_navigation_assets():
    root=Path(__file__).resolve().parents[2]/"frontend"
    manifest=json.loads((root/"public/manifest.webmanifest").read_text(encoding="utf-8"))
    source=(root/"src/main.tsx").read_text(encoding="utf-8");styles=(root/"src/design-system.css").read_text(encoding="utf-8");worker=(root/"public/sw.js").read_text(encoding="utf-8")
    assert manifest["display"]=="standalone" and "serviceWorker.register" in source
    assert "position: fixed" in styles and "100vw - 16px" in styles
    assert "repere-v4" in worker and "cache.put" in worker

import json
from pathlib import Path
import pytest


import base64 as _b64, hashlib as _hl, os as _os
from urllib.parse import urlparse as _urlparse, parse_qs as _parse_qs
from fastapi.testclient import TestClient as _TestClient
from app.main import app as _app

def _pkce():
    verifier = _b64.urlsafe_b64encode(_os.urandom(40)).rstrip(b"=").decode()
    challenge = _b64.urlsafe_b64encode(_hl.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    return verifier, challenge

_REDIRECT = "ca.repere.app://oauth2redirect"

def _authorize_code(client, challenge, state="xyz"):
    r = client.post("/api/oauth/authorize", data={
        "client_id": "repere-android", "redirect_uri": _REDIRECT, "code_challenge": challenge,
        "state": state, "decision": "allow"}, follow_redirects=False)
    assert r.status_code == 302, r.text
    loc = r.headers["location"]
    assert loc.startswith(_REDIRECT + "?")
    q = _parse_qs(_urlparse(loc).query)
    assert q.get("state") == [state]
    return q["code"][0]

def test_oauth_pkce_happy_path(client):
    verifier, challenge = _pkce()
    page = client.get("/api/oauth/authorize", params={
        "response_type": "code", "client_id": "repere-android", "redirect_uri": _REDIRECT,
        "code_challenge": challenge, "code_challenge_method": "S256", "state": "xyz"})
    assert page.status_code == 200 and "Autoriser" in page.text
    code = _authorize_code(client, challenge)
    tok = client.post("/api/oauth/token", data={
        "grant_type": "authorization_code", "code": code, "redirect_uri": _REDIRECT,
        "client_id": "repere-android", "code_verifier": verifier}).json()
    assert tok["token_type"] == "bearer" and tok["access_token"] and tok["refresh_token"]
    with _TestClient(_app) as bare:
        me = bare.get("/api/auth/me", headers={"Authorization": f"Bearer {tok['access_token']}"})
        assert me.status_code == 200 and me.json()["username"] == "test"
        reuse = bare.post("/api/oauth/token", data={
            "grant_type": "authorization_code", "code": code, "redirect_uri": _REDIRECT,
            "client_id": "repere-android", "code_verifier": verifier})
        assert reuse.status_code == 400
    refreshed = client.post("/api/oauth/token", data={
        "grant_type": "refresh_token", "refresh_token": tok["refresh_token"], "client_id": "repere-android"}).json()
    assert refreshed["access_token"] and refreshed["access_token"] != tok["access_token"]

def test_oauth_rejects_unknown_client(client):
    r = client.get("/api/oauth/authorize", params={
        "response_type": "code", "client_id": "evil", "redirect_uri": "https://evil.example/cb",
        "code_challenge": "x", "code_challenge_method": "S256"})
    assert r.status_code == 400

def test_oauth_pkce_verifier_mismatch_rejected(client):
    _, challenge = _pkce()
    code = _authorize_code(client, challenge)
    r = client.post("/api/oauth/token", data={
        "grant_type": "authorization_code", "code": code, "redirect_uri": _REDIRECT,
        "client_id": "repere-android", "code_verifier": "wrong-verifier"})
    assert r.status_code == 400 and r.json()["error"] == "invalid_grant"

def test_oauth_denied_redirects_with_error(client):
    r = client.post("/api/oauth/authorize", data={
        "client_id": "repere-android", "redirect_uri": _REDIRECT, "code_challenge": "abc",
        "state": "s1", "decision": "deny"}, follow_redirects=False)
    assert r.status_code == 302 and "error=access_denied" in r.headers["location"]


def test_stats_health_series_and_correlation(client):
    client.patch("/api/settings", json={"tracking_start_date": "2026-08-01", "day_start_hour": 0})
    for day, vol in [("2026-08-20", 500), ("2026-08-21", 200), ("2026-08-22", 700)]:
        client.post("/api/drinks", json={"drink_name": "Test", "volume_ml": vol, "abv_percent": 5,
                                         "started_at": f"{day}T19:00:00", "duration_minutes": 30})
    pairing = client.post("/api/wear/pairing-code").json()["code"]
    tok = client.post("/api/wear/pair", json={"code": pairing}).json()["token"]
    h = {"Authorization": f"Bearer {tok}"}

    def agg(day, rtype, value, unit):
        return {"local_date": day, "record_type": rtype, "value": value, "unit": unit,
                "window_start_utc": f"{day}T00:00:00", "window_end_utc": f"{day}T23:59:59",
                "origin_package": "com.test", "aggregation_method": "total"}
    rows = []
    for day, sleep in [("2026-08-20", 360), ("2026-08-21", 480), ("2026-08-22", 300)]:
        rows += [agg(day, "sleep", sleep, "min"), agg(day, "steps", 8000, "count")]
    assert client.post("/api/health-connect/aggregates", headers=h, json=rows).status_code == 200

    data = client.get("/api/stats/health?days=40").json()
    assert set(data["types"]) >= {"sleep", "steps"}
    by_date = {d["date"]: d for d in data["days"]}
    assert by_date["2026-08-21"]["health"]["sleep"] == 480
    assert data["units"]["sleep"] == "min"
    assert -1.0 <= data["correlations"]["sleep"] <= 1.0


def test_body_metrics_drive_distribution_ratio(client):
    r0 = client.get("/api/auth/me").json()["effective_distribution_ratio"]
    patched = client.patch("/api/settings", json={"sex": "female", "height_cm": 165, "weight_kg": 62}).json()
    assert 0.45 <= patched["distribution_ratio"] <= 0.75
    me = client.get("/api/auth/me").json()
    assert me["sex"] == "female" and me["height_cm"] == 165
    male = client.patch("/api/settings", json={"sex": "male", "height_cm": 180, "weight_kg": 85}).json()
    assert male["distribution_ratio"] > patched["distribution_ratio"]


def test_preset_crud(client):
    created = client.post("/api/presets", json={"name": "Cidre 500", "drink_type": "cidre", "volume_ml": 500, "abv_percent": 4.5}).json()
    assert created["id"] and created["volume_ml"] == 500
    names = [p["name"] for p in client.get("/api/presets").json()]
    assert "Cidre 500" in names
    edited = client.patch(f"/api/presets/{created['id']}", json={"volume_ml": 473}).json()
    assert edited["volume_ml"] == 473 and edited["name"] == "Cidre 500"
    # a global (shared) preset can be edited on a self-hosted instance
    shared = next(p for p in client.get("/api/presets").json() if p.get("user_id") is None)
    renamed = client.patch(f"/api/presets/{shared['id']}", json={"name": "Renomme", "volume_ml": 250}).json()
    assert renamed["name"] == "Renomme" and renamed["volume_ml"] == 250
    assert client.delete(f"/api/presets/{created['id']}").status_code == 204
    assert all(p["id"] != created["id"] for p in client.get("/api/presets").json())
    assert client.post("/api/presets", json={"name": "", "volume_ml": 100, "abv_percent": 5}).status_code == 422
