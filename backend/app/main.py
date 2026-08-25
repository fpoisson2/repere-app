import csv, hashlib, io, json, math, os, secrets, sqlite3, statistics, tempfile, time as time_module, uuid
from datetime import date, datetime, time, timedelta
from pathlib import Path
from fastapi import Depends, FastAPI, File, Header, HTTPException, Query, Request, Response, UploadFile
from fastapi.middleware.trustedhost import TrustedHostMiddleware
from fastapi.responses import FileResponse, StreamingResponse
from starlette.background import BackgroundTask
from fastapi.staticfiles import StaticFiles
from starlette.middleware.sessions import SessionMiddleware
from sqlalchemy import delete, func, select, text
from sqlalchemy.orm import Session
from . import __version__
from .auth import current_user, hash_password, verify_password, wear_user
from .db import Base, engine, get_db
from .models import AiInsight, Drink, Goal, ImportBatch, Journal, Preset, TrackedDay, User, WearPairingCode, WearToken
from .schemas import DrinkIn, DrinkOut, Login, SettingsPatch
from .services import (aggregate_periods, alcohol, bac_at, bac_projection, compare_series,
 daily_series, import_csv, key_for, pearson, period_stats, reduction_records, sessions, spearman, temporal_stats)
from .settings import settings

app=FastAPI(title="Repère", version=__version__)
login_attempts={}
app.add_middleware(TrustedHostMiddleware, allowed_hosts=[x.strip() for x in settings.trusted_hosts.split(",")])
app.add_middleware(SessionMiddleware, secret_key=settings.secret_key, https_only=settings.secure_cookies,
                   same_site="lax", max_age=60*60*24*30)

DEFAULT_PRESETS=[("Bière 341 ml","bière",341,5),("Bière 473 ml","bière",473,5),("IPA 473 ml","bière",473,6.5),
 ("Vin 150 ml","vin",150,12),("Bouteille de vin","vin",750,13),("Spiritueux 43 ml","spiritueux",43,40)]

@app.on_event("startup")
def startup():
    Path(settings.data_dir).mkdir(parents=True,exist_ok=True); Base.metadata.create_all(engine)
    with next(get_db()) as db:
        if not db.scalar(select(Preset.id).where(Preset.user_id.is_(None))):
            db.add_all([Preset(user_id=None,name=n,drink_type=t,volume_ml=v,abv_percent=a) for n,t,v,a in DEFAULT_PRESETS]); db.commit()

@app.get("/api/health")
def health(db:Session=Depends(get_db)):
    try: db.execute(text("SELECT 1")); database="ok"
    except Exception: database="error"
    storage=os.access(settings.data_dir,os.W_OK)
    return {"status":"healthy" if database=="ok" and storage else "degraded","version":__version__,"database":database,"database_version":"1","storage":"ok" if storage else "error"}

@app.post("/api/auth/register")
def register(data:Login, request:Request, db:Session=Depends(get_db)):
    if db.scalar(select(User).where(User.username==data.username)): raise HTTPException(409,"Utilisateur existant")
    u=User(username=data.username,password_hash=hash_password(data.password)); db.add(u); db.commit(); db.refresh(u)
    request.session["user_id"]=u.id;request.session["session_version"]=u.session_version;return {"username":u.username}

@app.post("/api/auth/login")
def login(data:Login, request:Request, db:Session=Depends(get_db)):
    ip=request.client.host if request.client else "local";now=time_module.monotonic();attempts=[stamp for stamp in login_attempts.get(ip,[]) if now-stamp<300]
    if len(attempts)>=8:raise HTTPException(429,"Trop de tentatives. Réessayez dans quelques minutes.")
    u=db.scalar(select(User).where(User.username==data.username))
    if not u or not verify_password(u.password_hash,data.password):login_attempts[ip]=attempts+[now];raise HTTPException(401,"Identifiants invalides")
    login_attempts.pop(ip,None)
    request.session.clear();request.session["user_id"]=u.id;request.session["session_version"]=u.session_version;return {"username":u.username}

@app.post("/api/auth/logout",status_code=204)
def logout(request:Request): request.session.clear()

@app.post("/api/auth/change-password",status_code=204)
def change_password(payload:dict,request:Request,u:User=Depends(current_user),db:Session=Depends(get_db)):
    if not verify_password(u.password_hash,str(payload.get("current_password", ""))):raise HTTPException(401,"Mot de passe actuel invalide")
    new=str(payload.get("new_password", ""))
    if len(new)<8:raise HTTPException(422,"Le nouveau mot de passe doit contenir au moins 8 caractères")
    u.password_hash=hash_password(new);u.session_version+=1;db.commit();request.session.clear()

@app.get("/api/auth/me")
def me(u:User=Depends(current_user)): return {"id":u.id,"username":u.username,"tracking_start_date":u.tracking_start_date,"day_start_hour":u.day_start_hour,"weight_kg":u.weight_kg,"distribution_ratio":u.distribution_ratio,"elimination_rate":u.elimination_rate,"session_gap_hours":u.session_gap_hours}

@app.patch("/api/settings")
def patch_settings(data:SettingsPatch,u:User=Depends(current_user),db:Session=Depends(get_db)):
    changes=data.model_dump(exclude_unset=True)
    if "tracking_start_date" in changes: u.tracking_start_explicit=True
    for k,v in changes.items(): setattr(u,k,v)
    db.commit(); return changes

def wear_drink_out(d:Drink):
    return {"id":d.id,"drink_name":d.drink_name,"volume_ml":d.volume_ml,"abv_percent":d.abv_percent,
      "quantity":d.quantity,"started_at":d.started_at,"ended_at":d.ended_at,"duration_minutes":d.duration_minutes,
      "alcohol_grams":d.alcohol_grams,"canadian_standard_drinks":d.canadian_standard_drinks,"is_active":d.is_active}

@app.post("/api/wear/pairing-code")
def create_wear_pairing_code(u:User=Depends(current_user),db:Session=Depends(get_db)):
    now=datetime.utcnow()
    for old in db.scalars(select(WearPairingCode).where(WearPairingCode.user_id==u.id,WearPairingCode.used_at.is_(None))).all():
        old.used_at=now
    code=f"{secrets.randbelow(1_000_000):06d}"
    db.add(WearPairingCode(user_id=u.id,code_hash=hashlib.sha256(code.encode()).hexdigest(),expires_at=now+timedelta(minutes=10),used_at=None))
    db.commit()
    return {"code":code,"expires_at":now+timedelta(minutes=10),"server_hint":str(settings.public_url).rstrip("/") if getattr(settings,"public_url",None) else None}

@app.post("/api/wear/pair")
def pair_wear(payload:dict,db:Session=Depends(get_db)):
    code=str(payload.get("code","")).strip()
    if len(code)!=6 or not code.isdigit():raise HTTPException(422,"Code à six chiffres requis")
    row=db.scalar(select(WearPairingCode).where(WearPairingCode.code_hash==hashlib.sha256(code.encode()).hexdigest(),WearPairingCode.used_at.is_(None)))
    if not row or row.expires_at<datetime.utcnow():raise HTTPException(401,"Code expiré ou invalide")
    raw=secrets.token_urlsafe(32);row.used_at=datetime.utcnow()
    token=WearToken(user_id=row.user_id,token_hash=hashlib.sha256(raw.encode()).hexdigest(),device_name=str(payload.get("device_name") or "Android / Wear OS")[:120])
    db.add(token);db.commit();db.refresh(token)
    return {"token":raw,"device_id":token.id}

@app.get("/api/wear/devices")
def wear_devices(u:User=Depends(current_user),db:Session=Depends(get_db)):
    return [{"id":x.id,"device_name":x.device_name,"created_at":x.created_at,"last_used_at":x.last_used_at}
      for x in db.scalars(select(WearToken).where(WearToken.user_id==u.id,WearToken.revoked_at.is_(None)).order_by(WearToken.created_at.desc())).all()]

@app.delete("/api/wear/devices/{token_id}",status_code=204)
def revoke_wear_device(token_id:int,u:User=Depends(current_user),db:Session=Depends(get_db)):
    token=db.get(WearToken,token_id)
    if not token or token.user_id!=u.id:raise HTTPException(404)
    token.revoked_at=datetime.utcnow();db.commit()

@app.get("/api/wear/presets")
def wear_presets(u:User=Depends(wear_user),db:Session=Depends(get_db)):
    rows=db.scalars(select(Preset).where((Preset.user_id==u.id)|(Preset.user_id.is_(None))).order_by(Preset.id)).all()
    return [{"id":x.id,"name":x.name,"drink_type":x.drink_type,"volume_ml":x.volume_ml,"abv_percent":x.abv_percent} for x in rows]

@app.get("/api/wear/state")
def wear_state(u:User=Depends(wear_user),db:Session=Depends(get_db)):
    active=db.scalar(select(Drink).where(Drink.user_id==u.id,Drink.is_active.is_(True)).order_by(Drink.started_at.desc()))
    return {"active":wear_drink_out(active) if active else None,"server_time":datetime.now()}

@app.post("/api/wear/start",status_code=201)
def wear_start(payload:dict,idempotency_key:str|None=Header(None,alias="Idempotency-Key"),u:User=Depends(wear_user),db:Session=Depends(get_db)):
    active=db.scalar(select(Drink).where(Drink.user_id==u.id,Drink.is_active.is_(True)))
    if active:return wear_drink_out(active)
    preset=db.get(Preset,int(payload["preset_id"])) if payload.get("preset_id") else None
    if preset and preset.user_id not in (None,u.id):raise HTTPException(404,"Preset introuvable")
    volume=float(payload.get("volume_ml") or (preset.volume_ml if preset else 473));abv=float(payload.get("abv_percent") if payload.get("abv_percent") is not None else (preset.abv_percent if preset else 5));quantity=int(payload.get("quantity") or 1)
    if volume<=0 or not 0<=abv<=100 or quantity<1:raise HTTPException(422,"Volume, taux ou quantité invalide")
    started=datetime.fromisoformat(str(payload["started_at"]).replace("Z","+00:00")).replace(tzinfo=None) if payload.get("started_at") else datetime.now()
    grams,standard=alcohol(volume,abv,quantity);dedupe=f"wear:{idempotency_key}" if idempotency_key else f"wear:{uuid.uuid4()}"
    d=Drink(user_id=u.id,drink_type=preset.drink_type if preset else str(payload.get("drink_type") or "autre"),drink_name=str(payload.get("drink_name") or (preset.name if preset else "Consommation")),volume_ml=volume,abv_percent=abv,quantity=quantity,started_at=started,ended_at=started,duration_minutes=0,notes=None,cost=None,source_icon="wear",import_source="wear_os",external_id=None,import_batch_id=None,dedupe_key=dedupe,alcohol_grams=grams,canadian_standard_drinks=standard,is_active=True)
    db.add(d);day=(started-timedelta(hours=u.day_start_hour)).date()
    if not u.tracking_start_explicit and (not u.tracking_start_date or day<u.tracking_start_date):u.tracking_start_date=day
    db.commit();db.refresh(d);return wear_drink_out(d)

@app.post("/api/wear/finish")
def wear_finish(payload:dict,u:User=Depends(wear_user),db:Session=Depends(get_db)):
    d=db.scalar(select(Drink).where(Drink.user_id==u.id,Drink.is_active.is_(True)).order_by(Drink.started_at.desc()))
    if not d:raise HTTPException(404,"Aucune consommation active")
    ended=datetime.fromisoformat(str(payload["ended_at"]).replace("Z","+00:00")).replace(tzinfo=None) if payload.get("ended_at") else datetime.now()
    if ended<d.started_at:raise HTTPException(422,"La fin précède le début")
    d.ended_at=ended;d.duration_minutes=max(0,round((ended-d.started_at).total_seconds()/60));d.is_active=False
    db.commit();db.refresh(d);return wear_drink_out(d)

@app.get("/api/drinks",response_model=list[DrinkOut])
def list_drinks(day:date|None=None,start:date|None=None,end:date|None=None,q:str|None=None,limit:int=Query(500,ge=1,le=2000),offset:int=Query(0,ge=0),u:User=Depends(current_user),db:Session=Depends(get_db)):
    query=select(Drink).where(Drink.user_id==u.id)
    if day is not None:
        boundary=timedelta(hours=u.day_start_hour)
        start=datetime.combine(day,time.min)+boundary
        query=query.where(Drink.started_at>=start,Drink.started_at<start+timedelta(days=1))
    elif start or end:
        if start:query=query.where(Drink.started_at>=datetime.combine(start,time.min)+timedelta(hours=u.day_start_hour))
        if end:query=query.where(Drink.started_at<datetime.combine(end+timedelta(days=1),time.min)+timedelta(hours=u.day_start_hour))
    if q:query=query.where(func.lower(Drink.drink_name).contains(q.lower()))
    return db.scalars(query.order_by(Drink.started_at.desc()).offset(offset).limit(limit)).all()

@app.post("/api/drinks",response_model=DrinkOut,status_code=201)
def add_drink(data:DrinkIn,idempotency_key:str|None=Header(None,alias="Idempotency-Key"),u:User=Depends(current_user),db:Session=Depends(get_db)):
    if idempotency_key:
        existing=db.scalar(select(Drink).where(Drink.user_id==u.id,Drink.dedupe_key==f"manual:{idempotency_key}"))
        if existing:return existing
    grams,standard=alcohol(data.volume_ml,data.abv_percent,data.quantity)
    ended=data.started_at+timedelta(minutes=data.duration_minutes)
    dedupe=f"manual:{idempotency_key}" if idempotency_key else key_for("manual",None,[data.started_at.isoformat(),data.drink_name,data.volume_ml,data.abv_percent,data.duration_minutes,datetime.utcnow().isoformat()])
    d=Drink(**data.model_dump(),user_id=u.id,ended_at=ended,dedupe_key=dedupe,alcohol_grams=grams,
            canadian_standard_drinks=standard,source_icon=None,import_source=None,external_id=None,import_batch_id=None)
    db.add(d)
    day=(data.started_at-timedelta(hours=u.day_start_hour)).date()
    if not u.tracking_start_explicit and (not u.tracking_start_date or day<u.tracking_start_date): u.tracking_start_date=day
    db.commit(); db.refresh(d); return d

@app.patch("/api/drinks/{drink_id}",response_model=DrinkOut)
def update_drink(drink_id:int,data:DrinkIn,u:User=Depends(current_user),db:Session=Depends(get_db)):
    d=db.get(Drink,drink_id)
    if not d or d.user_id!=u.id:raise HTTPException(404)
    grams,standard=alcohol(data.volume_ml,data.abv_percent,data.quantity)
    for key,value in data.model_dump().items():setattr(d,key,value)
    d.ended_at=data.started_at+timedelta(minutes=data.duration_minutes)
    d.alcohol_grams=grams;d.canadian_standard_drinks=standard
    db.commit();db.refresh(d);return d

@app.delete("/api/drinks/{drink_id}",status_code=204)
def remove_drink(drink_id:int,u:User=Depends(current_user),db:Session=Depends(get_db)):
    d=db.get(Drink,drink_id)
    if not d or d.user_id!=u.id: raise HTTPException(404)
    db.delete(d); db.commit()

@app.get("/api/drinks/search")
def search_drinks(q:str|None=None,start:date|None=None,end:date|None=None,drink_type:str|None=None,min_abv:float|None=None,max_abv:float|None=None,min_standards:float|None=None,page:int=Query(1,ge=1),page_size:int=Query(25,ge=5,le=100),u:User=Depends(current_user),db:Session=Depends(get_db)):
    query=select(Drink).where(Drink.user_id==u.id)
    boundary=timedelta(hours=u.day_start_hour)
    if start:query=query.where(Drink.started_at>=datetime.combine(start,time.min)+boundary)
    if end:query=query.where(Drink.started_at<datetime.combine(end+timedelta(days=1),time.min)+boundary)
    if q:query=query.where(func.lower(Drink.drink_name).contains(q.lower()))
    if drink_type:query=query.where(Drink.drink_type==drink_type)
    if min_abv is not None:query=query.where(Drink.abv_percent>=min_abv)
    if max_abv is not None:query=query.where(Drink.abv_percent<=max_abv)
    if min_standards is not None:query=query.where(Drink.canadian_standard_drinks>=min_standards)
    count=db.scalar(select(func.count()).select_from(query.subquery())) or 0
    all_matching=db.scalars(query).all();summary={"grams":sum(d.alcohol_grams for d in all_matching),"standards":sum(d.canadian_standard_drinks for d in all_matching),"consumptions":sum(d.quantity for d in all_matching)}
    items=db.scalars(query.order_by(Drink.started_at.desc()).offset((page-1)*page_size).limit(page_size)).all()
    return {"items":[DrinkOut.model_validate(d).model_dump() for d in items],"total":count,"page":page,"page_size":page_size,"pages":math.ceil(count/page_size) if count else 0,"summary":summary}

@app.post("/api/drinks/bulk-delete")
def bulk_delete(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    ids=[int(value) for value in payload.get("ids",[])]
    rows=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.id.in_(ids))).all()
    for row in rows:db.delete(row)
    db.commit();return {"deleted":len(rows)}

@app.get("/api/drinks/export")
def export_selected(ids:str,u:User=Depends(current_user),db:Session=Depends(get_db)):
    values=[int(value) for value in ids.split(",") if value.isdigit()]
    rows=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.id.in_(values)).order_by(Drink.started_at)).all()
    out=io.StringIO();fields=["id","name","started_at","volume_ml","abv_percent","quantity","duration_minutes","alcohol_grams","standards"];writer=csv.DictWriter(out,fieldnames=fields);writer.writeheader()
    for d in rows:writer.writerow({"id":d.id,"name":d.drink_name,"started_at":d.started_at.isoformat(),"volume_ml":d.volume_ml,"abv_percent":d.abv_percent,"quantity":d.quantity,"duration_minutes":d.duration_minutes,"alcohol_grams":d.alcohol_grams,"standards":d.canadian_standard_drinks})
    return StreamingResponse(iter([out.getvalue()]),media_type="text/csv",headers={"Content-Disposition":"attachment; filename=repere-selection.csv"})

@app.get("/api/presets")
def presets(u:User=Depends(current_user),db:Session=Depends(get_db)):
    return db.scalars(select(Preset).where((Preset.user_id==u.id)|(Preset.user_id.is_(None)))).all()

@app.post("/api/import")
async def do_import(file:UploadFile=File(...),u:User=Depends(current_user),db:Session=Depends(get_db)):
    try: return import_csv(db,u,file.filename or "import.csv",await file.read())
    except ValueError as e: raise HTTPException(422,str(e))

@app.get("/api/import/history")
def import_history(u:User=Depends(current_user),db:Session=Depends(get_db)):
    return db.scalars(select(ImportBatch).where(ImportBatch.user_id==u.id).order_by(ImportBatch.imported_at.desc())).all()

@app.delete("/api/import/history/{batch_id}")
def undo_import(batch_id:int,u:User=Depends(current_user),db:Session=Depends(get_db)):
    b=db.get(ImportBatch,batch_id)
    if not b or b.user_id!=u.id: raise HTTPException(404)
    removed=db.scalar(select(func.count()).select_from(Drink).where(Drink.import_batch_id==b.id))
    db.delete(b); db.commit(); return {"removed":removed}

@app.get("/api/days")
def days(start:date|None=None,end:date|None=None,u:User=Depends(current_user),db:Session=Depends(get_db)):
    return daily_series(db,u,start or date.today()-timedelta(days=89),end or date.today())

@app.post("/api/days/sober")
def mark_sober(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    day=date.fromisoformat(payload.get("date") or date.today().isoformat())
    if day>date.today():raise HTTPException(422,"Une journée future ne peut pas être déclarée sobre")
    boundary=timedelta(hours=u.day_start_hour)
    has_drink=db.scalar(select(Drink.id).where(Drink.user_id==u.id,Drink.started_at>=datetime.combine(day,time.min)+boundary,Drink.started_at<datetime.combine(day+timedelta(days=1),time.min)+boundary))
    if has_drink:raise HTTPException(409,"Cette journée contient déjà une consommation")
    tracked=db.scalar(select(TrackedDay).where(TrackedDay.user_id==u.id,TrackedDay.day==day)) or TrackedDay(user_id=u.id,day=day)
    tracked.sober=True;tracked.notes=payload.get("notes");db.add(tracked)
    if not u.tracking_start_explicit and (not u.tracking_start_date or day<u.tracking_start_date):u.tracking_start_date=day
    db.commit()
    series=daily_series(db,u,u.tracking_start_date or day,day);streak=0
    for row in reversed(series):
        if row["status"]=="sober":streak+=1
        else:break
    next_targets=[x for x in (3,7,14,30,60,90,180,365) if x>streak]
    milestones=[
      {"hours":8,"label":"8 heures","text":"Le corps continue de métaboliser l’alcool; le rythme exact varie selon la personne."},
      {"hours":24,"label":"24 heures","text":"Sans nouvel alcool et avec un apport normal en liquides, les symptômes liés à la déshydratation peuvent commencer à s’atténuer."},
      {"hours":48,"label":"48 heures","text":"Le sommeil peut commencer à se normaliser après les effets aigus, mais un sevrage peut aussi le perturber temporairement."},
      {"hours":72,"label":"72 heures","text":"Certaines personnes constatent une amélioration de l’humeur ou de l’anxiété; l’expérience varie fortement."},
      {"hours":168,"label":"1 semaine","text":"Réduire l’alcool soutient la santé cardiovasculaire et peut contribuer à une baisse de la tension au fil du temps."},
      {"hours":336,"label":"2 semaines","text":"L’abstinence permet au foie de commencer à récupérer lorsqu’une atteinte réversible, comme une stéatose, est présente."},
      {"hours":720,"label":"1 mois","text":"Sommeil, énergie, peau ou poids peuvent évoluer favorablement, sans résultat ni perte de poids garantis."},
    ]
    return {"date":day,"status":"sober","current_sober_streak":streak,"next_streak_target":next_targets[0] if next_targets else None,
      "health_milestones":milestones,"health_note":"Repères généraux, pas une prédiction médicale. En cas de consommation importante ou de symptômes de sevrage, consultez rapidement un professionnel de santé.",
      "sources":[{"label":"Santé Canada","url":"https://www.canada.ca/en/health-canada/services/substance-use/alcohol/health-risks.html"},{"label":"NIAAA","url":"https://www.niaaa.nih.gov/health-professionals-communities/core-resource-on-alcohol/alcohol-use-disorder-risk-diagnosis-recovery"}]}

@app.delete("/api/days/sober/{day}",status_code=204)
def unmark_sober(day:date,u:User=Depends(current_user),db:Session=Depends(get_db)):
    tracked=db.scalar(select(TrackedDay).where(TrackedDay.user_id==u.id,TrackedDay.day==day))
    if tracked:db.delete(tracked);db.commit()

@app.get("/api/stats")
def stats(days:int=30,u:User=Depends(current_user),db:Session=Depends(get_db)):
    series=daily_series(db,u,date.today()-timedelta(days=days-1),date.today()); return {"tracking_start_date":u.tracking_start_date,"period":period_stats(series),"days":series}

@app.get("/api/stats/distribution")
def distribution(days:int=90,u:User=Depends(current_user),db:Session=Depends(get_db)): return stats(days,u,db)["period"]

@app.get("/api/stats/trends")
def trends(u:User=Depends(current_user),db:Session=Depends(get_db)):
    series=daily_series(db,u,u.tracking_start_date or date.today(),date.today())
    moving={}
    for window in (3,7,14,30,60,90):
        rows=[]
        for i,x in enumerate(series):
            observed=[y for y in series[max(0,i-window+1):i+1] if y["observed"]]
            rows.append({"date":x["date"],"observed_days":len(observed),
              "grams":sum(y["grams"] for y in observed)/len(observed) if observed else 0,
              "standards":sum(y["standards"] for y in observed)/len(observed) if observed else 0,
              "daily_grams":x["grams"],"daily_standards":x["standards"],"status":x["status"]})
        moving[str(window)]=rows
    return {"tracking_start_date":u.tracking_start_date,"moving_averages":moving,
      "weekly":aggregate_periods(series,"week"),"monthly":aggregate_periods(series,"month")}

@app.get("/api/stats/advanced")
def advanced(u:User=Depends(current_user),db:Session=Depends(get_db)):
    start=u.tracking_start_date or date.today(); series=daily_series(db,u,start,date.today())
    ds=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.started_at>=datetime.combine(start,time.min)).order_by(Drink.started_at)).all()
    session_rows=sessions(ds,u.session_gap_hours)
    observed=[x for x in series if x["observed"]]
    quality={"calendar_days":len(series),"observed_days":len(observed),"no_data_days":sum(not x["observed"] for x in series),
      "sober_days":sum(x["status"]=="sober" for x in series),"alcohol_days":sum(x["status"]=="alcohol" for x in series),
      "completeness_percent":100*len(observed)/len(series) if series else None}
    alcohol_day_values=[x["standards"] for x in observed if x["status"]=="alcohol"]
    quality["sober_percent"]=100*quality["sober_days"]/quality["observed_days"] if quality["observed_days"] else None
    quality["alcohol_day_mean_standards"]=statistics.fmean(alcohol_day_values) if alcohol_day_values else None
    def recent_summary(rows):
        rows=[x for x in rows if x["observed"]]; alcohol_rows=[x for x in rows if x["status"]=="alcohol"]
        return {"days":len(rows),"sober_days":sum(x["status"]=="sober" for x in rows),"alcohol_days":len(alcohol_rows),"mean_standards":statistics.fmean(x["standards"] for x in rows) if rows else None,"alcohol_day_mean_standards":statistics.fmean(x["standards"] for x in alcohol_rows) if alcohol_rows else None}
    quality["recent_7"]=recent_summary(series[-7:]); quality["previous_7"]=recent_summary(series[-14:-7])
    boundary=timedelta(hours=u.day_start_hour); by_day={x["date"]:x for x in series}; drinks_by_day={}
    for drink in ds:
        logical_day=(drink.started_at-boundary).date().isoformat()
        drinks_by_day.setdefault(logical_day,[]).append(drink)
    scatter=[]
    for day,rows in sorted(drinks_by_day.items()):
        total=sum(x.canadian_standard_drinks for x in rows); first=min(rows,key=lambda x:x.started_at)
        hour=first.started_at.hour+first.started_at.minute/60
        last_end=max(x.ended_at for x in rows); duration_hours=max((last_end-first.started_at).total_seconds()/3600,.25)
        first_two=sum(x.canadian_standard_drinks for x in rows if x.started_at<first.started_at+timedelta(hours=2))
        first_four=sum(x.canadian_standard_drinks for x in rows if x.started_at<first.started_at+timedelta(hours=4))
        scatter.append({"date":day,"first_hour":round(hour,2),"last_hour":round(last_end.hour+last_end.minute/60,2),"duration_hours":round(duration_hours,2),"standards_per_hour":round(total/duration_hours,3),"first_2h_standards":round(first_two,3),"first_4h_standards":round(first_four,3),"standards":round(total,3),"grams":round(sum(x.alcohol_grams for x in rows),2),"high":total>=4})
    bins=[]
    for label,low,high in (("Avant 18 h",0,18),("18–20 h",18,20),("20–22 h",20,22),("Après 22 h",22,24)):
        rows=[x for x in scatter if low<=x["first_hour"]<high]
        bins.append({"label":label,"from_hour":low,"to_hour":high,"days":len(rows),"mean_standards":sum(x["standards"] for x in rows)/len(rows) if rows else None,"high_days":sum(x["high"] for x in rows),"high_percent":100*sum(x["high"] for x in rows)/len(rows) if rows else None})
    first_start_analysis={"threshold_standards":4,"points":scatter,"bins":bins}
    def association(metric):
        pairs=[(x["first_hour"],x[metric]) for x in scatter]
        coefficient=spearman(pairs)
        absolute=abs(coefficient) if coefficient is not None else 0
        strength="insuffisant" if len(pairs)<15 else "faible" if absolute<.3 else "modéré" if absolute<.6 else "fort"
        direction="aucune" if coefficient is None or absolute<.1 else "plus élevé lorsque la première consommation est plus tardive" if coefficient>0 else "plus élevé lorsque la première consommation est plus précoce"
        return {"coefficient":round(coefficient,3) if coefficient is not None else None,"direction":direction,"strength":strength,"sample_size":len(pairs),"reliable":len(pairs)>=15}
    first_start_analysis["association"]={"standards":association("standards"),"duration_hours":association("duration_hours"),"standards_per_hour":association("standards_per_hour")}
    day_rows=[]
    for day,rows in sorted(drinks_by_day.items()):
        total=sum(x.canadian_standard_drinks for x in rows); first=min(rows,key=lambda x:x.started_at); last=max(rows,key=lambda x:x.ended_at)
        day_rows.append({"date":day,"weekday":date.fromisoformat(day).weekday(),"standards":total,"grams":sum(x.alcohol_grams for x in rows),"duration_hours":max((last.ended_at-first.started_at).total_seconds()/3600,.25)})
    hour_heat=[{"weekday":weekday,"hours":[round(sum(x.alcohol_grams for x in ds if (x.started_at-timedelta(hours=u.day_start_hour)).weekday()==weekday and x.started_at.hour==hour),2) for hour in range(24)]} for weekday in range(7)]
    distribution_bins=[]
    for label,low,high in (("0",0,0.001),("0–2",0.001,2),("2–4",2,4),("4–6",4,6),("6–8",6,8),("8+",8,float("inf"))):
        distribution_bins.append({"label":label,"days":sum(low<=x["standards"]<high for x in day_rows)})
    next_day=[]
    for previous,current in zip(day_rows,day_rows[1:]):
        if date.fromisoformat(current["date"])==date.fromisoformat(previous["date"])+timedelta(days=1):
            next_day.append({"date":previous["date"],"standards":round(previous["standards"],3),"next_standards":round(current["standards"],3)})
    def quartile(values,p):
        if not values:return None
        values=sorted(values); index=(len(values)-1)*p; low=math.floor(index); high=math.ceil(index)
        return values[low] if low==high else values[low]+(values[high]-values[low])*(index-low)
    weekday_box=[]
    for weekday in range(7):
        values=[x["standards"] for x in day_rows if x["weekday"]==weekday]
        weekday_box.append({"weekday":weekday,"days":len(values),"q1":quartile(values,.25),"median":quartile(values,.5),"q3":quartile(values,.75),"maximum":max(values) if values else None})
    advanced_charts={"weekday_hour":hour_heat,"distribution":distribution_bins,"next_day":next_day,"weekday_box":weekday_box}
    abv_groups={}
    def abv_band(abv):
        if abv<5:return "< 5 %"
        if abv<8:return "5–7,9 %"
        if abv<13:return "8–12,9 %"
        if abv<20:return "13–19,9 %"
        return "20 % et +"
    for day,rows in drinks_by_day.items():
        total=sum(x.canadian_standard_drinks for x in rows); high=total>=4
        for drink in rows:
            label=abv_band(drink.abv_percent); group=abv_groups.setdefault(label,{"abv":label,"days":set(),"high_days":set(),"standards":0})
            group["days"].add(day); group["standards"]+=drink.canadian_standard_drinks
            if high:group["high_days"].add(day)
    behavior_abv=[{"abv":x["abv"],"days":len(x["days"]),"high_days":len(x["high_days"]),"high_percent":100*len(x["high_days"])/len(x["days"]) if x["days"] else None,"mean_standards":x["standards"]/len(x["days"])} for x in abv_groups.values()]
    behavior_abv.sort(key=lambda x:(-(x["high_percent"] or 0),-x["days"]))
    advanced_charts["behavior_abv"]=behavior_abv
    return {"tracking_start_date":u.tracking_start_date,"distribution":period_stats(series),"quality":quality,"first_start_analysis":first_start_analysis,
      "charts":advanced_charts,
      "weekly":aggregate_periods(series,"week"),"monthly":aggregate_periods(series,"month"),
      "temporal":temporal_stats(ds),"records":reduction_records(series),
      "sessions_distribution":period_stats([{"grams":x["grams"],"standards":x["standards"],"drinks":x["drink_count"]} for x in session_rows]) if session_rows else period_stats([])}

@app.post("/api/stats/ai-insights")
def ai_insights(u:User=Depends(current_user),db:Session=Depends(get_db)):
    if not settings.openai_api_key: raise HTTPException(503,"Analyse OpenAI non configurée. Ajoutez OPENAI_API_KEY au fichier d’environnement.")
    from openai import OpenAI
    analysis=advanced(u,db)
    payload={"tracking_start_date":analysis["tracking_start_date"],"quality":analysis["quality"],"first_start_analysis":analysis["first_start_analysis"],"charts":{"behavior_abv":analysis["charts"]["behavior_abv"],"weekday_box":analysis["charts"]["weekday_box"]},"weekly":analysis["weekly"][-12:],"monthly":analysis["monthly"][-12:]}
    schema={"type":"object","additionalProperties":False,"properties":{
      "summary":{"type":"string"},"signals":{"type":"array","items":{"type":"object","additionalProperties":False,"properties":{"factor":{"type":"string"},"evidence":{"type":"string"},"confidence":{"type":"string","enum":["faible","modérée","forte","insuffisante"]},"sample_size":{"type":"integer"}},"required":["factor","evidence","confidence","sample_size"]}},
      "experiment":{"type":"object","additionalProperties":False,"properties":{"title":{"type":"string"},"duration_days":{"type":"integer"},"steps":{"type":"array","items":{"type":"string"}},"measure":{"type":"string"},"success_criteria":{"type":"string"}},"required":["title","duration_days","steps","measure","success_criteria"]},
      "caveats":{"type":"array","items":{"type":"string"}}},"required":["summary","signals","experiment","caveats"]}
    instructions=("Tu es un analyste de suivi comportemental prudent. Analyse uniquement les statistiques agrégées fournies. "
      "Ne prétends jamais démontrer une causalité, ne donne aucun conseil médical ou légal, ne gamifie pas les fortes consommations. "
      "Propose une seule expérience comportementale simple, réversible et mesurable sur 7 à 14 jours. "
      "Si l'échantillon est petit, dis clairement que le signal est insuffisant. Réponds en français.")
    try:
        client=OpenAI(api_key=settings.openai_api_key)
        response=client.responses.create(model=settings.openai_model,input=[{"role":"system","content":instructions},{"role":"user","content":json.dumps(payload,ensure_ascii=False,default=str)}],text={"format":{"type":"json_schema","name":"repere_behavior_insights","schema":schema,"strict":True}},store=False)
        result=json.loads(response.output_text)
    except Exception as exc:
        raise HTTPException(502,f"Analyse OpenAI indisponible: {str(exc)[:180]}")
    record=AiInsight(user_id=u.id,provider="openai",model=settings.openai_model,result=result)
    db.add(record);db.commit();db.refresh(record)
    return {"id":record.id,"provider":record.provider,"model":record.model,"created_at":record.created_at,"generated_at":record.created_at.isoformat()+"Z",**result}

@app.get("/api/stats/ai-insights/history")
def ai_insights_history(u:User=Depends(current_user),db:Session=Depends(get_db)):
    rows=db.scalars(select(AiInsight).where(AiInsight.user_id==u.id).order_by(AiInsight.created_at.desc()).limit(20)).all()
    return [{"id":row.id,"provider":row.provider,"model":row.model,"created_at":row.created_at,"generated_at":row.created_at.isoformat()+"Z",**row.result} for row in rows]

@app.get("/api/stats/compare")
def compare(days:int=30,end:date|None=None,u:User=Depends(current_user),db:Session=Depends(get_db)):
    end=end or date.today(); current_start=end-timedelta(days=days-1); previous_end=current_start-timedelta(days=1);previous_start=previous_end-timedelta(days=days-1)
    return {"current_range":[current_start,end],"previous_range":[max(previous_start,u.tracking_start_date) if u.tracking_start_date else previous_start,previous_end],
      **compare_series(daily_series(db,u,current_start,end),daily_series(db,u,previous_start,previous_end))}

@app.get("/api/stats/heatmap")
def heatmap(metric:str="grams",u:User=Depends(current_user),db:Session=Depends(get_db)):
    if metric not in {"grams","standards","drinks"}:raise HTTPException(422,"Métrique invalide")
    series=daily_series(db,u,date.today()-timedelta(days=364),date.today());maximum=max([x[metric] for x in series]+[0])
    return [{**x,"value":x[metric],"intensity":round(x[metric]/maximum*4) if maximum else 0} for x in series]

@app.get("/api/stats/records")
def records(u:User=Depends(current_user),db:Session=Depends(get_db)):
    return reduction_records(daily_series(db,u,u.tracking_start_date or date.today(),date.today()))

@app.get("/api/success")
def success(u:User=Depends(current_user),db:Session=Depends(get_db)):
    series=daily_series(db,u,u.tracking_start_date or date.today(),date.today()); rec=reduction_records(series)
    journals=db.scalar(select(func.count()).select_from(Journal).where(Journal.user_id==u.id)) or 0
    months=aggregate_periods(series,"month"); monthly_drop=rec.get("best_monthly_reduction")
    latest30=period_stats(series[-30:])["grams"]["mean"] if len(series)>=30 else None
    previous30=period_stats(series[-60:-30])["grams"]["mean"] if len(series)>=60 else None
    reduction30=((previous30-latest30)/previous30*100) if previous30 and latest30 is not None else None
    month_reduction=max(0,monthly_drop["reduction_percent"]) if monthly_drop else 0; reduction=max(0,reduction30 or 0);streak=rec["best_alcohol_free_streak"];observed_count=sum(x["observed"] for x in series)
    by_day={date.fromisoformat(x["date"]):x for x in series};weekend_streak=0
    complete_weeks=[x for x in aggregate_periods(series,"week") if x["is_complete"]]
    for week in reversed(complete_weeks):
        monday=date.fromisoformat(week["period_start"]);days=[by_day.get(monday+timedelta(days=i),{"grams":0}) for i in range(7)]
        if all(x["grams"]==0 for x in days[:5]) and any(x["grams"]>0 for x in days[5:]):weekend_streak+=1
        else:break
    definitions=[
      ("first_entry","Premier pas","Première journée renseignée",min(observed_count,1),1,"seedling"),
      ("logged_3","Carnet ouvert","3 journées renseignées",observed_count,3,"tracking"),
      ("logged_7","Une semaine de données","7 journées renseignées",observed_count,7,"tracking"),
      ("logged_14","Deux semaines de données","14 journées renseignées",observed_count,14,"tracking"),
      ("logged_30","Un mois documenté","30 journées renseignées",observed_count,30,"tracking"),
      ("logged_60","Suivi régulier","60 journées renseignées",observed_count,60,"tracking"),
      ("logged_90","Un trimestre documenté","90 journées renseignées",observed_count,90,"tracking"),
      ("logged_180","Six mois de recul","180 journées renseignées",observed_count,180,"tracking"),
      ("logged_365","Une année de données","365 journées renseignées",observed_count,365,"tracking"),
      ("dry_3","Respiration","3 jours consécutifs sans alcool",streak,3,"streak"),
      ("dry_7","Semaine claire","7 jours consécutifs sans alcool",streak,7,"streak"),
      ("dry_14","Cap des deux semaines","14 jours consécutifs sans alcool",streak,14,"streak"),
      ("dry_30","Mois sans alcool","30 jours consécutifs sans alcool",streak,30,"streak"),
      ("reduce_10","Tendance inversée","Moyenne mobile 30 jours réduite d’au moins 10 %",reduction,10,"reduction"),
      ("reduce_25","Virage durable","Moyenne mobile 30 jours réduite d’au moins 25 %",reduction,25,"reduction"),
      ("month_10","Mois en progrès","Diminution mensuelle d’au moins 10 %",month_reduction,10,"calendar"),
      ("journal_7","Prendre du recul","7 journées de contexte consignées",journals,7,"journal"),
      ("journal_30","Journal régulier","30 journées de contexte consignées",journals,30,"journal"),
      ("weekend_2","Guerrier du week-end · 2 semaines","Consommation limitée au samedi et dimanche pendant 2 semaines complètes",weekend_streak,2,"weekend"),
      ("weekend_4","Guerrier du week-end · 1 mois","Consommation limitée au samedi et dimanche pendant 4 semaines complètes",weekend_streak,4,"weekend"),
      ("weekend_8","Guerrier du week-end · 2 mois","Consommation limitée au samedi et dimanche pendant 8 semaines complètes",weekend_streak,8,"weekend"),
      ("weekend_12","Guerrier du week-end · 3 mois","Consommation limitée au samedi et dimanche pendant 12 semaines complètes",weekend_streak,12,"weekend"),
    ]
    badges=[{"id":i,"title":t,"description":d,"unlocked":current>=target,"current":min(current,target),"target":target,"progress_percent":min(100,current/target*100),"category":c} for i,t,d,current,target,c in definitions]
    return {"unlocked_count":sum(x["unlocked"] for x in badges),"total_count":len(badges),"badges":badges,
      "principle":"Les succès récompensent le suivi, les jours sans alcool et la réduction — jamais une forte consommation."}

@app.get("/api/sessions")
def session_list(u:User=Depends(current_user),db:Session=Depends(get_db)):
    ds=db.scalars(select(Drink).where(Drink.user_id==u.id).order_by(Drink.started_at)).all(); rows=sessions(ds,u.session_gap_hours)
    for row in rows:
        members=[d for d in ds if d.started_at>=row["start"] and d.started_at<=row["end"]]
        points=[bac_at(members,u,row["start"]+timedelta(minutes=5*i))[0] for i in range(int((row["end"]-row["start"]).total_seconds()/300)+25)]
        row["peak_bac_percent"]=max(points or [0])
    return rows

@app.get("/api/sessions/day")
def day_sessions(day:date,u:User=Depends(current_user),db:Session=Depends(get_db)):
    start=datetime.combine(day,time.min)+timedelta(hours=u.day_start_hour)
    drinks=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.started_at>=start,Drink.started_at<start+timedelta(days=1)).order_by(Drink.started_at)).all();groups=[]
    for drink in drinks:
        previous=groups[-1]["members"][-1] if groups else None
        explicit=bool(previous and (drink.manual_session_id or previous.manual_session_id))
        joins=bool(previous and ((explicit and drink.manual_session_id==previous.manual_session_id and drink.manual_session_id is not None) or (not explicit and (drink.started_at-previous.ended_at).total_seconds()<=u.session_gap_hours*3600)))
        if not joins:groups.append({"members":[]})
        groups[-1]["members"].append(drink)
    result=[]
    for index,group in enumerate(groups,1):
        members=group["members"];begin=members[0].started_at;finish=max(d.ended_at for d in members);grams=sum(d.alcohol_grams for d in members);standards=sum(d.canadian_standard_drinks for d in members)
        points=[bac_at(members,u,begin+timedelta(minutes=5*i))[0] for i in range(int((finish-begin).total_seconds()/300)+25)]
        result.append({"index":index,"start":begin,"end":finish,"duration_minutes":(finish-begin).total_seconds()/60,"drink_ids":[d.id for d in members],"drink_count":sum(d.quantity for d in members),"grams":grams,"standards":standards,"grams_per_hour":grams/max((finish-begin).total_seconds()/3600,.25),"peak_bac_percent":max(points or [0]),"gap_hours":u.session_gap_hours,"manual":any(d.manual_session_id for d in members)})
    return result

@app.post("/api/sessions/assign")
def assign_sessions(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    groups=payload.get("groups") or []
    ids=[int(item) for group in groups for item in group]
    rows=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.id.in_(ids))).all()
    if len(rows)!=len(set(ids)):raise HTTPException(404,"Une consommation est introuvable")
    by_id={row.id:row for row in rows}
    for group in groups:
        session_id=str(uuid.uuid4())
        for drink_id in group:by_id[int(drink_id)].manual_session_id=session_id
    db.commit();return {"groups":len(groups)}

@app.post("/api/sessions/automatic")
def automatic_sessions(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    ids=[int(value) for value in payload.get("ids",[])]
    rows=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.id.in_(ids))).all()
    for row in rows:row.manual_session_id=None
    db.commit();return {"reset":len(rows)}

@app.get("/api/bac")
def bac(u:User=Depends(current_user),db:Session=Depends(get_db)):
    since=datetime.now()-timedelta(hours=36); ds=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.started_at>=since)).all()
    return bac_projection(ds,u)

@app.get("/api/bac/day")
def bac_day(day:date,u:User=Depends(current_user),db:Session=Depends(get_db)):
    start=datetime.combine(day,time.min)+timedelta(hours=u.day_start_hour);end=start+timedelta(hours=36)
    ds=db.scalars(select(Drink).where(Drink.user_id==u.id,Drink.started_at>=start,Drink.started_at<start+timedelta(days=1)).order_by(Drink.started_at)).all()
    points=[]
    for i in range(0,36*12+1):
        moment=start+timedelta(minutes=5*i);value,remaining,absorbing=bac_at(ds,u,moment)
        points.append({"at":moment,"bac_percent":value,"remaining_grams":remaining,"absorbing":absorbing,"future":moment>datetime.now()})
    peak=max(points,key=lambda point:point["bac_percent"]) if points else None
    last_at=max([d.ended_at for d in ds],default=start);zero=next((point for point in points if point["at"]>last_at and point["bac_percent"]<=.00001 and not point["absorbing"]),None)
    return {"day":day,"start":start,"points":points,"peak":peak,"estimated_zero_at":zero["at"] if zero else None,"drinks":[{"id":d.id,"at":d.started_at,"name":d.drink_name,"grams":d.alcohol_grams} for d in ds],"disclaimer":"Cette valeur est une estimation mathématique. Elle ne constitue pas une mesure réelle de l’alcoolémie et ne doit jamais être utilisée pour déterminer s’il est sécuritaire ou légal de conduire."}

@app.get("/api/goals")
def goals(u:User=Depends(current_user),db:Session=Depends(get_db)):
    goals=db.scalars(select(Goal).where(Goal.user_id==u.id)).all();week=daily_series(db,u,date.today()-timedelta(days=date.today().weekday()),date.today());p=period_stats(week)
    ds=db.scalars(select(Drink).where(Drink.user_id==u.id).order_by(Drink.started_at)).all();session_rows=sessions(ds,u.session_gap_hours)
    months=aggregate_periods(daily_series(db,u,u.tracking_start_date or date.today(),date.today()),"month");monthly_change=months[-1].get("change_percent") if months else None
    recent=daily_series(db,u,max(u.tracking_start_date or date.today(),date.today()-timedelta(days=6)),date.today());observed=[x for x in recent if x["observed"]]
    values={"max_grams_week":p["total_grams"],"max_standards":p["total_standards"],"min_alcohol_free_days":p["alcohol_free_days"],"max_drinking_days":p["alcohol_days"],"max_grams_session":max([x["grams"] for x in session_rows[-20:]] or [0]),"monthly_reduction":max(0,-monthly_change) if monthly_change is not None else None,"max_moving_7_grams":sum(x["grams"] for x in observed)/len(observed) if observed else 0}
    weekly=aggregate_periods(daily_series(db,u,u.tracking_start_date or date.today(),date.today()),"week")
    def streak(goal):
        checks={"max_grams_week":lambda w:w["total_grams"]<=goal.target,"max_standards":lambda w:w["total_standards"]<=goal.target,"min_alcohol_free_days":lambda w:w["alcohol_free_days"]>=goal.target,"max_drinking_days":lambda w:w["alcohol_days"]<=goal.target}
        check=checks.get(goal.kind);count=0
        if not check:return None
        for row in reversed([w for w in weekly if w["is_complete"]]):
            if not check(row):break
            count+=1
        return count
    def met(goal,value):
        if value is None:return None
        return value>=goal.target if goal.kind in {"min_alcohol_free_days","monthly_reduction"} else value<=goal.target
    def history(goal):
        field={"max_grams_week":"total_grams","max_standards":"total_standards","min_alcohol_free_days":"alcohol_free_days","max_drinking_days":"alcohol_days"}.get(goal.kind)
        if field:return [{"period":w["period_start"],"value":w[field],"met":met(goal,w[field])} for w in weekly if w["is_complete"]][-8:]
        if goal.kind=="max_moving_7_grams":
            series=daily_series(db,u,max(u.tracking_start_date or date.today(),date.today()-timedelta(days=34)),date.today());rows=[]
            for i,row in enumerate(series):
                window=[x for x in series[max(0,i-6):i+1] if x["observed"]]
                if window:
                    value=sum(x["grams"] for x in window)/len(window);rows.append({"period":row["date"],"value":value,"met":met(goal,value)})
            return rows[-28:]
        return []
    return [{"id":g.id,"kind":g.kind,"target":g.target,"active":g.active,"current":values.get(g.kind),"on_track":met(g,values.get(g.kind)),"progress_percent":(values[g.kind]/g.target*100 if g.target and g.kind in values else None),"temporal_mode":g.temporal_mode,"consecutive_weeks":g.consecutive_weeks,"consecutive_weeks_achieved":streak(g),"due_date":g.due_date,"days_remaining":max(0,(g.due_date-date.today()).days) if g.due_date else None,"started_on":g.started_on,"history":history(g)} for g in goals]

@app.get("/api/goals/suggestions")
def goal_suggestions(u:User=Depends(current_user),db:Session=Depends(get_db)):
    series=daily_series(db,u,u.tracking_start_date or date.today(),date.today());complete=aggregate_periods(series,"week")
    completed=[x for x in complete if x["is_complete"]][-4:]
    if not completed:return {"basis_weeks":0,"suggestions":[],"message":"Au moins une semaine complète est nécessaire pour personnaliser les propositions."}
    avg=lambda key:statistics.fmean(x[key] for x in completed)
    grams=avg("total_grams");standards=avg("total_standards");drinking=avg("alcohol_days");free=avg("alcohol_free_days")
    recent_observed=[x for x in series[-7:] if x["observed"]]
    moving7=statistics.fmean(x["grams"] for x in recent_observed) if recent_observed else grams/7
    ds=db.scalars(select(Drink).where(Drink.user_id==u.id).order_by(Drink.started_at)).all();session_rows=sessions(ds,u.session_gap_hours);session_peak=max([x["grams"] for x in session_rows[-20:]] or [0])
    suggestions=[
      {"kind":"max_moving_7_grams","target":round(moving7*.9,1),"label":"Maintenir la moyenne mobile 7 jours sous une cible réduite de 10 %","baseline":round(moving7,1),"unit":"g/jour (moyenne 7 j)"},
      {"kind":"max_grams_week","target":round(grams*.9,1),"label":"Réduire les grammes hebdomadaires de 10 %","baseline":round(grams,1),"unit":"g/semaine"},
      {"kind":"max_standards","target":round(standards*.9,1),"label":"Réduire les standards hebdomadaires de 10 %","baseline":round(standards,1),"unit":"standards/semaine"},
      {"kind":"max_drinking_days","target":max(1,math.floor(drinking-.5)),"label":"Limiter le nombre de jours avec alcool","baseline":round(drinking,1),"unit":"jours/semaine"},
      {"kind":"min_alcohol_free_days","target":min(7,max(1,math.ceil(free+.5))),"label":"Augmenter les jours sans alcool","baseline":round(free,1),"unit":"jours/semaine"},
    ]
    if session_peak:suggestions.append({"kind":"max_grams_session","target":round(session_peak*.9,1),"label":"Réduire le maximum par session de 10 %","baseline":round(session_peak,1),"unit":"g/session"})
    return {"basis_weeks":len(completed),"suggestions":suggestions,"message":"Propositions basées sur les semaines complètes récentes; vous restez libre de choisir la cible."}
@app.post("/api/goals")
def add_goal(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    allowed={"max_grams_week","max_standards","min_alcohol_free_days","max_grams_session","monthly_reduction","max_drinking_days","max_moving_7_grams"}
    if payload.get("kind") not in allowed: raise HTTPException(422,"Type d’objectif invalide")
    mode=payload.get("temporal_mode","consecutive_weeks")
    if mode not in {"consecutive_weeks","deadline"}:raise HTTPException(422,"Mode temporel invalide")
    weeks=int(payload.get("consecutive_weeks") or 0) if mode=="consecutive_weeks" else None
    due=date.fromisoformat(payload["due_date"]) if mode=="deadline" and payload.get("due_date") else None
    if mode=="consecutive_weeks" and weeks<1:raise HTTPException(422,"Le nombre de semaines doit être positif")
    if mode=="deadline" and (not due or due<date.today()):raise HTTPException(422,"L’échéance doit être aujourd’hui ou plus tard")
    g=Goal(user_id=u.id,kind=payload["kind"],target=float(payload["target"]),temporal_mode=mode,consecutive_weeks=weeks,due_date=due,started_on=date.today()); db.add(g);db.commit();db.refresh(g);return g

@app.patch("/api/goals/{goal_id}")
def update_goal(goal_id:int,payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    g=db.get(Goal,goal_id)
    if not g or g.user_id!=u.id:raise HTTPException(404)
    if "target" in payload:
        target=float(payload["target"])
        if target<0:raise HTTPException(422,"La cible doit être positive")
        g.target=target
    if "active" in payload:g.active=bool(payload["active"])
    allowed={"max_grams_week","max_standards","min_alcohol_free_days","max_grams_session","monthly_reduction","max_drinking_days","max_moving_7_grams"}
    if "kind" in payload:
        if payload["kind"] not in allowed:raise HTTPException(422,"Type d’objectif invalide")
        g.kind=payload["kind"]
    if "temporal_mode" in payload:
        mode=payload["temporal_mode"]
        if mode not in {"consecutive_weeks","deadline"}:raise HTTPException(422,"Mode temporel invalide")
        g.temporal_mode=mode
        if mode=="consecutive_weeks":
            weeks=int(payload.get("consecutive_weeks") or 0)
            if weeks<1:raise HTTPException(422,"Le nombre de semaines doit être positif")
            g.consecutive_weeks=weeks;g.due_date=None
        else:
            due=date.fromisoformat(payload["due_date"]) if payload.get("due_date") else None
            if not due or due<date.today():raise HTTPException(422,"L’échéance doit être aujourd’hui ou plus tard")
            g.due_date=due;g.consecutive_weeks=None
    db.commit();db.refresh(g);return g

@app.delete("/api/goals/{goal_id}",status_code=204)
def delete_goal(goal_id:int,u:User=Depends(current_user),db:Session=Depends(get_db)):
    g=db.get(Goal,goal_id)
    if not g or g.user_id!=u.id:raise HTTPException(404)
    db.delete(g);db.commit()

@app.get("/api/journal")
def journal(u:User=Depends(current_user),db:Session=Depends(get_db)): return db.scalars(select(Journal).where(Journal.user_id==u.id).order_by(Journal.day.desc())).all()
@app.post("/api/journal")
def upsert_journal(payload:dict,u:User=Depends(current_user),db:Session=Depends(get_db)):
    day=date.fromisoformat(payload["day"]); j=db.scalar(select(Journal).where(Journal.user_id==u.id,Journal.day==day)) or Journal(user_id=u.id,day=day)
    for key in ("mood","stress","fatigue","craving","notes","tags"):
        if key in payload:setattr(j,key,payload[key])
    db.add(j);db.commit();db.refresh(j);return j

@app.get("/api/journal/correlations")
def journal_correlations(u:User=Depends(current_user),db:Session=Depends(get_db)):
    entries=db.scalars(select(Journal).where(Journal.user_id==u.id)).all()
    if not entries:return {"sample_size":0,"correlations":{},"disclaimer":"Corrélation statistique ≠ causalité"}
    totals={x["date"]:x["grams"] for x in daily_series(db,u,min(x.day for x in entries),max(x.day for x in entries))}
    result={}
    for field in ("mood","stress","fatigue","craving"):
        pairs=[(getattr(x,field),totals.get(x.day.isoformat(),0)) for x in entries if getattr(x,field) is not None]
        result[field]={"coefficient":pearson(pairs),"sample_size":len(pairs)}
    return {"sample_size":len(entries),"correlations":result,"disclaimer":"Corrélation statistique ≠ causalité"}

@app.get("/api/export")
def export(format:str="json",u:User=Depends(current_user),db:Session=Depends(get_db)):
    ds=db.scalars(select(Drink).where(Drink.user_id==u.id).order_by(Drink.started_at)).all()
    rows=[{"id":d.id,"name":d.drink_name,"started_at":d.started_at.isoformat(),"duration_minutes":d.duration_minutes,"volume_ml":d.volume_ml,"abv_percent":d.abv_percent,"alcohol_grams":d.alcohol_grams,"standards":d.canadian_standard_drinks,"notes":d.notes} for d in ds]
    if format=="json": return rows
    if format!="csv": raise HTTPException(422,"Format supporté: csv ou json")
    out=io.StringIO(); w=csv.DictWriter(out,fieldnames=rows[0].keys() if rows else ["id"]);w.writeheader();w.writerows(rows)
    return StreamingResponse(iter([out.getvalue()]),media_type="text/csv",headers={"Content-Disposition":"attachment; filename=alcohol-tracker.csv"})

def sqlite_path():
    prefix="sqlite:///"
    if not settings.database_url.startswith(prefix):raise HTTPException(501,"La sauvegarde directe est disponible avec SQLite")
    return settings.database_url[len(prefix):]

@app.get("/api/backup")
def download_backup(u:User=Depends(current_user)):
    source=sqlite3.connect(sqlite_path());handle,path=tempfile.mkstemp(suffix=".sqlite");os.close(handle);target=sqlite3.connect(path)
    try:source.backup(target)
    finally:target.close();source.close()
    filename=f"repere-backup-{datetime.now().strftime('%Y-%m-%d-%H%M')}.sqlite"
    return FileResponse(path,media_type="application/vnd.sqlite3",filename=filename,background=BackgroundTask(os.unlink,path))

@app.post("/api/backup/restore")
async def restore_backup(file:UploadFile=File(...),u:User=Depends(current_user)):
    handle,path=tempfile.mkstemp(suffix=".sqlite");os.close(handle)
    try:
        with open(path,"wb") as output:output.write(await file.read())
        candidate=sqlite3.connect(path)
        if candidate.execute("PRAGMA quick_check").fetchone()[0]!="ok":raise HTTPException(422,"Sauvegarde SQLite corrompue")
        tables={row[0] for row in candidate.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not {"users","drinks","goals","alembic_version"}.issubset(tables):raise HTTPException(422,"Sauvegarde Repère invalide")
        version=candidate.execute("SELECT version_num FROM alembic_version").fetchone()
        if not version or version[0]!="0004":raise HTTPException(422,"Version de sauvegarde incompatible")
        live_path=sqlite_path();backup_dir=Path(settings.data_dir)/"backups";backup_dir.mkdir(parents=True,exist_ok=True)
        current=sqlite3.connect(live_path);safety=sqlite3.connect(backup_dir/f"pre-restore-{datetime.now().strftime('%Y-%m-%d-%H%M%S')}.sqlite")
        current.backup(safety);safety.close();candidate.backup(current);current.close();candidate.close()
        return {"status":"restored","safety_backup":str(backup_dir)}
    finally:
        if os.path.exists(path):os.unlink(path)

frontend=Path(__file__).resolve().parents[2]/"frontend"/"dist"
if frontend.exists():
    app.mount("/assets",StaticFiles(directory=frontend/"assets"),name="assets")
    @app.get("/{path:path}")
    def spa(path:str): return Response((frontend/"index.html").read_text(),media_type="text/html")
