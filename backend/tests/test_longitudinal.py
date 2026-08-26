from datetime import datetime
from app.db import SessionLocal
from app.models import DerivedDailyFeature, EmaCheckIn, InterventionDecision

CHECKIN={
 "observed_at":"2026-08-25T17:00:00Z","local_date":"2026-08-25","timezone_id":"America/Toronto",
 "craving":8,"confidence":3,"planned_grams":26.9,"display_quantity":2,"display_unit":"standard_ca",
 "social_context":"friends","others_drinking":"yes","alcohol_available":True,
}

def test_structured_checkin_is_idempotent_and_audits_no_intervention(client):
    payload={**CHECKIN,"id":"69fe3365-7c55-45dc-987b-957b84dcf311"}
    first=client.post("/api/check-ins",json=payload)
    assert first.status_code==201 and first.json()["post_onset"] is False
    assert first.json()["decision"]["kind"]=="no_intervention"
    assert client.post("/api/check-ins",json=payload).json()["duplicate"] is True
    with SessionLocal() as db:
        assert db.query(EmaCheckIn).count()==1
        assert db.query(InterventionDecision).count()==1

def test_checkin_immediately_advances_success(client):
    before=client.get("/api/success").json()
    badge_before=next(x for x in before["badges"] if x["id"]=="checkin_1")
    assert badge_before["current"]==0 and badge_before["unlocked"] is False
    client.post("/api/check-ins",json=CHECKIN)
    after=client.get("/api/success").json()
    badge_after=next(x for x in after["badges"] if x["id"]=="checkin_1")
    assert badge_after["current"]==1 and badge_after["unlocked"] is True

def test_legacy_journal_routes_are_removed(client):
    assert client.get("/api/journal").status_code==404
    assert client.post("/api/journal",json={"day":"2026-08-25"}).status_code in {404,405}

def test_no_legacy_journal_checkins_exist_in_new_schema(client):
    with SessionLocal() as db:
        assert db.query(EmaCheckIn).filter(EmaCheckIn.source=="journal_migration").count()==0

def test_achieved_goal_creates_persistent_success(client):
    client.post("/api/days/sober",json={"date":"2026-08-26"})
    goal=client.post("/api/goals",json={"kind":"max_grams_session","target":100,
      "temporal_mode":"deadline","due_date":"2099-12-31"}).json()
    first=client.get("/api/success").json()
    badge=next(x for x in first["badges"] if x["category"]=="goal")
    assert badge["unlocked"] is True and "Objectif personnel atteint" in badge["description"]
    client.delete(f"/api/goals/{goal['id']}")
    second=client.get("/api/success").json()
    assert any(x["id"]==badge["id"] for x in second["badges"])

def test_jitai_offer_and_not_now_are_audited(client):
    client.patch("/api/jitai/config",json={"enabled":True,"max_notifications_per_week":2})
    result=client.post("/api/check-ins",json=CHECKIN).json()
    assert result["decision"]["kind"]=="offer" and result["decision"]["rule"]=="goal_confirmation"
    assert client.post(f"/api/interventions/{result['decision']['id']}/exposure",json={"response":"not_now"}).status_code==200

def test_post_onset_checkin_cannot_trigger_intervention(client):
    client.patch("/api/jitai/config",json={"enabled":True})
    client.post("/api/drinks",json={"drink_name":"Test","volume_ml":341,"abv_percent":5,"started_at":"2026-08-25T16:00:00","duration_minutes":30})
    result=client.post("/api/check-ins",json=CHECKIN).json()
    assert result["post_onset"] is True and result["decision"]["kind"]=="no_intervention"

def test_feature_cutoff_blocks_future_checkin_and_drink(client):
    before=client.post("/api/features/2026-08-25?cutoff=2026-08-25T17:30:00Z").json()["values"]
    client.post("/api/drinks",json={"drink_name":"Futur","volume_ml":341,"abv_percent":5,"started_at":"2026-08-25T20:00:00","duration_minutes":30})
    client.post("/api/check-ins",json={**CHECKIN,"observed_at":"2026-08-25T19:00:00Z","craving":10})
    after=client.post("/api/features/2026-08-25?cutoff=2026-08-25T17:30:00Z").json()["values"]
    assert before==after and after["craving"] is None

def test_missing_health_is_null_not_zero(client):
    values=client.post("/api/features/2026-08-25?cutoff=2026-08-25T17:30:00Z").json()["values"]
    assert values["sleep_lag_1"] is None and values["steps_lag_1"] is None

def test_checkin_validation_rejects_invalid_scales_and_context(client):
    assert client.post("/api/check-ins",json={**CHECKIN,"craving":11}).status_code==422
    assert client.post("/api/check-ins",json={**CHECKIN,"social_context":"party"}).status_code==422

def test_personal_analytics_uses_association_language(client):
    data=client.get("/api/analytics/personal").json()
    assert "ne démontrent pas une cause" in data["disclaimer"]
    assert data["model_readiness"]["regularized_model"] is False
