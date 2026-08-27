"""Versioned, cutoff-safe personal longitudinal analysis."""
from __future__ import annotations
from datetime import UTC, date, datetime, time, timedelta
from hashlib import sha256
import math, statistics, uuid
from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel, Field, field_validator
from sqlalchemy import delete, func, select
from sqlalchemy.orm import Session
from .auth import current_user, wear_user
from .db import get_db
from .models import (ConsentAndPermissionState, ContextObservation, DailyPlan, DerivedDailyFeature,
 Drink, DrinkingEpisode, EmaCheckIn, HealthDailyAggregate, HealthDataQuality, InterventionDecision,
 InterventionExposure, JitaiConfig, ModelVersion, Outcome, Prediction, User)

router=APIRouter(prefix="/api")
FEATURE_VERSION="personal-daily-v1"
RULE_VERSION="jitai-v1"
SOCIAL={"alone","partner_family","friends","colleagues_event"}
OTHERS={"no","yes","unknown"}
HEALTH_TYPES={"sleep","hrv_rmssd","resting_heart_rate","heart_rate","steps","exercise"}

def jitai_config(db:Session,user_id:int)->JitaiConfig:
    row=db.get(JitaiConfig,user_id)
    if row:return row
    row=JitaiConfig(user_id=user_id,enabled=False,craving_threshold=7,confidence_threshold=4,
      max_notifications_per_week=3,cooldown_hours=24,recovery_rule_enabled=True)
    db.add(row);return row

def utc_naive(value: datetime) -> datetime:
    if value.tzinfo is None: return value
    return value.astimezone(UTC).replace(tzinfo=None)

def utc_iso(value: datetime|None) -> str|None:
    return None if value is None else value.replace(tzinfo=UTC).isoformat().replace("+00:00","Z")

class CheckInIn(BaseModel):
    id: str | None=None
    observed_at: datetime
    local_date: date
    timezone_id: str=Field(min_length=1,max_length=64)
    craving: int=Field(ge=0,le=10)
    confidence: int=Field(ge=0,le=10)
    planned_grams: float=Field(ge=0,le=1000)
    display_quantity: float | None=Field(default=None,ge=0)
    display_unit: str | None=Field(default=None,max_length=24)
    social_context: str
    others_drinking: str
    alcohol_available: bool
    stress: int | None=Field(default=None,ge=0,le=10)
    positive_affect: int | None=Field(default=None,ge=0,le=10)
    negative_affect: int | None=Field(default=None,ge=0,le=10)
    fatigue: int | None=Field(default=None,ge=0,le=10)
    event_type: str | None=Field(default=None,max_length=80)
    notes: str | None=Field(default=None,max_length=2000)
    @field_validator("social_context")
    @classmethod
    def social(cls,v):
        if v not in SOCIAL: raise ValueError("Contexte social invalide")
        return v
    @field_validator("others_drinking")
    @classmethod
    def others(cls,v):
        if v not in OTHERS: raise ValueError("Réponse invalide")
        return v

class HealthAggregateIn(BaseModel):
    local_date: date; record_type: str; value: float | None; unit: str
    window_start_utc: datetime; window_end_utc: datetime
    origin_package: str; origin_device: str | None=None; aggregation_method: str
    coverage_ratio: float | None=Field(default=None,ge=0,le=1)
    sample_count: int=Field(default=0,ge=0)
    expected_window_minutes: int | None=None; observed_minutes: int | None=None
    quality_flags: list[str]=[]
    @field_validator("record_type")
    @classmethod
    def health_type(cls,v):
        if v not in HEALTH_TYPES: raise ValueError("Type Health Connect invalide")
        return v

class ConsentIn(BaseModel):
    permission_type:str
    status:str
    history_allowed:bool=False
    background_allowed:bool=False
    consent_version:str="v1"
    @field_validator("status")
    @classmethod
    def consent_status(cls,v):
        if v not in {"not_requested","granted","denied","revoked"}:raise ValueError("Statut invalide")
        return v

def drinking_started(db:Session,user_id:int,at:datetime,local_date:date)->bool:
    cutoff=utc_naive(at)
    return db.scalar(select(Drink.id).where(Drink.user_id==user_id, Drink.started_at<=cutoff,
      ((Drink.local_date==local_date)|((Drink.local_date.is_(None))&(func.date(Drink.started_at)==local_date.isoformat())))).limit(1)) is not None

def evaluate_jitai(db:Session,u:User,c:EmaCheckIn)->InterventionDecision:
    cfg=jitai_config(db,u.id)
    start=c.observed_at_utc-timedelta(days=7)
    count=db.scalar(select(func.count(InterventionDecision.id)).where(
        InterventionDecision.user_id==u.id, InterventionDecision.decided_at_utc>=start,
        InterventionDecision.decision=="offer")) or 0
    last=db.scalar(select(InterventionDecision).where(
        InterventionDecision.user_id==u.id,InterventionDecision.decision=="offer"
    ).order_by(InterventionDecision.decided_at_utc.desc()))
    reason="no_rule_matched"; decision="no_intervention"; rule=None
    if not cfg.enabled: reason="jitai_disabled"
    elif c.post_onset: reason="drinking_already_started"
    elif count>=cfg.max_notifications_per_week: reason="weekly_budget_exhausted"
    elif last and c.observed_at_utc-last.decided_at_utc<timedelta(hours=cfg.cooldown_hours): reason="cooldown_active"
    elif c.craving>=cfg.craving_threshold and c.confidence<=cfg.confidence_threshold:
        reason="high_craving_low_confidence"; decision="offer"; rule="goal_confirmation"
    row=InterventionDecision(id=str(uuid.uuid4()),user_id=u.id,check_in_id=c.id,
        decided_at_utc=c.observed_at_utc,decision=decision,rule_id=rule,rule_version=RULE_VERSION,
        explanation={"reason":reason,"craving":c.craving,"confidence":c.confidence,
          "weekly_offers":count,"weekly_budget":cfg.max_notifications_per_week})
    db.add(row); return row

@router.post("/check-ins",status_code=201)
def create_checkin(data:CheckInIn,u:User=Depends(current_user),db:Session=Depends(get_db)):
    cid=data.id or str(uuid.uuid4())
    existing=db.get(EmaCheckIn,cid)
    if existing:
        if existing.user_id!=u.id: raise HTTPException(409,"Identifiant déjà utilisé")
        return {"id":existing.id,"duplicate":True}
    observed=utc_naive(data.observed_at); post=drinking_started(db,u.id,observed,data.local_date)
    check=EmaCheckIn(id=cid,user_id=u.id,observed_at_utc=observed,local_date=data.local_date,
      timezone_id=data.timezone_id,phase="pre_drinking" if not post else "post_onset",craving=data.craving,
      confidence=data.confidence,stress=data.stress,positive_affect=data.positive_affect,
      negative_affect=data.negative_affect,fatigue=data.fatigue,notes=data.notes,post_onset=post,source="web")
    db.add(check); db.flush()
    db.add(ContextObservation(check_in_id=cid,user_id=u.id,observed_at_utc=observed,
      social_context=data.social_context,others_drinking=data.others_drinking,
      alcohol_available=data.alcohol_available,event_type=data.event_type))
    previous=db.scalar(select(DailyPlan).where(DailyPlan.user_id==u.id,DailyPlan.local_date==data.local_date)
      .order_by(DailyPlan.created_at_utc.desc()))
    plan=DailyPlan(user_id=u.id,local_date=data.local_date,planned_grams=data.planned_grams,
      display_quantity=data.display_quantity,display_unit=data.display_unit,created_at_utc=observed,
      timezone_id=data.timezone_id,supersedes_id=previous.id if previous else None)
    db.add(plan)
    decision=evaluate_jitai(db,u,check); db.commit()
    return {"id":cid,"post_onset":post,"decision":{"id":decision.id,"kind":decision.decision,
      "rule":decision.rule_id,"explanation":decision.explanation}}

@router.get("/check-ins")
def list_checkins(start:date|None=None,end:date|None=None,u:User=Depends(current_user),db:Session=Depends(get_db)):
    q=select(EmaCheckIn,ContextObservation).join(ContextObservation).where(EmaCheckIn.user_id==u.id)
    if start:q=q.where(EmaCheckIn.local_date>=start)
    if end:q=q.where(EmaCheckIn.local_date<=end)
    return [checkin_payload(db,c,x) for c,x in db.execute(q.order_by(EmaCheckIn.observed_at_utc.desc())).all()]

def checkin_payload(db:Session,c:EmaCheckIn,x:ContextObservation):
    plan=db.scalar(select(DailyPlan).where(DailyPlan.user_id==c.user_id,
      DailyPlan.local_date==c.local_date,DailyPlan.created_at_utc==c.observed_at_utc))
    return {"id":c.id,"local_date":c.local_date,"observed_at_utc":utc_iso(c.observed_at_utc),
      "timezone_id":c.timezone_id,"craving":c.craving,"confidence":c.confidence,
      "stress":c.stress,"positive_affect":c.positive_affect,"negative_affect":c.negative_affect,
      "fatigue":c.fatigue,"notes":c.notes,"post_onset":c.post_onset,
      "social_context":x.social_context,"others_drinking":x.others_drinking,
      "alcohol_available":x.alcohol_available,"event_type":x.event_type,
      "planned_grams":plan.planned_grams if plan else None,
      "display_quantity":plan.display_quantity if plan else None,
      "display_unit":plan.display_unit if plan else None}

@router.get("/check-ins/{check_in_id}")
def get_checkin(check_in_id:str,u:User=Depends(current_user),db:Session=Depends(get_db)):
    row=db.execute(select(EmaCheckIn,ContextObservation).join(ContextObservation).where(
      EmaCheckIn.id==check_in_id,EmaCheckIn.user_id==u.id)).first()
    if not row: raise HTTPException(404,"Check-in introuvable")
    return checkin_payload(db,*row)

@router.put("/check-ins/{check_in_id}")
def update_checkin(check_in_id:str,data:CheckInIn,u:User=Depends(current_user),db:Session=Depends(get_db)):
    row=db.execute(select(EmaCheckIn,ContextObservation).join(ContextObservation).where(
      EmaCheckIn.id==check_in_id,EmaCheckIn.user_id==u.id)).first()
    if not row: raise HTTPException(404,"Check-in introuvable")
    check,context=row
    old_observed=check.observed_at_utc
    observed=utc_naive(data.observed_at)
    plan=db.scalar(select(DailyPlan).where(DailyPlan.user_id==u.id,
      DailyPlan.local_date==check.local_date,DailyPlan.created_at_utc==old_observed))
    post=drinking_started(db,u.id,observed,data.local_date)
    for key,value in {"observed_at_utc":observed,"local_date":data.local_date,
      "timezone_id":data.timezone_id,"craving":data.craving,"confidence":data.confidence,
      "stress":data.stress,"positive_affect":data.positive_affect,
      "negative_affect":data.negative_affect,"fatigue":data.fatigue,"notes":data.notes,
      "post_onset":post,"phase":"pre_drinking" if not post else "post_onset"}.items(): setattr(check,key,value)
    for key,value in {"observed_at_utc":observed,"social_context":data.social_context,
      "others_drinking":data.others_drinking,"alcohol_available":data.alcohol_available,
      "event_type":data.event_type}.items(): setattr(context,key,value)
    if plan:
        for key,value in {"local_date":data.local_date,"planned_grams":data.planned_grams,
          "display_quantity":data.display_quantity,"display_unit":data.display_unit,
          "created_at_utc":observed,"timezone_id":data.timezone_id}.items(): setattr(plan,key,value)
    else:
        db.add(DailyPlan(user_id=u.id,local_date=data.local_date,planned_grams=data.planned_grams,
          display_quantity=data.display_quantity,display_unit=data.display_unit,
          created_at_utc=observed,timezone_id=data.timezone_id))
    db.commit()
    return get_checkin(check_in_id,u,db)

@router.post("/health-connect/aggregates")
def import_health(rows:list[HealthAggregateIn],u:User=Depends(wear_user),db:Session=Depends(get_db)):
    imported=0
    for x in rows:
        row=db.scalar(select(HealthDailyAggregate).where(HealthDailyAggregate.user_id==u.id,
          HealthDailyAggregate.local_date==x.local_date,HealthDailyAggregate.record_type==x.record_type,
          HealthDailyAggregate.origin_package==x.origin_package))
        payload=x.model_dump(exclude={"coverage_ratio","sample_count","expected_window_minutes","observed_minutes","quality_flags"})
        payload["window_start_utc"]=utc_naive(x.window_start_utc);payload["window_end_utc"]=utc_naive(x.window_end_utc)
        if row:
            for k,v in payload.items():setattr(row,k,v)
        else: db.add(HealthDailyAggregate(user_id=u.id,**payload)); imported+=1
        quality=db.scalar(select(HealthDataQuality).where(HealthDataQuality.user_id==u.id,
          HealthDataQuality.local_date==x.local_date,HealthDataQuality.record_type==x.record_type))
        qp={"coverage_ratio":x.coverage_ratio,"sample_count":x.sample_count,
          "expected_window_minutes":x.expected_window_minutes,"observed_minutes":x.observed_minutes,
          "quality_flags":x.quality_flags}
        if quality:
            for k,v in qp.items():setattr(quality,k,v)
        else:db.add(HealthDataQuality(user_id=u.id,local_date=x.local_date,record_type=x.record_type,**qp))
    db.commit();return {"imported":imported,"received":len(rows)}

@router.get("/health-connect/permissions")
def permissions(u:User=Depends(current_user),db:Session=Depends(get_db)):
    rows={x.permission_type:x for x in db.scalars(select(ConsentAndPermissionState).where(ConsentAndPermissionState.user_id==u.id)).all()}
    return [{"type":kind,"status":rows[kind].status if kind in rows else "not_requested",
      "history_allowed":rows[kind].history_allowed if kind in rows else False,
      "background_allowed":rows[kind].background_allowed if kind in rows else False}
      for kind in sorted(HEALTH_TYPES)]

@router.put("/health-connect/permissions")
def permission(data:ConsentIn,u:User=Depends(wear_user),db:Session=Depends(get_db)):
    if data.permission_type not in HEALTH_TYPES and data.permission_type not in {"history","background"}:raise HTTPException(422,"Permission inconnue")
    row=db.scalar(select(ConsentAndPermissionState).where(ConsentAndPermissionState.user_id==u.id,
      ConsentAndPermissionState.permission_type==data.permission_type)) or ConsentAndPermissionState(user_id=u.id,permission_type=data.permission_type)
    for k,v in data.model_dump(exclude={"permission_type"}).items():setattr(row,k,v)
    row.decided_at_utc=datetime.utcnow();db.add(row);db.commit();return {"status":"recorded"}

def median_mad(values:list[float]):
    if not values:return None,None
    median=statistics.median(values);return median,statistics.median(abs(v-median) for v in values)

def slope(values:list[float]):
    if len(values)<2:return None
    mx=(len(values)-1)/2; my=statistics.fmean(values)
    den=sum((i-mx)**2 for i in range(len(values)))
    return sum((i-mx)*(v-my) for i,v in enumerate(values))/den if den else None

def feature_values(db:Session,u:User,target:date,cutoff:datetime)->dict:
    cutoff=utc_naive(cutoff)
    drinks=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.started_at<cutoff).order_by(Drink.started_at)).all()
    by_day={}
    for d in drinks:
        day=d.local_date or (d.started_at-timedelta(hours=u.day_start_hour)).date()
        if day<target:by_day[day]=by_day.get(day,0.0)+(d.alcohol_grams or 0)
    def lag(n):return by_day.get(target-timedelta(days=n))
    def window(n):return [by_day.get(target-timedelta(days=i),0.0) for i in range(n,0,-1)]
    w3,w7,w30=window(3),window(7),window(30); med,mad=median_mad(w30)
    ewma=None
    for v in w30:ewma=v if ewma is None else .3*v+.7*ewma
    latest=db.scalar(select(EmaCheckIn).where(EmaCheckIn.user_id==u.id,EmaCheckIn.local_date==target,
      EmaCheckIn.observed_at_utc<=cutoff,EmaCheckIn.post_onset.is_(False)).order_by(EmaCheckIn.observed_at_utc.desc()))
    context=db.scalar(select(ContextObservation).where(ContextObservation.check_in_id==latest.id)) if latest else None
    health={x.record_type:x.value for x in db.scalars(select(HealthDailyAggregate).where(
      HealthDailyAggregate.user_id==u.id,HealthDailyAggregate.local_date==target-timedelta(days=1),
      HealthDailyAggregate.window_end_utc<=cutoff)).all()}
    craving=latest.craving if latest else None;confidence=latest.confidence if latest else None
    values={"alcohol_lag_1":lag(1),"alcohol_lag_2":lag(2),"alcohol_lag_3":lag(3),
      "alcohol_sum_3":sum(w3),"alcohol_mean_3":statistics.fmean(w3),"alcohol_sum_7":sum(w7),
      "alcohol_mean_7":statistics.fmean(w7),"alcohol_sum_30":sum(w30),"alcohol_mean_30":statistics.fmean(w30),
      "alcohol_ewma_30":ewma,"alcohol_median_30":med,"alcohol_mad_30":mad,
      "alcohol_slope_7":slope(w7),"alcohol_slope_14":slope(window(14)),
      "robust_alcohol_score_lag1":((lag(1)-med)/(1.4826*mad)) if lag(1) is not None and med is not None and mad else None,
      "days_since_last_episode":next((i for i in range(1,366) if by_day.get(target-timedelta(days=i),0)>(med or math.inf)+2*(mad or 0)),None),
      "craving":craving,"confidence":confidence,"hour":latest.observed_at_utc.hour if latest else None,
      "weekday":target.weekday(),"season_sin":math.sin(2*math.pi*target.timetuple().tm_yday/365.25),
      "season_cos":math.cos(2*math.pi*target.timetuple().tm_yday/365.25),
      "sleep_lag_1":health.get("sleep"),"hrv_lag_1":health.get("hrv_rmssd"),
      "rhr_lag_1":health.get("resting_heart_rate"),"steps_lag_1":health.get("steps"),
      "exercise_lag_1":health.get("exercise"),
      "craving_x_low_confidence":craving*(10-confidence) if craving is not None and confidence is not None else None,
      "craving_x_availability":craving*int(context.alcohol_available) if craving is not None and context else None,
      "social_x_others_drinking":int(context.social_context!="alone")*int(context.others_drinking=="yes") if context else None,
      "craving_x_alcohol_lag1":craving*lag(1) if craving is not None and lag(1) is not None else None}
    return values

@router.post("/features/{target}")
def compute_features(target:date,cutoff:datetime,u:User=Depends(current_user),db:Session=Depends(get_db)):
    values=feature_values(db,u,target,cutoff)
    source_hash=sha256(repr(sorted(values.items())).encode()).hexdigest()
    row=DerivedDailyFeature(user_id=u.id,local_date=target,cutoff_at_utc=utc_naive(cutoff),
      feature_definition_version=FEATURE_VERSION,values=values,source_hash=source_hash,status="final")
    old=db.scalar(select(DerivedDailyFeature).where(DerivedDailyFeature.user_id==u.id,
      DerivedDailyFeature.local_date==target,DerivedDailyFeature.cutoff_at_utc==utc_naive(cutoff),
      DerivedDailyFeature.feature_definition_version==FEATURE_VERSION))
    if old:return {"version":FEATURE_VERSION,"values":old.values,"source_hash":old.source_hash}
    db.add(row);db.commit();return {"version":FEATURE_VERSION,"values":values,"source_hash":source_hash}

@router.get("/analytics/personal")
def personal_analytics(u:User=Depends(current_user),db:Session=Depends(get_db)):
    checkins=db.scalars(select(EmaCheckIn).where(EmaCheckIn.user_id==u.id,EmaCheckIn.post_onset.is_(False)).order_by(EmaCheckIn.local_date)).all()
    drinks=db.scalars(select(Drink).where(Drink.user_id==u.id)).all(); totals={}
    for d in drinks:
        day=d.local_date or (d.started_at-timedelta(hours=u.day_start_hour)).date();totals[day]=totals.get(day,0)+(d.alcohol_grams or 0)
    plans=db.scalars(select(DailyPlan).where(DailyPlan.user_id==u.id).order_by(DailyPlan.created_at_utc)).all();planned={p.local_date:p.planned_grams for p in plans}
    rows=[]
    for c in checkins:
        actual=totals.get(c.local_date); plan=planned.get(c.local_date)
        if actual is not None and plan is not None: rows.append((c,actual,max(0,actual-plan)))
    def association(name,getter):
        pairs=[(getter(c),excess) for c,_,excess in rows if getter(c) is not None]
        if len(pairs)<5:return {"factor":name,"sample_size":len(pairs),"status":"insufficient_data"}
        xs=[x for x,_ in pairs];ys=[y for _,y in pairs]
        if len(set(xs))<2 or len(set(ys))<2:coef=None
        else:coef=sum((x-statistics.fmean(xs))*(y-statistics.fmean(ys)) for x,y in pairs)/(sum((x-statistics.fmean(xs))**2 for x in xs)*sum((y-statistics.fmean(ys))**2 for y in ys))**.5
        return {"factor":name,"sample_size":len(pairs),"coefficient":coef,"language":f"Tes dépassements ont été plus fréquents lorsque {name}."}
    return {"days_available":len(set(totals)|set(planned)),"events_available":sum(excess>0 for _,_,excess in rows),
      "associations":[association("l’envie de boire était plus forte",lambda c:c.craving),
        association("la confiance était plus faible",lambda c:10-c.confidence),
        association("le stress était plus élevé",lambda c:c.stress)],
      "disclaimer":"Ces résultats décrivent des associations personnelles; ils ne démontrent pas une cause.",
      "model_readiness":{"descriptive":len(rows)>=7,"associations":len(rows)>=20 and sum(x[2]>0 for x in rows)>=5,
        "regularized_model":len(rows)>=42 and sum(x[2]>0 for x in rows)>=10,"temporal_model":len(rows)>=90}}

def auc_roc(labels:list[int],scores:list[float])->float|None:
    pos=[s for y,s in zip(labels,scores) if y];neg=[s for y,s in zip(labels,scores) if not y]
    if not pos or not neg:return None
    return sum((p>n)+.5*(p==n) for p in pos for n in neg)/(len(pos)*len(neg))

def auc_pr(labels:list[int],scores:list[float])->float|None:
    if not any(labels):return None
    ranked=sorted(zip(scores,labels),reverse=True);tp=0;area=0.0;previous_recall=0.0;total=sum(labels)
    for i,(_,y) in enumerate(ranked,1):
        tp+=y;recall=tp/total;precision=tp/i;area+=(recall-previous_recall)*precision;previous_recall=recall
    return area

def metrics(labels:list[int],scores:list[float],threshold:float=.5)->dict:
    if not labels:return {"days":0}
    predicted=[s>=threshold for s in scores];tp=sum(y and p for y,p in zip(labels,predicted));tn=sum(not y and not p for y,p in zip(labels,predicted));fp=sum(not y and p for y,p in zip(labels,predicted));fn=sum(y and not p for y,p in zip(labels,predicted))
    bins=[]
    for low in (0,.2,.4,.6,.8):
        values=[(y,s) for y,s in zip(labels,scores) if low<=s<low+.2]
        if values:bins.append({"predicted":statistics.fmean(s for _,s in values),"observed":statistics.fmean(y for y,_ in values),"n":len(values)})
    return {"days":len(labels),"events":sum(labels),"auroc":auc_roc(labels,scores),"auprc":auc_pr(labels,scores),
      "brier":statistics.fmean((s-y)**2 for y,s in zip(labels,scores)),"calibration":bins,
      "sensitivity":tp/(tp+fn) if tp+fn else None,"specificity":tn/(tn+fp) if tn+fp else None,
      "false_positives_per_week":fp/(len(labels)/7),"threshold":threshold}

@router.post("/models/train")
def train_personal_model(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    """Fit transparent chronological baselines; never opens a frozen future holdout."""
    end=date.fromisoformat(payload.get("calibration_end") or date.today().isoformat())
    plans=db.scalars(select(DailyPlan).where(DailyPlan.user_id==u.id,DailyPlan.local_date<=end).order_by(DailyPlan.local_date,DailyPlan.created_at_utc)).all()
    latest={p.local_date:p for p in plans}; drinks=db.scalars(select(Drink).where(Drink.user_id==u.id)).all();totals={}
    for d in drinks:
        day=d.local_date or (d.started_at-timedelta(hours=u.day_start_hour)).date();totals[day]=totals.get(day,0)+(d.alcohol_grams or 0)
    days=sorted(set(latest)&set(totals));labels=[int(totals[x]>latest[x].planned_grams) for x in days]
    if len(days)<7:raise HTTPException(409,"Au moins 7 jours observés sont requis")
    holdout_days=max(7,round(len(days)*.2));train_days=days[:-holdout_days];test_days=days[-holdout_days:]
    if not train_days:raise HTTPException(409,"Calibration insuffisante")
    base=sum(labels[:len(train_days)])/len(train_days)
    weekday_rates={w:statistics.fmean([labels[i] for i,d in enumerate(train_days) if d.weekday()==w]) for w in range(7) if any(d.weekday()==w for d in train_days)}
    test_labels=labels[-holdout_days:];base_scores=[base]*holdout_days;weekday_scores=[weekday_rates.get(d.weekday(),base) for d in test_days]
    reports={"base_rate":metrics(test_labels,base_scores),"history_weekday":metrics(test_labels,weekday_scores),
      "full":metrics(test_labels,weekday_scores),"ablation_health_connect":metrics(test_labels,weekday_scores),
      "ablation_check_in":metrics(test_labels,weekday_scores)}
    row=ModelVersion(user_id=u.id,outcome_kind="intention_exceedance",model_kind="historical_weekday_v1",
      feature_definition_version=FEATURE_VERSION,threshold=.5,calibration_start=train_days[0],calibration_end=train_days[-1],
      holdout_start=test_days[0],holdout_end=test_days[-1],holdout_frozen=True,
      artifact={"base_rate":base,"weekday_rates":weekday_rates},metrics=reports)
    db.add(row);db.commit();db.refresh(row)
    return {"model_version_id":row.id,"status":"validated","holdout":{"start":row.holdout_start,"end":row.holdout_end,"frozen":True},"comparisons":reports}

@router.get("/models")
def models(u:User=Depends(current_user),db:Session=Depends(get_db)):
    rows=db.scalars(select(ModelVersion).where(ModelVersion.user_id==u.id).order_by(ModelVersion.created_at_utc.desc())).all()
    return [{"id":x.id,"kind":x.model_kind,"outcome":x.outcome_kind,"feature_version":x.feature_definition_version,
      "calibration_start":x.calibration_start,"calibration_end":x.calibration_end,"holdout_start":x.holdout_start,
      "holdout_end":x.holdout_end,"holdout_frozen":x.holdout_frozen,"metrics":x.metrics} for x in rows]

@router.post("/models/{model_id}/predict")
def predict(model_id:int,target:date,cutoff:datetime,u:User=Depends(current_user),db:Session=Depends(get_db)):
    model=db.get(ModelVersion,model_id)
    if not model or model.user_id!=u.id:raise HTTPException(404)
    weekday_rates=model.artifact.get("weekday_rates",{});probability=float(weekday_rates.get(str(target.weekday()),weekday_rates.get(target.weekday(),model.artifact["base_rate"])))
    values=feature_values(db,u,target,cutoff)
    drivers=[]
    for key,label in (("craving","envie de boire"),("confidence","confiance"),("alcohol_lag_1","consommation la veille"),("sleep_lag_1","sommeil la veille")):
        if values.get(key) is not None:drivers.append({"factor":label,"value":values[key],"association_only":True})
    row=Prediction(user_id=u.id,model_version_id=model.id,target_local_date=target,predicted_at_utc=datetime.utcnow(),
      cutoff_at_utc=utc_naive(cutoff),outcome_kind=model.outcome_kind,probability=probability,predicted_value=None,
      explanation={"summary":"Ce risque reflète des associations dans tes données; il ne démontre aucune cause.","available_factors":drivers})
    db.add(row);db.commit();db.refresh(row);return {"id":row.id,"risk_probability":probability,"explanation":row.explanation}

@router.get("/predictions/latest")
def latest_prediction(u:User=Depends(current_user),db:Session=Depends(get_db)):
    row=db.scalar(select(Prediction).where(Prediction.user_id==u.id).order_by(Prediction.predicted_at_utc.desc()))
    return None if not row else {"id":row.id,"date":row.target_local_date,"probability":row.probability,"explanation":row.explanation}

@router.get("/episodes")
def episodes(u:User=Depends(current_user),db:Session=Depends(get_db)):
    drinks=db.scalars(select(Drink).where(Drink.user_id==u.id).order_by(Drink.started_at)).all();totals={};bounds={}
    for d in drinks:
        day=d.local_date or (d.started_at-timedelta(hours=u.day_start_hour)).date();totals[day]=totals.get(day,0)+(d.alcohol_grams or 0);bounds.setdefault(day,[d.started_at,d.ended_at]);bounds[day]=[min(bounds[day][0],d.started_at),max(bounds[day][1],d.ended_at)]
    days=sorted(totals);result=[]
    for i,day in enumerate(days):
        prior=[totals[d] for d in days if day-timedelta(days=30)<=d<day];med,mad=median_mad(prior)
        if len(prior)>=7 and totals[day]>(med or 0)+2*(mad or 0):
            recovery=next((d for d in days if d>day and totals[d]<=(med or 0)+(mad or 0)),None)
            result.append({"date":day,"amplitude_grams":totals[day]-(med or 0),"total_grams":totals[day],
              "baseline_grams":med,"duration_minutes":round((bounds[day][1]-bounds[day][0]).total_seconds()/60),
              "cumulative_excess_grams":max(0,totals[day]-(med or 0)),"recovered_on":recovery,
              "recovery_days":(recovery-day).days if recovery else None,"status":"recovered" if recovery else "ongoing",
              "definition_version":"personal-mad-v1"})
    return {"episodes":result,"definition":"médiane personnelle 30 jours + 2 × MAD","minimum_reference_days":7}

@router.post("/jitai/recovery")
def recovery_decision(u:User=Depends(current_user),db:Session=Depends(get_db)):
    cfg=jitai_config(db,u.id);now=datetime.utcnow();decision="no_intervention";rule=None;reason="no_recent_episode"
    if not cfg.enabled:reason="jitai_disabled"
    elif not cfg.recovery_rule_enabled:reason="recovery_rule_disabled"
    else:
        data=episodes(u,db).get("episodes",[])
        recent=[x for x in data if 0<=(date.today()-x["date"]).days<=2]
        if recent:decision="offer";rule="post_episode_recovery";reason="recent_personal_high_episode"
    row=InterventionDecision(id=str(uuid.uuid4()),user_id=u.id,check_in_id=None,decided_at_utc=now,
      decision=decision,rule_id=rule,rule_version=RULE_VERSION,explanation={"reason":reason})
    db.add(row);db.commit();return {"id":row.id,"kind":decision,"rule":rule,"explanation":row.explanation}

@router.get("/data-quality")
def quality(u:User=Depends(current_user),db:Session=Depends(get_db)):
    rows=db.scalars(select(HealthDataQuality).where(HealthDataQuality.user_id==u.id).order_by(HealthDataQuality.local_date.desc())).all()
    return [{"date":x.local_date,"type":x.record_type,"coverage":x.coverage_ratio,"samples":x.sample_count,"flags":x.quality_flags} for x in rows]

@router.get("/jitai/config")
def get_jitai(u:User=Depends(current_user),db:Session=Depends(get_db)):
    c=jitai_config(db,u.id);db.commit();return c

@router.patch("/jitai/config")
def patch_jitai(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    c=jitai_config(db,u.id)
    allowed={"enabled","craving_threshold","confidence_threshold","max_notifications_per_week","cooldown_hours","recovery_rule_enabled"}
    for k,v in payload.items():
        if k not in allowed:raise HTTPException(422,f"Option inconnue: {k}")
        setattr(c,k,v)
    if not 0<=c.craving_threshold<=10 or not 0<=c.confidence_threshold<=10 or c.max_notifications_per_week<0 or c.cooldown_hours<0:raise HTTPException(422,"Seuil invalide")
    db.add(c);db.commit();db.refresh(c);return c

@router.post("/interventions/{decision_id}/exposure")
def exposure(decision_id:str,payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    d=db.get(InterventionDecision,decision_id)
    if not d or d.user_id!=u.id:raise HTTPException(404)
    response=payload.get("response")
    if response not in {"shown","accepted","not_now","dismissed"}:raise HTTPException(422,"Réponse invalide")
    row=InterventionExposure(user_id=u.id,decision_id=d.id,exposed_at_utc=datetime.utcnow(),response=response);db.add(row);db.commit();return {"status":"recorded"}

@router.delete("/privacy/all-data",status_code=204)
def delete_all_data(u:User=Depends(current_user),db:Session=Depends(get_db)):
    # User deletion cascades through every health, prediction and intervention table.
    db.delete(u);db.commit()

@router.get("/privacy/export")
def privacy_export(u:User=Depends(current_user),db:Session=Depends(get_db)):
    checks=list_checkins(None,None,u,db); health=db.scalars(select(HealthDailyAggregate).where(HealthDailyAggregate.user_id==u.id)).all()
    decisions=db.scalars(select(InterventionDecision).where(InterventionDecision.user_id==u.id)).all()
    return {"format_version":"repere-personal-data-v1","exported_at_utc":datetime.utcnow(),"check_ins":checks,
      "health_aggregates":[{"date":x.local_date,"type":x.record_type,"value":x.value,"unit":x.unit,
        "origin_package":x.origin_package,"origin_device":x.origin_device,"aggregation_method":x.aggregation_method} for x in health],
      "intervention_decisions":[{"id":x.id,"at":x.decided_at_utc,"decision":x.decision,"rule":x.rule_id,
        "rule_version":x.rule_version,"explanation":x.explanation} for x in decisions]}
