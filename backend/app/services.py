import calendar, csv, hashlib, io, math, statistics
from collections import defaultdict
from datetime import date, datetime, time, timedelta
from sqlalchemy import select
from sqlalchemy.orm import Session
from .models import Drink, ImportBatch, TrackedDay, User

DENSITY = .789
STANDARD_GRAMS = 13.45

def alcohol(volume_ml: float, abv: float, quantity: int = 1):
    grams = volume_ml * abv / 100 * DENSITY * quantity
    return grams, grams / STANDARD_GRAMS

def parse_time(value: str) -> time:
    value = value.strip()
    for fmt in ("%H:%M:%S", "%H:%M", "%I:%M:%S %p", "%I:%M %p"):
        try: return datetime.strptime(value, fmt).time()
        except ValueError: pass
    raise ValueError(f"Heure invalide: {value}")

def parse_date(value: str) -> date:
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%m/%d/%Y"):
        try: return datetime.strptime(value.strip(), fmt).date()
        except ValueError: pass
    raise ValueError(f"Date invalide: {value}")

def key_for(source, external_id, values):
    if external_id: return f"{source}:{external_id}"
    raw = "|".join(str(x).strip().lower() for x in values)
    return f"hash:{hashlib.sha256(raw.encode()).hexdigest()}"

def import_csv(db: Session, user: User, filename: str, content: bytes, source="alco-export"):
    text = content.decode("utf-8-sig")
    sample = text[:4096]
    delimiter = csv.Sniffer().sniff(sample, delimiters=";,").delimiter
    rows = list(csv.DictReader(io.StringIO(text), delimiter=delimiter))
    batch = ImportBatch(user_id=user.id, filename=filename, rows_detected=len(rows))
    db.add(batch); db.flush()
    earliest = None
    errors = []
    required = {"name", "start_date", "start_time", "duration_min", "volume_ml", "abv_pct"}
    if not rows or not required.issubset(rows[0]): raise ValueError("Colonnes CSV requises absentes")
    for i, row in enumerate(rows, 2):
        try:
            day, tm = parse_date(row["start_date"]), parse_time(row["start_time"])
            duration = int(float(row.get("duration_min") or 0))
            volume, abv = float(row["volume_ml"]), float(row["abv_pct"])
            started = datetime.combine(day, tm); grams, standards = alcohol(volume, abv)
            external_id = (row.get("id") or "").strip() or None
            dedupe = key_for(source, external_id, [day, tm, row["name"], volume, abv, duration])
            exists = db.scalar(select(Drink.id).where(Drink.user_id == user.id, Drink.dedupe_key == dedupe))
            if exists: batch.rows_skipped += 1; continue
            cost_raw = (row.get("cost") or "").strip()
            cost = float(cost_raw) if cost_raw else None
            if cost is not None and cost < 0: cost = None
            db.add(Drink(user_id=user.id, drink_name=row["name"], drink_type=None,
                volume_ml=volume, abv_percent=abv, quantity=1, started_at=started,
                ended_at=started + timedelta(minutes=duration), duration_minutes=duration,
                notes=None, cost=cost, source_icon=row.get("glass_icon") or None,
                import_source=source, external_id=external_id, import_batch_id=batch.id,
                dedupe_key=dedupe, alcohol_grams=grams, canadian_standard_drinks=standards))
            batch.rows_imported += 1
            tracked_day=(started-timedelta(hours=user.day_start_hour)).date()
            earliest = min(earliest, tracked_day) if earliest else tracked_day
        except Exception as exc:
            batch.rows_failed += 1; errors.append({"row": i, "error": str(exc)})
    if earliest and (not user.tracking_start_explicit) and (not user.tracking_start_date or earliest < user.tracking_start_date):
        user.tracking_start_date = earliest
    db.commit()
    return {"batch_id": batch.id, "delimiter": delimiter, "rows_detected": len(rows),
            "rows_imported": batch.rows_imported, "rows_skipped": batch.rows_skipped,
            "rows_failed": batch.rows_failed, "errors": errors[:50]}

def daily_series(db: Session, user: User, start: date, end: date):
    tracking = user.tracking_start_date
    if tracking is None: return []
    start = max(start, tracking)
    end = min(end, date.today())
    if start > end: return []
    boundary=timedelta(hours=user.day_start_hour)
    drinks = db.scalars(select(Drink).where(Drink.user_id == user.id,
        Drink.started_at >= datetime.combine(start, time.min)+boundary,
        Drink.started_at < datetime.combine(end + timedelta(days=1), time.min)+boundary)).all()
    tracked=set(db.scalars(select(TrackedDay.day).where(TrackedDay.user_id==user.id,TrackedDay.day>=start,TrackedDay.day<=end)).all())
    totals = defaultdict(lambda: {"grams": 0., "standards": 0., "drinks": 0})
    for d in drinks:
        k=(d.started_at-boundary).date(); totals[k]["grams"] += d.alcohol_grams
        totals[k]["standards"] += d.canadian_standard_drinks; totals[k]["drinks"] += d.quantity
    result=[]
    for i in range((end-start).days+1):
        day=start+timedelta(days=i);values=totals[day]
        status="alcohol" if values["grams"]>0 else "sober" if day in tracked else "no_data"
        result.append({"date":day.isoformat(),"status":status,"observed":status!="no_data",**values})
    return result

def describe(values):
    if not values: return {k: None for k in ("mean","median","q1","q3","p90","stddev","cv","min","max")}
    vals=sorted(values); n=len(vals)
    percentile=lambda p: vals[0] if n==1 else vals[math.floor((n-1)*p)]*(1-((n-1)*p%1))+vals[math.ceil((n-1)*p)]*((n-1)*p%1)
    mean=statistics.fmean(vals); std=statistics.pstdev(vals)
    return {"mean":mean,"median":statistics.median(vals),"q1":percentile(.25),"q3":percentile(.75),
            "p90":percentile(.9),"stddev":std,"cv":std/mean if mean else None,"min":min(vals),"max":max(vals)}

def period_stats(series):
    series=[x for x in series if x.get("status")!="no_data"]
    grams=[x["grams"] for x in series]
    return {"days_observed":len(series),"total_grams":sum(grams),"total_standards":sum(x["standards"] for x in series),
      "total_drinks":sum(x["drinks"] for x in series),"alcohol_days":sum(x["grams"]>0 for x in series),
      "alcohol_free_days":sum(x["grams"]==0 for x in series),
      "alcohol_free_percent":100*sum(x["grams"]==0 for x in series)/len(series) if series else None,
      "grams":describe(grams),"standards":describe([x["standards"] for x in series]),
      "drinks":describe([x["drinks"] for x in series])}

def sessions(drinks, gap_hours=4):
    result=[]
    for d in sorted(drinks, key=lambda x:x.started_at):
        if not result or (d.started_at-result[-1]["end"]).total_seconds()>gap_hours*3600:
            result.append({"start":d.started_at,"end":d.ended_at,"drinks":[],"grams":0.,"standards":0.})
        s=result[-1]; s["drinks"].append(d); s["end"]=max(s["end"],d.ended_at)
        s["grams"]+=d.alcohol_grams; s["standards"]+=d.canadian_standard_drinks
    return [{"start":s["start"],"end":s["end"],"duration_minutes":(s["end"]-s["start"]).total_seconds()/60,
      "drink_count":sum(d.quantity for d in s["drinks"]),"grams":s["grams"],"standards":s["standards"],
      "grams_per_hour":s["grams"]/max((s["end"]-s["start"]).total_seconds()/3600, .25)} for s in result]

def body_r(user):
    """Widmark distribution ratio from sex/height/weight (Watson total body water,
    age assumed 40). Falls back to sex constants, then the stored distribution_ratio."""
    w=user.weight_kg or 75.
    h=getattr(user,"height_cm",None)
    sex=(getattr(user,"sex",None) or "unspecified").lower()
    if h and h>100:
        male=2.447-0.09516*40+0.1074*h+0.3362*w
        female=-2.097+0.1069*h+0.2466*w
        tbw=male if sex=="male" else female if sex=="female" else (male+female)/2
        return max(.4,min(.9,tbw/(w*0.8065)))
    return {"male":.68,"female":.55}.get(sex, user.distribution_ratio or .6)

def bac_at(drinks, user, moment):
    # Each drink's own remaining grams is clamped to zero before summing: elimination keeps growing
    # for as long as a drink is tracked, so an old, fully-metabolized drink must not be able to carry
    # a negative "debt" forward that cancels out a different, unrelated drink's absorption.
    remaining=0.; active=False
    r=body_r(user)
    for d in drinks:
        if moment <= d.started_at: continue
        elapsed_minutes=(moment-d.started_at).total_seconds()/60
        # A still-in-progress drink's stored duration isn't final yet (often still 0), so keep
        # stretching the absorption window to match real elapsed time instead of assuming the
        # whole thing was downed the moment it was logged.
        effective_duration=max(d.duration_minutes,elapsed_minutes) if d.is_active else d.duration_minutes
        absorption_minutes=max(30, effective_duration+30)
        fraction=min(1., elapsed_minutes/absorption_minutes)
        absorbed=d.alcohol_grams*fraction
        if fraction < 1: active=True
        full_at=d.started_at+timedelta(minutes=absorption_minutes)
        elimination_hours=max(0.,(moment-full_at).total_seconds()/3600)
        eliminated=user.elimination_rate*elimination_hours*user.weight_kg*r*10
        remaining += max(0.,absorbed-eliminated)
    bac=max(0.,remaining/(user.weight_kg*1000*r)*100)
    return bac, remaining, active

def bac_projection(drinks, user, now=None):
    now=now or datetime.now(); start=min([d.started_at for d in drinks]+[now])-timedelta(hours=1)
    points=[]
    for i in range(0, 24*12+1):
        moment=start+timedelta(minutes=5*i); bac, remaining, active=bac_at(drinks,user,moment)
        points.append({"at":moment.isoformat(),"bac_percent":bac,"remaining_grams":remaining})
        if moment>now and bac==0 and not active: break
    current, remaining, absorbing=bac_at(drinks,user,now); peak=max(points,key=lambda p:p["bac_percent"])
    future=[p for p in points if p["at"]>=now.isoformat()]
    already_zero=current <= .00001 and not absorbing
    zero=None if already_zero else next((p["at"] for p in future if p["bac_percent"] <= .00001 and p["at"]>now.isoformat()),None)
    future_bac=bac_at(drinks,user,now+timedelta(minutes=10))[0]
    trend="hausse" if future_bac>current+.0001 else "baisse" if future_bac<current-.0001 else "stable"
    return {"current_bac_percent":current,"trend":trend,"peak_bac_percent":peak["bac_percent"],
      "peak_at":peak["at"],"estimated_zero_at":zero,"already_zero":already_zero,"remaining_grams":remaining,"curve":points,
      "disclaimer":"Cette valeur est une estimation mathématique. Elle ne constitue pas une mesure réelle de l’alcoolémie et ne doit jamais être utilisée pour déterminer s’il est sécuritaire ou légal de conduire."}

def aggregate_periods(series, period="week"):
    groups=defaultdict(list)
    for row in series:
        day=date.fromisoformat(row["date"])
        if period=="week":
            key=(day-timedelta(days=day.weekday())).isoformat()
        else:key=day.replace(day=1).isoformat()
        groups[key].append(row)
    result=[]
    for key,rows in sorted(groups.items()):
        p=period_stats(rows); grams=[r["grams"] for r in rows]
        first=date.fromisoformat(rows[0]["date"]);last=date.fromisoformat(rows[-1]["date"])
        fully_observed=all(x.get("status")!="no_data" for x in rows)
        complete=((first.weekday()==0 and last.weekday()==6 and len(rows)==7) if period=="week" else (first.day==1 and last.day==calendar.monthrange(last.year,last.month)[1] and len(rows)==last.day)) and fully_observed
        is_current=period=="week" and last==date.today()
        if period=="week" and not complete and not is_current:continue
        result.append({"period_start":key,"period_end":rows[-1]["date"],"is_complete":complete,"is_current":is_current,"observed_days":len(rows),"_rows":rows,**p,
          "daily_mean":statistics.fmean(grams),"daily_median":statistics.median(grams)})
    for i,row in enumerate(result):
        previous=result[i-1] if i else None
        comparable=bool(previous and previous["is_complete"] and (row["is_complete"] or row["is_current"]))
        if comparable and row["is_current"]:
            previous_total=sum(x["grams"] for x in previous["_rows"][:row["observed_days"]])
            row["comparison_basis"]="same_elapsed_days"
        else:
            previous_total=previous["total_grams"] if comparable else None
            row["comparison_basis"]="full_period" if comparable else None
        row["change_percent"]=((row["total_grams"]-previous_total)/previous_total*100) if previous_total else None
        for count in ((4,12) if period=="week" else (3,12)):
            complete_rows=[x for x in result[:i+1] if x["is_complete"]]
            window=complete_rows[-count:]
            row[f"moving_{count}"]=statistics.fmean(x["total_grams"] for x in window) if row["is_complete"] and window else None
    for row in result:
        row.pop("_rows",None)
    return result

def temporal_stats(drinks):
    weekdays={i:{"grams":0.,"drinks":0,"days":set()} for i in range(7)}
    hours={i:{"grams":0.,"drinks":0} for i in range(24)}
    day_times=defaultdict(list)
    for d in drinks:
        day=d.started_at.date(); wd=weekdays[d.started_at.weekday()]; wd["grams"]+=d.alcohol_grams;wd["drinks"]+=d.quantity;wd["days"].add(day)
        h=hours[d.started_at.hour];h["grams"]+=d.alcohol_grams;h["drinks"]+=d.quantity;day_times[day].append(d.started_at)
    return {"by_weekday":[{"weekday":i,"grams":x["grams"],"drinks":x["drinks"],"active_days":len(x["days"])} for i,x in weekdays.items()],
      "by_hour":[{"hour":i,**x} for i,x in hours.items()],
      "first_drink_times":[min(x).strftime("%H:%M") for x in day_times.values()],
      "last_drink_times":[max(x).strftime("%H:%M") for x in day_times.values()]}

def reduction_records(series):
    if not series:return {"best_alcohol_free_streak":0,"lowest_30_day_average":None,"best_monthly_reduction":None}
    best=run=0
    for r in series:
        run=run+1 if r["grams"]==0 else 0;best=max(best,run)
    averages=[]
    for i in range(29,len(series)):
        averages.append({"date":series[i]["date"],"average":sum(x["grams"] for x in series[i-29:i+1])/30})
    months=aggregate_periods(series,"month"); reductions=[]
    for i in range(1,len(months)):
        if months[i-1]["total_grams"]>0:reductions.append({"month":months[i]["period_start"],"reduction_percent":(months[i-1]["total_grams"]-months[i]["total_grams"])/months[i-1]["total_grams"]*100})
    return {"best_alcohol_free_streak":best,"lowest_30_day_average":min(averages,key=lambda x:x["average"]) if averages else None,
      "best_monthly_reduction":max(reductions,key=lambda x:x["reduction_percent"]) if reductions else None}

def compare_series(first,second):
    a,b=period_stats(first),period_stats(second)
    def delta(key):
        old=b[key];return ((a[key]-old)/old*100) if old else None
    return {"current":a,"previous":b,"change":{"grams_percent":delta("total_grams"),"standards_percent":delta("total_standards"),
      "drinks_percent":delta("total_drinks"),"alcohol_free_days":a["alcohol_free_days"]-b["alcohol_free_days"]}}

def pearson(pairs):
    if len(pairs)<3:return None
    xs=[x for x,_ in pairs];ys=[y for _,y in pairs];mx=statistics.fmean(xs);my=statistics.fmean(ys)
    numerator=sum((x-mx)*(y-my) for x,y in pairs);denominator=math.sqrt(sum((x-mx)**2 for x in xs)*sum((y-my)**2 for y in ys))
    return numerator/denominator if denominator else None

def spearman(pairs):
    if len(pairs)<3:return None
    def ranks(values):
        ordered=sorted((value,index) for index,value in enumerate(values)); result=[0.0]*len(values); pos=0
        while pos<len(ordered):
            end=pos
            while end+1<len(ordered) and ordered[end+1][0]==ordered[pos][0]:end+=1
            rank=(pos+end)/2+1
            for i in range(pos,end+1):result[ordered[i][1]]=rank
            pos=end+1
        return result
    xs,ys=zip(*pairs)
    return pearson(list(zip(ranks(list(xs)),ranks(list(ys)))))
