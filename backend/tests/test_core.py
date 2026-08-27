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
    assert client.get("/api/wear/state",headers=headers).json()["active"] is None

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
