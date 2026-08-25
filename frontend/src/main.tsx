import React, { useEffect, useState } from "react";
import { createRoot } from "react-dom/client";
import {
  Activity,
  BarChart3,
  ChevronLeft,
  ChevronRight,
  Clock3,
  Copy,
  Moon,
  Pencil,
  Pause,
  Plus,
  Play,
  Settings,
  Smile,
  Target,
  Trophy,
  Sun,
  Upload,
  Trash2,
} from "lucide-react";
import "./styles.css";
import "./advanced.css";
import "./tooltip-fixes.css";
import "./success.css";
import "./success-extra.css";
import "./sober.css";
import "./sober-extra.css";
import "./celebration.css";
import "./sober-input.css";
import "./mood.css";
import "./design-system.css";
type Preset = {
  id: number;
  name: string;
  drink_type: string;
  volume_ml: number;
  abv_percent: number;
};
type View = "now" | "stats" | "success" | "goals" | "settings" | "history";
type QueuedRequest = {
  id: string;
  path: string;
  method: string;
  body?: string;
};
const QUEUE_KEY = "repere-offline-queue";
const readQueue = (): QueuedRequest[] => {
  try {
    return JSON.parse(localStorage.getItem(QUEUE_KEY) || "[]");
  } catch {
    return [];
  }
};
const queueRequest = (path: string, opts: RequestInit, id: string) => {
  const queue = readQueue();
  queue.push({
    id,
    path,
    method: opts.method || "POST",
    body: typeof opts.body === "string" ? opts.body : undefined,
  });
  localStorage.setItem(QUEUE_KEY, JSON.stringify(queue));
  window.dispatchEvent(new Event("repere-queue-change"));
};
const canQueue = (path: string, method: string) =>
  method !== "GET" &&
  (path.startsWith("/drinks") ||
    path.startsWith("/days/sober") ||
    path.startsWith("/journal"));
const api = async (path: string, opts: RequestInit = {}) => {
  let r: Response;
  const method = opts.method || "GET";
  const requestId = canQueue(path, method) ? crypto.randomUUID() : "";
  try {
    r = await fetch("/api" + path, {
      ...opts,
      headers: {
        "Content-Type": "application/json",
        ...(requestId ? { "Idempotency-Key": requestId } : {}),
        ...opts.headers,
      },
    });
  } catch (error) {
    if (canQueue(path, method)) {
      queueRequest(path, opts, requestId);
      return { queued: true };
    }
    throw error;
  }
  if (!r.ok) {
    let m = `Erreur ${r.status}`;
    try {
      const b = await r.json();
      m = typeof b.detail === "string" ? b.detail : b.detail?.[0]?.msg || m;
    } catch {}
    throw Error(m);
  }
  return r.status === 204 ? null : r.json();
};
function Login({ done }: { done: () => Promise<void> }) {
  const [u, setU] = useState(""),
    [p, setP] = useState(""),
    [e, setE] = useState("");
  const go = async (x: string) => {
    if (p.length < 8)
      return setE("Le mot de passe doit contenir au moins 8 caractères.");
    try {
      await api(x, {
        method: "POST",
        body: JSON.stringify({ username: u, password: p }),
      });
      await done();
    } catch (z) {
      setE(z instanceof Error ? z.message : "Erreur");
    }
  };
  return (
    <main className="shell">
      <div className="card login">
        <div className="loginbrand">
          <img src="/icon.svg" alt="" />
          <h1>Repère</h1>
        </div>
        <input
          placeholder="Utilisateur"
          value={u}
          onChange={(e) => setU(e.target.value)}
        />
        <input
          type="password"
          placeholder="Mot de passe (8 caractères min.)"
          value={p}
          onChange={(e) => setP(e.target.value)}
        />
        {e && <p className="error">{e}</p>}
        <div className="actions">
          <button className="ghost" onClick={() => go("/auth/register")}>
            Créer un compte
          </button>
          <button className="add" onClick={() => go("/auth/login")}>
            Connexion
          </button>
        </div>
      </div>
    </main>
  );
}
function App() {
  const [ready, setReady] = useState(false),
    [auth, setAuth] = useState(
      localStorage.getItem("repere-has-session") === "true",
    ),
    [view, setView] = useState<View>("now"),
    [presets, setPresets] = useState<Preset[]>(() =>
      JSON.parse(localStorage.getItem("repere-presets") || "[]"),
    ),
    [stats, setStats] = useState<any>(() =>
      JSON.parse(localStorage.getItem("repere-stats") || "null"),
    ),
    [bac, setBac] = useState<any>(() =>
      JSON.parse(localStorage.getItem("repere-bac") || "null"),
    ),
    [modal, setModal] = useState<Preset | null>(null),
    [addMenu, setAddMenu] = useState(false),
    [moodModal, setMoodModal] = useState(false),
    [selectedDate, setSelectedDate] = useState(
      new Date().toISOString().slice(0, 10),
    ),
    [online, setOnline] = useState(navigator.onLine),
    [queued, setQueued] = useState(readQueue().length),
    [theme, setTheme] = useState<"light" | "dark">(() => {
      const saved = localStorage.getItem("repere-theme");
      if (saved === "light" || saved === "dark") return saved;
      return window.matchMedia("(prefers-color-scheme: dark)").matches
        ? "dark"
        : "light";
    });
  const load = async () => {
    try {
      await api("/auth/me");
      setAuth(true);
      localStorage.setItem("repere-has-session", "true");
      const [p, s, b] = await Promise.all([
        api("/presets"),
        api("/stats?days=30"),
        api("/bac"),
      ]);
      setPresets(p);
      setStats(s);
      setBac(b);
      localStorage.setItem("repere-presets", JSON.stringify(p));
      localStorage.setItem("repere-stats", JSON.stringify(s));
      localStorage.setItem("repere-bac", JSON.stringify(b));
    } catch {
      if (navigator.onLine) setAuth(false);
    } finally {
      setReady(true);
    }
  };
  useEffect(() => {
    load();
  }, []);
  useEffect(() => {
    const sync = async () => {
      setOnline(navigator.onLine);
      if (!navigator.onLine) return;
      const queue = readQueue();
      const remaining = [...queue];
      for (const item of queue) {
        try {
          const response = await fetch(`/api${item.path}`, {
            method: item.method,
            headers: {
              "Content-Type": "application/json",
              "Idempotency-Key": item.id,
            },
            body: item.body,
          });
          if (
            !response.ok &&
            !(item.method === "DELETE" && response.status === 404)
          )
            break;
          remaining.shift();
          localStorage.setItem(QUEUE_KEY, JSON.stringify(remaining));
          setQueued(remaining.length);
        } catch {
          break;
        }
      }
      if (queue.length && remaining.length === 0) await load();
    };
    const changed = () => setQueued(readQueue().length);
    window.addEventListener("online", sync);
    window.addEventListener("offline", sync);
    window.addEventListener("repere-queue-change", changed);
    sync();
    return () => {
      window.removeEventListener("online", sync);
      window.removeEventListener("offline", sync);
      window.removeEventListener("repere-queue-change", changed);
    };
  }, []);
  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    document.documentElement.style.colorScheme = theme;
    localStorage.setItem("repere-theme", theme);
  }, [theme]);
  if (!ready) return null;
  if (!auth) return <Login done={load} />;
  const titles = {
    now: "Tableau personnel",
    stats: "Statistiques",
    success: "Succès",
    goals: "Objectifs",
    settings: "Réglages",
    history: "Historique",
  };
  return (
    <>
      <main className="shell">
        <header className="top">
          <div className="brand">
            <img className="mark" src="/icon.svg" alt="" />
            <div>
              <b>Repère</b>
              <div className="muted">{titles[view]}</div>
            </div>
          </div>
          <div className="topactions">
            {(!online || queued > 0) && (
              <span
                className={`networkstate ${online ? "syncing" : "offline"}`}
              >
                {online ? `${queued} en attente` : "Hors ligne"}
              </span>
            )}
            <button
              className="iconbutton"
              title={theme === "dark" ? "Thème clair" : "Thème sombre"}
              aria-label={
                theme === "dark"
                  ? "Passer au thème clair"
                  : "Passer au thème sombre"
              }
              onClick={() => setTheme(theme === "dark" ? "light" : "dark")}
            >
              {theme === "dark" ? <Sun size={20} /> : <Moon size={20} />}
            </button>
          </div>
        </header>
        {view === "now" ? (
          <Dashboard
            stats={stats}
            bac={bac}
            refresh={load}
            selectedDate={selectedDate}
            setSelectedDate={setSelectedDate}
            add={() => setAddMenu(true)}
            mood={() => setMoodModal(true)}
          />
        ) : view === "stats" ? (
          <Stats stats={stats} />
        ) : view === "success" ? (
          <Success />
        ) : view === "goals" ? (
          <Goals />
        ) : view === "history" ? (
          <History />
        ) : (
          <Prefs
            stats={stats}
            refresh={load}
            logout={async () => {
              await api("/auth/logout", { method: "POST" });
              localStorage.removeItem("repere-has-session");
              setAuth(false);
            }}
            openHistory={() => setView("history")}
          />
        )}
      </main>
      <nav className="nav">
        {(
          [
            ["now", Activity, "Maintenant"],
            ["stats", BarChart3, "Stats"],
            ["success", Trophy, "Succès"],
            ["goals", Target, "Objectifs"],
            ["settings", Settings, "Réglages"],
          ] as const
        ).map(([id, Icon, label]) => (
          <button
            key={id}
            className={view === id ? "active" : ""}
            onClick={() => setView(id)}
          >
            <Icon size={17} />
            <span>{label}</span>
          </button>
        ))}
      </nav>
      {modal && (
        <DrinkSheet
          preset={modal}
          day={selectedDate}
          close={() => setModal(null)}
          saved={() => {
            setModal(null);
            load();
          }}
        />
      )}
      {addMenu && (
        <AddMenu
          presets={presets}
          close={() => setAddMenu(false)}
          choose={(preset) => {
            setAddMenu(false);
            setModal(preset);
          }}
        />
      )}
      {moodModal && (
        <MoodModal day={selectedDate} close={() => setMoodModal(false)} />
      )}
    </>
  );
}
const Metric = ({ label, value }: { label: React.ReactNode; value: any }) => (
  <div className="metric">
    <span className="muted">{label}</span>
    <strong>{value}</strong>
  </div>
);
function Dashboard({
  stats,
  bac,
  refresh,
  selectedDate,
  setSelectedDate,
  add,
  mood,
}: {
  stats: any;
  bac: any;
  refresh: () => Promise<void>;
  selectedDate: string;
  setSelectedDate: (day: string) => void;
  add: () => void;
  mood: () => void;
}) {
  const [celebration, setCelebration] = useState<any>();
  const [selectedDayStatus, setSelectedDayStatus] = useState("no_data");
  const [selectedDayDrinks, setSelectedDayDrinks] = useState<any[]>([]);
  const [editingDrink, setEditingDrink] = useState<any>();
  const [copyingDrink, setCopyingDrink] = useState<any>();
  const [pendingDelete, setPendingDelete] = useState<any>();
  const [bacDay, setBacDay] = useState<any>();
  const [sessionCards, setSessionCards] = useState<any[]>([]);
  useEffect(() => {
    Promise.all([
      api(`/days?start=${selectedDate}&end=${selectedDate}`),
      api(`/drinks?day=${selectedDate}`),
      api(`/bac/day?day=${selectedDate}`),
      api(`/sessions/day?day=${selectedDate}`),
    ]).then(([rows, drinks, bacHistory, dailySessions]) => {
      setSelectedDayStatus(rows[0]?.status || "no_data");
      setSelectedDayDrinks(drinks);
      setBacDay(bacHistory);
      setSessionCards(dailySessions);
    });
  }, [selectedDate, stats]);
  const moveDay = (offset: number) => {
    const day = new Date(`${selectedDate}T12:00:00`);
    day.setDate(day.getDate() + offset);
    setSelectedDate(day.toISOString().slice(0, 10));
  };
  const copyDrink = async (drink: any, targetDay: string, move = false) => {
    const time = String(drink.started_at).slice(11, 19) || "12:00:00";
    await api("/drinks", {
      method: "POST",
      body: JSON.stringify({
        drink_type: drink.drink_type,
        drink_name: drink.drink_name,
        volume_ml: drink.volume_ml,
        abv_percent: drink.abv_percent,
        quantity: drink.quantity,
        started_at: `${targetDay}T${time}`,
        duration_minutes: drink.duration_minutes,
        notes: drink.notes,
        cost: drink.cost,
      }),
    });
    if (move) await api(`/drinks/${drink.id}`, { method: "DELETE" });
    await refresh();
  };
  const scheduleDelete = (drink: any) => {
    setSelectedDayDrinks((rows) =>
      rows.filter((row: any) => row.id !== drink.id),
    );
    void api(`/drinks/${drink.id}`, { method: "DELETE" });
    const timer = window.setTimeout(() => setPendingDelete(undefined), 10000);
    if (pendingDelete) {
      window.clearTimeout(pendingDelete.timer);
    }
    setPendingDelete({ drink, timer });
  };
  const undoDelete = async () => {
    const drink = pendingDelete?.drink;
    if (!drink) return;
    window.clearTimeout(pendingDelete.timer);
    await api("/drinks", {
      method: "POST",
      body: JSON.stringify({
        drink_type: drink.drink_type,
        drink_name: drink.drink_name,
        volume_ml: drink.volume_ml,
        abv_percent: drink.abv_percent,
        quantity: drink.quantity,
        started_at: drink.started_at,
        duration_minutes: drink.duration_minutes,
        notes: drink.notes,
        cost: drink.cost,
      }),
    });
    setPendingDelete(undefined);
    await refresh();
  };
  const reloadSessions = async () =>
    setSessionCards(await api(`/sessions/day?day=${selectedDate}`));
  const assignSessionGroups = async (groups: number[][]) => {
    await api("/sessions/assign", {
      method: "POST",
      body: JSON.stringify({ groups }),
    });
    await reloadSessions();
  };
  const p = stats?.period || {},
    days = stats?.days || [],
    chartMaximum = Math.max(...days.map((day: any) => day.grams), 1),
    timelineDrinks = [...selectedDayDrinks].sort(
      (a, b) =>
        new Date(a.started_at).getTime() - new Date(b.started_at).getTime(),
    );
  return (
    <div className="grid">
      <section className="card hero">
        <div className="eyebrow">Maintenant · estimation</div>
        <div className="bac">
          {((bac?.current_bac_percent || 0) * 100).toFixed(2)}{" "}
          <small>g/L</small>
        </div>
        <span className="trend">
          <Activity size={15} />
          {bac?.trend}
        </span>
        <div className="metrics">
          <Metric
            label="Pic"
            value={((bac?.peak_bac_percent || 0) * 100).toFixed(2)}
          />
          <Metric
            label="Retour à 0"
            value={
              bac?.already_zero
                ? "Déjà à 0"
                : bac?.estimated_zero_at
                  ? new Date(bac.estimated_zero_at).toLocaleTimeString(
                      "fr-CA",
                      {
                        hour: "2-digit",
                        minute: "2-digit",
                      },
                    )
                  : "—"
            }
          />
        </div>
        <p className="warning">{bac?.disclaimer}</p>
      </section>
      <section className="card today">
        <div className="eyebrow">30 derniers jours observés</div>
        <h2>Votre rythme</h2>
        <div className="metrics">
          <Metric
            label="Total"
            value={`${(p.total_grams || 0).toFixed(0)} g`}
          />
          <Metric
            label="Moyenne / jour"
            value={`${(p.grams?.mean || 0).toFixed(1)} g`}
          />
          <Metric label="Sans alcool" value={p.alcohol_free_days || 0} />
        </div>
        <div className="bars">
          {days.map((d: any, i: number) => (
            <div
              key={i}
              className={`bar has-tip ${d.status}`}
              tabIndex={0}
              data-tip={`${d.date} · ${d.status === "no_data" ? "aucune donnée" : d.status === "sober" ? "journée sobre · 0 g" : `${d.grams.toFixed(1)} g · ${d.standards.toFixed(2)} standards · ${d.drinks} consommation${d.drinks > 1 ? "s" : ""}`}`}
              style={{
                height: `${d.grams === 0 ? 2 : (d.grams / chartMaximum) * 100}%`,
              }}
            />
          ))}
        </div>
      </section>
      <section className="card full daily">
        <div className="eyebrow">Suivi quotidien</div>
        <div className="daynav">
          <button
            className="iconbutton"
            onClick={() => moveDay(-1)}
            aria-label="Journée précédente"
          >
            <ChevronLeft size={20} />
          </button>
          <label>
            <span>
              {new Date(`${selectedDate}T12:00:00`).toLocaleDateString(
                "fr-CA",
                {
                  weekday: "long",
                  day: "numeric",
                  month: "long",
                },
              )}
            </span>
            <input
              type="date"
              max={new Date().toISOString().slice(0, 10)}
              value={selectedDate}
              onChange={(e) => setSelectedDate(e.target.value)}
            />
          </label>
          <button
            className="iconbutton"
            onClick={() => moveDay(1)}
            disabled={selectedDate >= new Date().toISOString().slice(0, 10)}
            aria-label="Journée suivante"
          >
            <ChevronRight size={20} />
          </button>
        </div>
        <div className="dayactions">
          <button
            className="moodbutton"
            onClick={mood}
            aria-label="Consigner mon état"
          >
            <Smile size={20} />
          </button>
          <button className="add" onClick={add}>
            <Plus size={17} /> Ajouter à cette journée
          </button>
        </div>
        <div className="daymetrics">
          <Metric
            label="Consommations"
            value={selectedDayDrinks.reduce(
              (sum, drink) => sum + drink.quantity,
              0,
            )}
          />
          <Metric
            label="Alcool pur"
            value={`${selectedDayDrinks.reduce((sum, drink) => sum + drink.alcohol_grams, 0).toFixed(1)} g`}
          />
          <Metric
            label="Standards"
            value={selectedDayDrinks
              .reduce((sum, drink) => sum + drink.canadian_standard_drinks, 0)
              .toFixed(2)}
          />
        </div>
        {sessionCards.length > 0 && (
          <div className="sessioncards">
            {sessionCards.map((session, index) => (
              <article key={session.drink_ids.join("-")}>
                <div className="sessioncardhead">
                  <div>
                    <div className="eyebrow">
                      Session {session.index}
                      {session.manual ? " · manuelle" : ""}
                    </div>
                    <h3>
                      {new Date(session.start).toLocaleTimeString("fr-CA", {
                        hour: "2-digit",
                        minute: "2-digit",
                      })}{" "}
                      –{" "}
                      {new Date(session.end).toLocaleTimeString("fr-CA", {
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                    </h3>
                  </div>
                  <span>{Math.round(session.duration_minutes)} min</span>
                </div>
                <div className="sessionmetrics">
                  <span>
                    <b>{session.drink_count}</b> consommations
                  </span>
                  <span>
                    <b>{session.standards.toFixed(2)}</b> standards
                  </span>
                  <span>
                    <b>{session.grams_per_hour.toFixed(1)}</b> g/h
                  </span>
                  <span>
                    <b>{(session.peak_bac_percent * 100).toFixed(2)}</b> g/L pic
                  </span>
                </div>
                <div className="sessionactions">
                  {index > 0 && (
                    <button
                      className="ghost"
                      onClick={() =>
                        assignSessionGroups([
                          [
                            ...sessionCards[index - 1].drink_ids,
                            ...session.drink_ids,
                          ],
                        ])
                      }
                    >
                      Fusionner avec la précédente
                    </button>
                  )}
                  {session.drink_ids.length > 1 && (
                    <button
                      className="ghost"
                      onClick={() =>
                        assignSessionGroups([
                          session.drink_ids.slice(0, -1),
                          session.drink_ids.slice(-1),
                        ])
                      }
                    >
                      Séparer la dernière
                    </button>
                  )}
                  {session.manual && (
                    <button
                      className="ghost"
                      onClick={async () => {
                        await api("/sessions/automatic", {
                          method: "POST",
                          body: JSON.stringify({ ids: session.drink_ids }),
                        });
                        await reloadSessions();
                      }}
                    >
                      Calcul automatique
                    </button>
                  )}
                </div>
              </article>
            ))}
          </div>
        )}
        {selectedDayDrinks.length > 0 && (
          <div className="daydrinks timeline">
            {timelineDrinks.map((drink, index) => {
              const newSession =
                index === 0 ||
                new Date(drink.started_at).getTime() -
                  new Date(timelineDrinks[index - 1].ended_at).getTime() >
                  4 * 3600000;
              const sessionNumber = timelineDrinks
                .slice(0, index + 1)
                .filter(
                  (row, rowIndex, all) =>
                    rowIndex === 0 ||
                    new Date(row.started_at).getTime() -
                      new Date(all[rowIndex - 1].ended_at).getTime() >
                      4 * 3600000,
                ).length;
              return (
                <React.Fragment key={drink.id}>
                  {newSession && (
                    <div className="sessiondivider">
                      Session {sessionNumber} · début{" "}
                      {new Date(drink.started_at).toLocaleTimeString("fr-CA", {
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                    </div>
                  )}
                  <div>
                    <i className="timelinepoint" aria-hidden="true" />
                    <span>
                      <b>{drink.drink_name}</b>
                      <small>
                        <Clock3 size={13} />{" "}
                        {new Date(drink.started_at).toLocaleTimeString(
                          "fr-CA",
                          {
                            hour: "2-digit",
                            minute: "2-digit",
                          },
                        )}{" "}
                        · {drink.duration_minutes} min · {drink.volume_ml} ml ·{" "}
                        {drink.abv_percent}%
                      </small>
                      <small>
                        {drink.canadian_standard_drinks.toFixed(2)} standards ·
                        cumul{" "}
                        {timelineDrinks
                          .slice(0, index + 1)
                          .reduce(
                            (total, row) =>
                              total + row.canadian_standard_drinks,
                            0,
                          )
                          .toFixed(2)}
                      </small>
                    </span>
                    <strong>{drink.alcohol_grams.toFixed(1)} g</strong>
                    <span className="drinkactions">
                      <button
                        className="iconbutton"
                        aria-label={`Dupliquer ${drink.drink_name}`}
                        title="Dupliquer dans cette journée"
                        onClick={() => copyDrink(drink, selectedDate)}
                      >
                        <Plus size={16} />
                      </button>
                      <button
                        className="iconbutton"
                        aria-label={`Copier ${drink.drink_name} vers une autre date`}
                        title="Copier vers une date"
                        onClick={() => setCopyingDrink(drink)}
                      >
                        <Copy size={16} />
                      </button>
                      <button
                        className="iconbutton"
                        aria-label={`Modifier ${drink.drink_name}`}
                        onClick={() => setEditingDrink(drink)}
                      >
                        <Pencil size={16} />
                      </button>
                      <button
                        className="iconbtn"
                        aria-label={`Supprimer ${drink.drink_name}`}
                        onClick={() => scheduleDelete(drink)}
                      >
                        <Trash2 size={16} />
                      </button>
                    </span>
                  </div>
                </React.Fragment>
              );
            })}
          </div>
        )}
        {bacDay && selectedDayDrinks.length > 0 && (
          <BacDayChart data={bacDay} />
        )}
        <div className="soberaction">
          <div>
            <b>Enregistrer une journée sobre</b>
            <span className="muted">
              État :{" "}
              {selectedDayStatus === "sober"
                ? "journée sobre"
                : selectedDayStatus === "alcohol"
                  ? "consommation enregistrée"
                  : "aucune donnée"}
            </span>
          </div>
          {selectedDayStatus === "sober" ? (
            <button
              className="dangerghost"
              onClick={async () => {
                setSelectedDayStatus("no_data");
                await api(`/days/sober/${selectedDate}`, { method: "DELETE" });
                await refresh();
              }}
            >
              Annuler la journée sobre
            </button>
          ) : (
            <button
              className="ghost"
              disabled={selectedDayStatus === "alcohol"}
              onClick={async () => {
                const result = await api("/days/sober", {
                  method: "POST",
                  body: JSON.stringify({ date: selectedDate }),
                });
                if (!result.queued) setCelebration(result);
                setSelectedDayStatus("sober");
                await refresh();
              }}
            >
              Marquer comme sobre
            </button>
          )}
        </div>
      </section>
      <section className="card third">
        <div className="eyebrow">Suivi actif depuis</div>
        <h2>{stats?.tracking_start_date || "Non défini"}</h2>
      </section>
      <section className="card third">
        <div className="eyebrow">Jours observés</div>
        <h2>{p.days_observed || 0}</h2>
      </section>
      <section className="card third">
        <div className="eyebrow">Standards</div>
        <h2>{(p.total_standards || 0).toFixed(1)}</h2>
      </section>
      {celebration && (
        <SoberCelebration
          data={celebration}
          close={() => setCelebration(null)}
        />
      )}
      {editingDrink && (
        <DrinkSheet
          preset={{
            id: editingDrink.id,
            name: editingDrink.drink_name,
            drink_type: editingDrink.drink_type || "",
            volume_ml: editingDrink.volume_ml,
            abv_percent: editingDrink.abv_percent,
          }}
          day={selectedDate}
          drink={editingDrink}
          close={() => setEditingDrink(undefined)}
          saved={async () => {
            setEditingDrink(undefined);
            await refresh();
          }}
        />
      )}
      {copyingDrink && (
        <CopyDrinkModal
          drink={copyingDrink}
          initialDay={selectedDate}
          close={() => setCopyingDrink(undefined)}
          submit={async (day, move) => {
            await copyDrink(copyingDrink, day, move);
            setCopyingDrink(undefined);
          }}
        />
      )}
      {pendingDelete && (
        <div className="undotoast">
          <span>Consommation supprimée</span>
          <button onClick={undoDelete}>Annuler</button>
        </div>
      )}
    </div>
  );
}
function BacDayChart({ data }: { data: any }) {
  const [selectedIndex, setSelectedIndex] = useState<number | null>(null);
  const rows = (data.points || []).filter(
      (_: any, index: number) => index % 6 === 0,
    ),
    maximum = Math.max(...rows.map((row: any) => row.bac_percent), 0.001),
    pointString = (values: any[], offset = 0) =>
      values
        .map(
          (row: any, index: number) =>
            `${((index + offset) / Math.max(1, rows.length - 1)) * 100},${52 - (row.bac_percent / maximum) * 46}`,
        )
        .join(" "),
    futureIndex = rows.findIndex((row: any) => row.future),
    pastRows = futureIndex < 0 ? rows : rows.slice(0, futureIndex + 1),
    futureRows =
      futureIndex < 0 ? [] : rows.slice(Math.max(0, futureIndex - 1)),
    futureOffset = futureIndex < 0 ? 0 : Math.max(0, futureIndex - 1),
    selected = selectedIndex == null ? null : rows[selectedIndex],
    startMs = rows.length ? new Date(rows[0].at).getTime() : 0,
    endMs = rows.length ? new Date(rows[rows.length - 1].at).getTime() : 1,
    pick = (event: React.PointerEvent<HTMLDivElement>) => {
      const rect = event.currentTarget.getBoundingClientRect();
      const ratio = Math.max(
        0,
        Math.min(1, (event.clientX - rect.left) / rect.width),
      );
      setSelectedIndex(Math.round(ratio * Math.max(0, rows.length - 1)));
    };
  return (
    <div className="bacday">
      <div className="sectionhead">
        <div>
          <div className="eyebrow">Estimation de la journée</div>
          <h3>Courbe BAC</h3>
        </div>
        <span className="muted">
          Pic {((data.peak?.bac_percent || 0) * 100).toFixed(2)} g/L ·{" "}
          {data.estimated_zero_at
            ? `retour à 0 vers ${new Date(data.estimated_zero_at).toLocaleTimeString("fr-CA", { hour: "2-digit", minute: "2-digit" })}`
            : "retour à 0 non calculé"}
        </span>
      </div>
      <div
        className="bacchartplot"
        onPointerMove={pick}
        onPointerDown={pick}
        onPointerLeave={() => setSelectedIndex(null)}
      >
        <svg viewBox="0 0 100 56" preserveAspectRatio="none">
          <polyline className="bacpast" points={pointString(pastRows)} />
          {futureRows.length > 0 && (
            <polyline
              className="bacfuture"
              points={pointString(futureRows, futureOffset)}
            />
          )}
          {(data.drinks || []).map((drink: any) => {
            const x =
              ((new Date(drink.at).getTime() - startMs) /
                Math.max(1, endMs - startMs)) *
              100;
            return (
              <line
                key={drink.id}
                className="bacdrinkmark"
                x1={x}
                x2={x}
                y1="3"
                y2="53"
              />
            );
          })}
        </svg>
        {selected && (
          <>
            <i
              className="baccursor"
              style={{
                left: `${(selectedIndex! / Math.max(1, rows.length - 1)) * 100}%`,
              }}
            />
            <div
              className="bactooltip"
              style={{
                left: `${(selectedIndex! / Math.max(1, rows.length - 1)) * 100}%`,
              }}
            >
              <b>
                {new Date(selected.at).toLocaleTimeString("fr-CA", {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </b>
              <span>{(selected.bac_percent * 100).toFixed(2)} g/L</span>
              <span>
                {selected.remaining_grams.toFixed(1)} g estimés dans l’organisme
              </span>
              <small>
                {selected.future ? "Projection" : "Estimation passée"}
              </small>
            </div>
          </>
        )}
        {(data.drinks || []).map((drink: any) => (
          <span
            key={drink.id}
            className="bacdrinklabel"
            style={{
              left: `${((new Date(drink.at).getTime() - startMs) / Math.max(1, endMs - startMs)) * 100}%`,
            }}
            title={`${drink.name} · ${drink.grams.toFixed(1)} g`}
          >
            +
          </span>
        ))}
      </div>
      <div className="bactimeaxis">
        <span>
          {rows[0]
            ? new Date(rows[0].at).toLocaleTimeString("fr-CA", {
                hour: "2-digit",
              })
            : ""}
        </span>
        <span>
          {rows[Math.floor(rows.length / 2)]
            ? new Date(rows[Math.floor(rows.length / 2)].at).toLocaleTimeString(
                "fr-CA",
                { hour: "2-digit" },
              )
            : ""}
        </span>
        <span>
          {rows.at(-1)
            ? new Date(rows.at(-1).at).toLocaleTimeString("fr-CA", {
                hour: "2-digit",
              })
            : ""}
        </span>
      </div>
      <p className="warning">{data.disclaimer}</p>
    </div>
  );
}
function SoberCelebration({ data, close }: { data: any; close: () => void }) {
  return (
    <div className="celebration" role="dialog" aria-modal="true">
      <div className="fireworks" aria-hidden="true">
        {Array.from({ length: 28 }, (_, i) => (
          <i key={i} style={{ "--i": i } as React.CSSProperties} />
        ))}
      </div>
      <div className="celebratecard">
        <div className="trophybig">
          <Trophy size={38} />
        </div>
        <div className="eyebrow">Journée sobre enregistrée</div>
        <h1>
          {data.current_sober_streak} jour
          {data.current_sober_streak > 1 ? "s" : ""} sobre
          {data.current_sober_streak > 1 ? "s" : ""} d’affilée
        </h1>
        {data.next_streak_target && (
          <p>
            Prochain jalon : <b>{data.next_streak_target} jours</b>
          </p>
        )}
        <div className="healthtimeline">
          {data.health_milestones.map((m: any) => (
            <div
              key={m.hours}
              className={
                data.current_sober_streak * 24 >= m.hours ? "reached" : ""
              }
            >
              <strong>{m.label}</strong>
              <span>{m.text}</span>
            </div>
          ))}
        </div>
        <p className="warning">{data.health_note}</p>
        <p className="sources">
          Sources :{" "}
          {data.sources.map((s: any, i: number) => (
            <React.Fragment key={s.url}>
              {i > 0 ? " · " : ""}
              <a href={s.url} target="_blank" rel="noreferrer">
                {s.label}
              </a>
            </React.Fragment>
          ))}
        </p>
        <button className="add" onClick={close}>
          Continuer
        </button>
      </div>
    </div>
  );
}
function ImportPage({ refresh }: { refresh: () => Promise<void> }) {
  const [f, setF] = useState<File | null>(null),
    [rows, setRows] = useState<string[][]>([]),
    [sep, setSep] = useState(";"),
    [result, setResult] = useState<any>(),
    [hist, setHist] = useState<any[]>([]),
    [err, setErr] = useState("");
  const reload = () => api("/import/history").then(setHist);
  useEffect(() => {
    reload();
  }, []);
  const choose = async (file?: File) => {
    if (!file) return;
    setF(file);
    const t = await file.text(),
      h = t.split(/\r?\n/)[0] || "",
      s =
        (h.match(/;/g) || []).length >= (h.match(/,/g) || []).length
          ? ";"
          : ",";
    setSep(s);
    setRows(
      t
        .split(/\r?\n/)
        .filter(Boolean)
        .slice(0, 6)
        .map((x) => x.split(s)),
    );
  };
  const send = async () => {
    if (!f) return;
    const d = new FormData();
    d.append("file", f);
    const r = await fetch("/api/import", { method: "POST", body: d }),
      b = await r.json();
    if (!r.ok) return setErr(b.detail || "Import impossible");
    setResult(b);
    await Promise.all([reload(), refresh()]);
  };
  const undo = async (id: number) => {
    if (confirm("Annuler cet import ?")) {
      await api(`/import/history/${id}`, { method: "DELETE" });
      await Promise.all([reload(), refresh()]);
    }
  };
  return (
    <div className="grid">
      <section className="card full">
        <div className="eyebrow">Assistant CSV</div>
        <h1>Importer des consommations</h1>
        <p className="muted">
          id;name;start_date;start_time;duration_min;volume_ml;abv_pct;cost;glass_icon
        </p>
        <label className="drop">
          <Upload />
          <b>{f ? f.name : "Sélectionner un CSV"}</b>
          <span>; ou , · AM/PM accepté</span>
          <input
            type="file"
            accept=".csv"
            onChange={(e) => choose(e.target.files?.[0])}
          />
        </label>
        {f && (
          <>
            <p>
              Séparateur détecté : <b>{sep}</b>
            </p>
            <Table rows={rows} />
            <div className="actions">
              <button className="add" onClick={send}>
                Valider et importer
              </button>
            </div>
          </>
        )}
        {err && <p className="error">{err}</p>}
        {result && (
          <div className="report">
            <b>Terminé</b>
            <span>{result.rows_imported} importées</span>
            <span>{result.rows_skipped} doublons</span>
            <span>{result.rows_failed} échecs</span>
          </div>
        )}
      </section>
      <section className="card full">
        <h2>Historique des imports</h2>
        {hist.length ? (
          <div className="tablewrap">
            <table>
              <tbody>
                {hist.map((h) => (
                  <tr key={h.id}>
                    <td>{h.filename}</td>
                    <td>{h.rows_imported} importées</td>
                    <td>{h.rows_skipped} ignorées</td>
                    <td>
                      <button className="iconbtn" onClick={() => undo(h.id)}>
                        <Trash2 size={17} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="muted">Aucun import.</p>
        )}
      </section>
    </div>
  );
}
function Table({ rows }: { rows: string[][] }) {
  return (
    <div className="tablewrap">
      <table>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>
              {r.map((c, j) =>
                i ? <td key={j}>{c}</td> : <th key={j}>{c}</th>,
              )}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Stats({ stats }: { stats: any }) {
  const [data, setData] = useState<any>(),
    [heat, setHeat] = useState<any[]>([]),
    [sessions, setSessions] = useState<any[]>([]),
    [trends, setTrends] = useState<any>(),
    [goals, setGoals] = useState<any[]>([]),
    [distributionMetric, setDistributionMetric] = useState<
      "standards" | "grams"
    >("standards");
  useEffect(() => {
    Promise.all([
      api("/stats/advanced"),
      api("/stats/heatmap"),
      api("/sessions"),
      api("/stats/trends"),
      api("/goals"),
    ]).then(([a, h, s, t, g]) => {
      setData(a);
      setHeat(h);
      setSessions(s);
      setTrends(t);
      setGoals(g);
    });
  }, [stats]);
  if (!data) return <div className="card">Chargement des analyses…</div>;
  const p = data.distribution?.[distributionMetric] || {},
    weekly = data.weekly || [],
    monthly = data.monthly || [],
    temporal = data.temporal || {},
    distributionHelp: Record<string, string> = {
      Moyenne:
        "Votre niveau moyen par journée observée. Elle sert à suivre l’évolution générale, mais peut être tirée vers le haut par quelques journées très élevées.",
      Médiane:
        "Votre journée la plus représentative : la moitié des journées est en dessous et l’autre moitié au-dessus. Utile lorsque quelques épisodes élevés déforment la moyenne.",
      "Quartile 1":
        "25 % des journées observées sont à ce niveau ou moins. Peut servir de repère réaliste pour vos journées de plus faible consommation.",
      "Quartile 3":
        "75 % des journées observées sont à ce niveau ou moins. Au-dessus, vous entrez dans le quart de vos journées les plus élevées.",
      P90: "90 % des journées observées sont à ce niveau ou moins. Les valeurs supérieures correspondent à vos 10 % de journées les plus élevées et peuvent aider à repérer les épisodes exceptionnels.",
      "Écart-type":
        "Indique à quel point vos journées varient autour de la moyenne. Une valeur faible signifie un rythme stable; une valeur élevée indique des écarts importants d’une journée à l’autre.",
      "Coeff. variation":
        "Rapporte la variabilité à votre moyenne. Pratique pour voir si votre rythme devient plus régulier même lorsque votre niveau moyen change.",
      Minimum:
        "Votre plus faible journée réellement observée. Les journées sobres explicitement consignées peuvent donc donner une valeur de zéro.",
      Maximum:
        "Votre journée observée la plus élevée. Sert à identifier l’ampleur de votre pic historique, sans en faire un record à battre.",
    };
  return (
    <div className="grid">
      <section className="card full">
        <div className="sectionhead">
          <div>
            <div className="eyebrow">Depuis {data.tracking_start_date}</div>
            <h1>Distribution complète</h1>
          </div>
          <div className="segmented">
            <button
              className={distributionMetric === "standards" ? "active" : ""}
              onClick={() => setDistributionMetric("standards")}
            >
              Standards
            </button>
            <button
              className={distributionMetric === "grams" ? "active" : ""}
              onClick={() => setDistributionMetric("grams")}
            >
              Grammes
            </button>
          </div>
        </div>
        <p className="muted">
          Par journée observée · 1 consommation standard canadienne = 13,45 g
          d’alcool pur.
        </p>
        <div className="metrics">
          {[
            ["Moyenne", p.mean],
            ["Médiane", p.median],
            ["Quartile 1", p.q1],
            ["Quartile 3", p.q3],
            ["P90", p.p90],
            ["Écart-type", p.stddev],
            ["Coeff. variation", p.cv],
            ["Minimum", p.min],
            ["Maximum", p.max],
          ].map(([l, v]) => (
            <Metric
              key={l}
              label={
                <>
                  {String(l)}
                  <span
                    className="helpmark has-tip"
                    tabIndex={0}
                    data-tip={distributionHelp[String(l)]}
                  >
                    ?
                  </span>
                </>
              }
              value={
                v == null
                  ? "—"
                  : `${(+v).toFixed(2)}${l === "Coeff. variation" ? "" : distributionMetric === "standards" ? " standards" : " g"}`
              }
            />
          ))}
        </div>
      </section>
      {trends && <MovingChart trends={trends} goals={goals} />}
      <section className="card full">
        <div className="eyebrow">Heatmap · grammes</div>
        <h2>365 derniers jours observables</h2>
        <div className="heatmap">
          {heat.map((x) => (
            <span
              key={x.date}
              className={`heat h${x.intensity} has-tip ${x.status}`}
              tabIndex={0}
              data-tip={`${x.date} · ${x.status === "no_data" ? "aucune donnée" : x.status === "sober" ? "journée sobre · 0 g" : `${x.grams.toFixed(1)} g · ${x.standards.toFixed(2)} standards · ${x.drinks} consommation${x.drinks > 1 ? "s" : ""}`}`}
            />
          ))}
        </div>
      </section>
      <section className="card full">
        <div className="eyebrow">Hebdomadaire</div>
        <h2>Évolution par semaine</h2>
        <PeriodTable rows={weekly.slice(-12)} type="week" />
      </section>
      <section className="card full">
        <div className="eyebrow">Mensuel</div>
        <h2>Évolution par mois</h2>
        <PeriodTable rows={monthly.slice(-12)} type="month" />
      </section>
      <section className="card today">
        <div className="eyebrow">Sessions · écart configuré</div>
        <h2>{sessions.length} sessions</h2>
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Alcool pur</th>
                <th>Consommations</th>
                <th>Standards</th>
                <th>BAC maximal</th>
              </tr>
            </thead>
            <tbody>
              {sessions
                .slice(-8)
                .reverse()
                .map((s: any) => (
                  <tr key={s.start}>
                    <td>{new Date(s.start).toLocaleDateString("fr-CA")}</td>
                    <td>{s.grams.toFixed(1)} g</td>
                    <td>
                      {s.drink_count} consommation
                      {s.drink_count > 1 ? "s" : ""}
                    </td>
                    <td>{s.standards.toFixed(2)}</td>
                    <td>{(s.peak_bac_percent * 100).toFixed(2)} g/L</td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      </section>
      <section className="card hero">
        <div className="eyebrow">Records orientés réduction</div>
        <h2>Progrès utiles</h2>
        <Metric
          label="Meilleure séquence sans alcool"
          value={`${data.records.best_alcohol_free_streak} jours`}
        />
        <Metric
          label="Plus faible moyenne 30 jours"
          value={
            data.records.lowest_30_day_average
              ? `${data.records.lowest_30_day_average.average.toFixed(1)} g`
              : "—"
          }
        />
        <Metric
          label="Meilleure diminution mensuelle"
          value={
            data.records.best_monthly_reduction
              ? `${data.records.best_monthly_reduction.reduction_percent.toFixed(0)} %`
              : "—"
          }
        />
      </section>
      <section className="card full">
        <div className="eyebrow">Répartition temporelle</div>
        <h2>Grammes par jour de semaine</h2>
        <div className="weekday">
          {(temporal.by_weekday || []).map((x: any) => (
            <div key={x.weekday}>
              <span>
                {["Lun", "Mar", "Mer", "Jeu", "Ven", "Sam", "Dim"][x.weekday]}
              </span>
              <b>{x.grams.toFixed(0)} g</b>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
function MovingChart({ trends, goals }: { trends: any; goals: any[] }) {
  const [observed, setObserved] = useState("90"),
    [metric, setMetric] = useState<"grams" | "standards">("grams"),
    allRows = trends.moving_averages["7"] || [],
    rows = observed === "all" ? allRows : allRows.slice(-Number(observed)),
    applicableGoals = goals.filter(
      (goal) =>
        metric === "grams" && goal.kind === "max_moving_7_grams" && goal.active,
    ),
    maximum = Math.max(
      ...rows.flatMap((x: any) => [
        x[metric],
        x[metric === "grams" ? "daily_grams" : "daily_standards"],
      ]),
      ...applicableGoals.map((goal) => goal.target),
      0.01,
    ),
    points = rows
      .map(
        (x: any, i: number) =>
          `${(i / Math.max(1, rows.length - 1)) * 100},${52 - (x[metric] / maximum) * 48}`,
      )
      .join(" ");
  return (
    <section className="card full">
      <div className="sectionhead">
        <div>
          <div className="eyebrow">Tendance lissée</div>
          <h2>Moyenne mobile 7 jours</h2>
        </div>
        <div className="segmented">
          <button
            className={metric === "grams" ? "active" : ""}
            onClick={() => setMetric("grams")}
          >
            Grammes
          </button>
          <button
            className={metric === "standards" ? "active" : ""}
            onClick={() => setMetric("standards")}
          >
            Standards
          </button>
          {["30", "90", "180", "365", "all"].map((value) => (
            <button
              key={value}
              className={observed === value ? "active" : ""}
              onClick={() => setObserved(value)}
            >
              {value === "all" ? "Tout" : `${value} j`}
            </button>
          ))}
        </div>
      </div>
      <div className="linechart">
        <div className="chartaxis" aria-hidden="true">
          <span>{maximum.toFixed(metric === "grams" ? 0 : 1)}</span>
          <span>{(maximum / 2).toFixed(metric === "grams" ? 0 : 1)}</span>
          <span>0</span>
        </div>
        <div className="trendbars" aria-hidden="true">
          {rows.map((x: any) => (
            <i
              key={x.date}
              className={x.status}
              style={{
                height: `${(x[metric === "grams" ? "daily_grams" : "daily_standards"] / maximum) * 85.7142}%`,
              }}
            />
          ))}
        </div>
        <svg viewBox="0 0 100 56" preserveAspectRatio="none">
          <polyline points={points} />
          {applicableGoals.map((goal) => (
            <line
              key={goal.id}
              className="goalline"
              x1="0"
              x2="100"
              y1={52 - (goal.target / maximum) * 48}
              y2={52 - (goal.target / maximum) * 48}
            />
          ))}
        </svg>
        {rows.map((x: any, i: number) => (
          <span
            key={x.date}
            tabIndex={0}
            className="chartpoint has-tip"
            data-tip={`${x.date} · consommation : ${x[metric === "grams" ? "daily_grams" : "daily_standards"].toFixed(metric === "grams" ? 1 : 2)} ${metric === "grams" ? "g" : "standards"} · moyenne mobile 7 j : ${x[metric].toFixed(metric === "grams" ? 1 : 2)}${applicableGoals.length ? ` · objectif : ${applicableGoals.map((goal) => goal.target).join(", ")} g` : ""}`}
            style={{
              left: `${(i / Math.max(1, rows.length - 1)) * 100}%`,
              bottom: `${7.1429 + (x[metric] / maximum) * 85.7142}%`,
            }}
          />
        ))}
      </div>
      <div className="chartlegend">
        <span>
          <i className="dailykey" /> Grammes par jour
        </span>
        <span>
          <i className="movingkey" /> Moyenne mobile 7 jours
        </span>
        {applicableGoals.map((goal) => (
          <span key={goal.id}>
            <i className="goalkey" /> Objectif {goal.target} g
          </span>
        ))}
      </div>
    </section>
  );
}
function PeriodTable({ rows, type }: { rows: any[]; type: string }) {
  return (
    <div className="tablewrap">
      <table>
        <thead>
          <tr>
            <th>Période</th>
            <th>Total</th>
            <th>Moy./jour</th>
            <th>Sans alcool</th>
            <th>Évolution</th>
            <th>Moy. glissante</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r) => (
            <tr key={r.period_start} className={r.is_complete ? "" : "partial"}>
              <td>
                {r.period_start}
                {r.is_current && <small> en cours</small>}
              </td>
              <td>{r.total_grams.toFixed(1)} g</td>
              <td>{r.daily_mean.toFixed(1)} g</td>
              <td>
                {r.alcohol_free_days} (
                {(r.alcohol_free_percent || 0).toFixed(0)}%)
              </td>
              <td>
                {r.change_percent == null
                  ? "—"
                  : `${r.change_percent > 0 ? "+" : ""}${r.change_percent.toFixed(0)}%${r.comparison_basis === "same_elapsed_days" ? " à date" : ""}`}
              </td>
              <td>
                {r[type === "week" ? "moving_4" : "moving_3"] == null
                  ? "—"
                  : `${r[type === "week" ? "moving_4" : "moving_3"].toFixed(1)} g`}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function Success() {
  const [data, setData] = useState<any>();
  useEffect(() => {
    api("/success").then(setData);
  }, []);
  if (!data) return <div className="card">Chargement des succès…</div>;
  return (
    <div className="grid">
      <section className="card full successhero">
        <Trophy size={34} />
        <div>
          <div className="eyebrow">Progression positive</div>
          <h1>
            {data.unlocked_count} / {data.total_count} badges obtenus
          </h1>
          <p className="muted">{data.principle}</p>
        </div>
      </section>
      <section className="card full">
        <div className="badgegrid">
          {data.badges.map((badge: any) => (
            <article
              key={badge.id}
              className={`badge ${badge.unlocked ? "unlocked" : "locked"}`}
            >
              <div className="badgeicon">
                <Trophy size={22} />
              </div>
              <div>
                <h3>{badge.title}</h3>
                <p>{badge.description}</p>
                <progress max="100" value={badge.progress_percent} />
                <small>
                  {badge.unlocked
                    ? "Obtenu"
                    : `${Number(badge.current).toFixed(badge.category === "reduction" || badge.category === "calendar" ? 1 : 0)} / ${badge.target}`}
                </small>
              </div>
            </article>
          ))}
        </div>
      </section>
    </div>
  );
}
function Goals() {
  const [goals, setGoals] = useState<any[]>([]),
    [suggestions, setSuggestions] = useState<any>(),
    [kind, setKind] = useState("max_drinking_days"),
    [target, setTarget] = useState(2),
    [temporalMode, setTemporalMode] = useState<
      "consecutive_weeks" | "deadline"
    >("consecutive_weeks"),
    [weeks, setWeeks] = useState(3),
    [dueDate, setDueDate] = useState(""),
    [editingGoal, setEditingGoal] = useState<any>();
  const reload = () =>
    Promise.all([
      api("/goals").then(setGoals),
      api("/goals/suggestions").then(setSuggestions),
    ]);
  useEffect(() => {
    reload();
  }, []);
  const add = async (k = kind, t = target) => {
    await api("/goals", {
      method: "POST",
      body: JSON.stringify({
        kind: k,
        target: t,
        temporal_mode: temporalMode,
        consecutive_weeks: temporalMode === "consecutive_weeks" ? weeks : null,
        due_date: temporalMode === "deadline" ? dueDate : null,
      }),
    });
    await reload();
  };
  const labels: any = {
    max_moving_7_grams: "Maintenir la moyenne mobile 7 jours sous un maximum",
    max_grams_week: "Maximum de grammes par semaine",
    max_standards: "Maximum de standards par semaine",
    min_alcohol_free_days: "Minimum de jours sobres par semaine",
    max_grams_session: "Maximum de grammes par session",
    monthly_reduction: "Réduction mensuelle",
    max_drinking_days: "Maximum de jours avec alcool par semaine",
  };
  return (
    <div className="grid">
      <section className="card full">
        <div className="eyebrow">Objectifs actifs</div>
        <h1>Mes objectifs</h1>
        {goals.length ? (
          <div className="goalgrid">
            {goals.map((g) => (
              <article
                className={`metric goalcard ${g.active ? "" : "paused"}`}
                key={g.id}
              >
                <div className="goalhead">
                  <span>{labels[g.kind] || g.kind}</span>
                  <span className={`goalstatus ${g.on_track ? "met" : "over"}`}>
                    {!g.active
                      ? "En pause"
                      : g.on_track
                        ? "Dans la cible"
                        : "Hors cible"}
                  </span>
                </div>
                <strong>
                  {g.current == null ? "—" : g.current.toFixed(1)} / {g.target}
                </strong>
                {g.progress_percent != null && (
                  <progress
                    max="100"
                    value={Math.min(100, g.progress_percent)}
                  />
                )}
                <small className="goaltime">
                  {g.temporal_mode === "deadline"
                    ? `Échéance : ${g.due_date} · ${g.days_remaining} jour${g.days_remaining > 1 ? "s" : ""} restant${g.days_remaining > 1 ? "s" : ""}`
                    : `${g.consecutive_weeks_achieved ?? 0} / ${g.consecutive_weeks} semaines consécutives`}
                </small>
                {g.history?.length > 0 && (
                  <>
                    <div className="goalhistory" aria-label="Historique récent">
                      {g.history.map((entry: any) => (
                        <i
                          key={entry.period}
                          className={entry.met ? "met" : "missed"}
                          title={`${entry.period} · ${entry.value.toFixed(1)} · ${entry.met ? "cible respectée" : "cible dépassée"}`}
                        />
                      ))}
                    </div>
                    <GoalHistorySummary history={g.history} />
                  </>
                )}
                <div className="goalactions">
                  <button
                    className="iconbutton"
                    title="Modifier l’objectif"
                    onClick={() => setEditingGoal(g)}
                  >
                    <Pencil size={15} />
                  </button>
                  <button
                    className="iconbutton"
                    title={g.active ? "Mettre en pause" : "Reprendre"}
                    onClick={async () => {
                      await api(`/goals/${g.id}`, {
                        method: "PATCH",
                        body: JSON.stringify({ active: !g.active }),
                      });
                      await reload();
                    }}
                  >
                    {g.active ? <Pause size={15} /> : <Play size={15} />}
                  </button>
                  <button
                    className="iconbtn"
                    onClick={async () => {
                      await api(`/goals/${g.id}`, { method: "DELETE" });
                      reload();
                    }}
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <p className="muted">Aucun objectif actif.</p>
        )}
      </section>
      <section className="card full">
        <div className="eyebrow">
          Basées sur {suggestions?.basis_weeks || 0} semaines complètes
        </div>
        <h2>Propositions personnalisées</h2>
        <p className="muted">{suggestions?.message}</p>
        <div className="suggestgrid">
          {(suggestions?.suggestions || []).map((s: any) => (
            <article className="suggestion" key={s.kind}>
              <h3>{s.label}</h3>
              <p>
                Base : {s.baseline} {s.unit}
              </p>
              <strong>
                Cible : {s.target} {s.unit}
              </strong>
              <button className="add" onClick={() => add(s.kind, s.target)}>
                Fixer cet objectif
              </button>
            </article>
          ))}
        </div>
      </section>
      <section className="card full">
        <div className="eyebrow">Objectif personnalisé</div>
        <h2>Définir ma propre cible</h2>
        <div className="form">
          <label>
            Type
            <select value={kind} onChange={(e) => setKind(e.target.value)}>
              {Object.entries(labels).map(([k, v]) => (
                <option key={k} value={k}>
                  {String(v)}
                </option>
              ))}
            </select>
          </label>
          <label>
            Cible
            <input
              type="number"
              min="0"
              step=".1"
              value={target}
              onChange={(e) => setTarget(+e.target.value)}
            />
          </label>
          <label>
            Durée de l’objectif
            <select
              value={temporalMode}
              onChange={(e) =>
                setTemporalMode(
                  e.target.value as "consecutive_weeks" | "deadline",
                )
              }
            >
              <option value="consecutive_weeks">Semaines consécutives</option>
              <option value="deadline">D’ici une date</option>
            </select>
          </label>
          {temporalMode === "consecutive_weeks" ? (
            <label>
              Nombre de semaines
              <input
                type="number"
                min="1"
                max="52"
                value={weeks}
                onChange={(e) => setWeeks(+e.target.value)}
              />
            </label>
          ) : (
            <label>
              Date d’échéance
              <input
                type="date"
                min={new Date().toISOString().slice(0, 10)}
                value={dueDate}
                onChange={(e) => setDueDate(e.target.value)}
              />
            </label>
          )}
        </div>
        <div className="actions">
          <button className="add" onClick={() => add()}>
            Ajouter l’objectif
          </button>
        </div>
      </section>
      {editingGoal && (
        <GoalEditor
          goal={editingGoal}
          labels={labels}
          close={() => setEditingGoal(undefined)}
          saved={async () => {
            setEditingGoal(undefined);
            await reload();
          }}
        />
      )}
    </div>
  );
}
function GoalHistorySummary({ history }: { history: any[] }) {
  const success = history.filter((entry) => entry.met).length;
  let current = 0,
    best = 0,
    running = 0;
  for (const entry of history) {
    running = entry.met ? running + 1 : 0;
    best = Math.max(best, running);
  }
  for (const entry of [...history].reverse()) {
    if (!entry.met) break;
    current += 1;
  }
  return (
    <div className="goalhistorysummary">
      <span>{Math.round((success / history.length) * 100)} % réussies</span>
      <span>Série : {current}</span>
      <span>Meilleure : {best}</span>
    </div>
  );
}
function GoalEditor({
  goal,
  labels,
  close,
  saved,
}: {
  goal: any;
  labels: Record<string, string>;
  close: () => void;
  saved: () => Promise<void>;
}) {
  const [kind, setKind] = useState(goal.kind),
    [target, setTarget] = useState(goal.target),
    [mode, setMode] = useState<"consecutive_weeks" | "deadline">(
      goal.temporal_mode,
    ),
    [weeks, setWeeks] = useState(goal.consecutive_weeks || 3),
    [dueDate, setDueDate] = useState(goal.due_date || ""),
    [error, setError] = useState("");
  const submit = async () => {
    try {
      await api(`/goals/${goal.id}`, {
        method: "PATCH",
        body: JSON.stringify({
          kind,
          target,
          temporal_mode: mode,
          consecutive_weeks: mode === "consecutive_weeks" ? weeks : null,
          due_date: mode === "deadline" ? dueDate : null,
        }),
      });
      await saved();
    } catch (reason) {
      setError(
        reason instanceof Error ? reason.message : "Modification impossible",
      );
    }
  };
  return (
    <div className="modal" onClick={close}>
      <div className="sheet" onClick={(event) => event.stopPropagation()}>
        <div className="eyebrow">Objectif existant</div>
        <h2>Modifier l’objectif</h2>
        <div className="form">
          <label className="wide">
            Type
            <select
              value={kind}
              onChange={(event) => setKind(event.target.value)}
            >
              {Object.entries(labels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label>
            Cible
            <input
              type="number"
              min="0"
              step=".1"
              value={target}
              onChange={(event) => setTarget(+event.target.value)}
            />
          </label>
          <label>
            Échéancier
            <select
              value={mode}
              onChange={(event) =>
                setMode(event.target.value as "consecutive_weeks" | "deadline")
              }
            >
              <option value="consecutive_weeks">Semaines consécutives</option>
              <option value="deadline">D’ici une date</option>
            </select>
          </label>
          {mode === "consecutive_weeks" ? (
            <label className="wide">
              Nombre de semaines
              <input
                type="number"
                min="1"
                max="52"
                value={weeks}
                onChange={(event) => setWeeks(+event.target.value)}
              />
            </label>
          ) : (
            <label className="wide">
              Date d’échéance
              <input
                type="date"
                min={new Date().toISOString().slice(0, 10)}
                value={dueDate}
                onChange={(event) => setDueDate(event.target.value)}
              />
            </label>
          )}
        </div>
        {error && <p className="error">{error}</p>}
        <div className="actions">
          <button className="ghost" onClick={close}>
            Annuler
          </button>
          <button className="add" onClick={submit}>
            Enregistrer les changements
          </button>
        </div>
      </div>
    </div>
  );
}
function History() {
  const [rows, setRows] = useState<any[]>([]),
    [query, setQuery] = useState(""),
    [start, setStart] = useState(""),
    [end, setEnd] = useState(""),
    [loading, setLoading] = useState(false);
  const search = async () => {
    setLoading(true);
    const params = new URLSearchParams();
    if (query) params.set("q", query);
    if (start) params.set("start", start);
    if (end) params.set("end", end);
    params.set("limit", "1000");
    setRows(await api(`/drinks?${params}`));
    setLoading(false);
  };
  useEffect(() => {
    search();
  }, []);
  return (
    <div className="grid">
      <section className="card full">
        <div className="eyebrow">Toutes les données</div>
        <h1>Historique des consommations</h1>
        <div className="form">
          <label className="wide">
            Recherche
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="Nom de la consommation"
            />
          </label>
          <label>
            Du
            <input
              type="date"
              value={start}
              onChange={(event) => setStart(event.target.value)}
            />
          </label>
          <label>
            Au
            <input
              type="date"
              value={end}
              onChange={(event) => setEnd(event.target.value)}
            />
          </label>
        </div>
        <div className="actions">
          <button className="add" onClick={search}>
            {loading ? "Recherche…" : "Rechercher"}
          </button>
        </div>
        <div className="tablewrap">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Consommation</th>
                <th>Volume</th>
                <th>Standards</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((drink) => (
                <tr key={drink.id}>
                  <td>
                    {new Date(drink.started_at).toLocaleString("fr-CA", {
                      dateStyle: "short",
                      timeStyle: "short",
                    })}
                  </td>
                  <td>{drink.drink_name}</td>
                  <td>
                    {drink.volume_ml} ml · {drink.abv_percent}%
                  </td>
                  <td>{drink.canadian_standard_drinks.toFixed(2)}</td>
                  <td>
                    <button
                      className="iconbtn"
                      onClick={async () => {
                        if (
                          !window.confirm(`Supprimer « ${drink.drink_name} » ?`)
                        )
                          return;
                        await api(`/drinks/${drink.id}`, { method: "DELETE" });
                        await search();
                      }}
                    >
                      <Trash2 size={16} />
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
function Prefs({
  stats,
  refresh,
  logout,
  openHistory,
}: {
  stats: any;
  refresh: () => Promise<void>;
  logout: () => void;
  openHistory: () => void;
}) {
  const [d, setD] = useState(stats?.tracking_start_date || ""),
    [hour, setHour] = useState(8),
    [weight, setWeight] = useState(75),
    [ratio, setRatio] = useState(0.68),
    [elim, setElim] = useState(0.015),
    [gap, setGap] = useState(4),
    [currentPassword, setCurrentPassword] = useState(""),
    [newPassword, setNewPassword] = useState(""),
    [accountMessage, setAccountMessage] = useState("");
  useEffect(() => {
    api("/auth/me").then((x) => {
      setHour(x.day_start_hour);
      setWeight(x.weight_kg);
      setRatio(x.distribution_ratio);
      setElim(x.elimination_rate);
      setGap(x.session_gap_hours);
    });
  }, []);
  const save = async () => {
    await api("/settings", {
      method: "PATCH",
      body: JSON.stringify({
        tracking_start_date: d,
        day_start_hour: hour,
        weight_kg: weight,
        distribution_ratio: ratio,
        elimination_rate: elim,
        session_gap_hours: gap,
      }),
    });
    await refresh();
  };
  const changePassword = async () => {
    try {
      await api("/auth/change-password", {
        method: "POST",
        body: JSON.stringify({
          current_password: currentPassword,
          new_password: newPassword,
        }),
      });
      localStorage.removeItem("repere-has-session");
      setAccountMessage("Mot de passe modifié. Reconnexion nécessaire.");
      window.setTimeout(() => window.location.reload(), 900);
    } catch (reason) {
      setAccountMessage(
        reason instanceof Error ? reason.message : "Modification impossible",
      );
    }
  };
  const restore = async (file?: File) => {
    if (
      !file ||
      !window.confirm(
        "Restaurer cette sauvegarde remplacera les données actuelles. Une copie de sécurité sera créée. Continuer ?",
      )
    )
      return;
    const form = new FormData();
    form.append("file", file);
    const response = await fetch("/api/backup/restore", {
      method: "POST",
      body: form,
    });
    if (!response.ok) {
      const body = await response.json();
      setAccountMessage(body.detail || "Restauration impossible");
      return;
    }
    window.location.reload();
  };
  return (
    <div className="grid">
      <section className="card full">
        <div className="eyebrow">Paramètres de calcul</div>
        <h1>Réglages</h1>
        <div className="form">
          <label>
            Date de début du suivi
            <input
              type="date"
              value={d}
              onChange={(e) => setD(e.target.value)}
            />
          </label>
          <label>
            Début de la journée
            <input
              type="time"
              step="3600"
              value={`${String(hour).padStart(2, "0")}:00`}
              onChange={(e) => setHour(+e.target.value.split(":")[0])}
            />
          </label>
          <label>
            Poids (kg)
            <input
              type="number"
              value={weight}
              onChange={(e) => setWeight(+e.target.value)}
            />
          </label>
          <label>
            Ratio de distribution
            <input
              type="number"
              step=".01"
              value={ratio}
              onChange={(e) => setRatio(+e.target.value)}
            />
          </label>
          <label>
            Élimination (%/h)
            <input
              type="number"
              step=".001"
              value={elim}
              onChange={(e) => setElim(+e.target.value)}
            />
          </label>
          <label>
            Écart entre sessions (h)
            <input
              type="number"
              step=".5"
              value={gap}
              onChange={(e) => setGap(+e.target.value)}
            />
          </label>
        </div>
        <p className="muted">
          À {String(hour).padStart(2, "0")}:00, une nouvelle journée statistique
          commence; elle se termine le lendemain juste avant cette heure.
        </p>
        <div className="actions">
          <button className="ghost" onClick={openHistory}>
            Historique complet
          </button>
          <button className="ghost" onClick={logout}>
            Déconnexion
          </button>
          <button className="add" onClick={save}>
            Enregistrer
          </button>
        </div>
      </section>
      <section className="card full">
        <div className="eyebrow">Compte local</div>
        <h2>Sécurité</h2>
        <div className="form">
          <label>
            Mot de passe actuel
            <input
              type="password"
              value={currentPassword}
              onChange={(event) => setCurrentPassword(event.target.value)}
            />
          </label>
          <label>
            Nouveau mot de passe
            <input
              type="password"
              minLength={8}
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
            />
          </label>
        </div>
        {accountMessage && <p className="muted">{accountMessage}</p>}
        <div className="actions">
          <button
            className="add"
            disabled={newPassword.length < 8}
            onClick={changePassword}
          >
            Changer le mot de passe
          </button>
        </div>
      </section>
      <section className="card full">
        <div className="eyebrow">Données locales</div>
        <h2>Sauvegarde et restauration</h2>
        <p className="muted">
          La sauvegarde utilise l’API SQLite pour produire un fichier cohérent
          pendant que l’application fonctionne.
        </p>
        <div className="actions">
          <a className="ghost buttonlink" href="/api/backup">
            Télécharger une sauvegarde
          </a>
          <label className="add filebutton">
            Restaurer
            <input
              type="file"
              accept=".sqlite,.db"
              onChange={(event) => restore(event.target.files?.[0])}
            />
          </label>
        </div>
      </section>
      <section className="full settingsimport">
        <ImportPage refresh={refresh} />
      </section>
    </div>
  );
}
function MoodModal({ day, close }: { day: string; close: () => void }) {
  const [mood, setMood] = useState(3),
    [stress, setStress] = useState(3),
    [fatigue, setFatigue] = useState(3),
    [craving, setCraving] = useState(3),
    [notes, setNotes] = useState(""),
    [saved, setSaved] = useState(false);
  const submit = async () => {
    await api("/journal", {
      method: "POST",
      body: JSON.stringify({
        day,
        mood,
        stress,
        fatigue,
        craving,
        notes,
        tags: [],
      }),
    });
    setSaved(true);
    setTimeout(close, 700);
  };
  return (
    <div className="modal" onClick={close}>
      <div className="sheet moodmodal" onClick={(e) => e.stopPropagation()}>
        <div className="trophybig">
          <Smile size={38} />
        </div>
        <div className="eyebrow">
          Journal · {new Date(`${day}T12:00:00`).toLocaleDateString("fr-CA")}
        </div>
        <h2>Comment vous sentiez-vous cette journée-là ?</h2>
        <div className="form">
          {[
            ["Humeur", mood, setMood],
            ["Stress", stress, setStress],
            ["Fatigue", fatigue, setFatigue],
            ["Envie de boire", craving, setCraving],
          ].map(([label, value, setter]: any) => (
            <label key={label}>
              {label} : <b>{value}/5</b>
              <input
                type="range"
                min="1"
                max="5"
                value={value}
                onChange={(e) => setter(+e.target.value)}
              />
            </label>
          ))}
          <label className="wide">
            Notes
            <input
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Contexte, déclencheurs, ressenti…"
            />
          </label>
        </div>
        <div className="actions">
          <button className="ghost" onClick={close}>
            Annuler
          </button>
          <button className="add" onClick={submit}>
            {saved ? "Enregistré ✓" : "Enregistrer"}
          </button>
        </div>
      </div>
    </div>
  );
}
function AddMenu({
  presets,
  close,
  choose,
}: {
  presets: Preset[];
  close: () => void;
  choose: (preset: Preset) => void;
}) {
  return (
    <div className="modal" onClick={close}>
      <div className="sheet addmenu" onClick={(e) => e.stopPropagation()}>
        <div className="eyebrow">Nouvelle consommation</div>
        <h2>Ajouter</h2>
        <p className="muted">Choisissez un favori pour une saisie rapide.</p>
        <div className="presetgrid">
          {presets.map((preset) => (
            <button
              className="preset"
              key={preset.id}
              onClick={() => choose(preset)}
            >
              <b>{preset.name}</b>
              <span>
                {preset.volume_ml} ml · {preset.abv_percent}%
              </span>
            </button>
          ))}
        </div>
        <div className="actions">
          <button className="ghost" onClick={close}>
            Annuler
          </button>
          <button
            className="add"
            onClick={() =>
              choose({
                id: 0,
                name: "Consommation",
                drink_type: "",
                volume_ml: 341,
                abv_percent: 5,
              })
            }
          >
            <Plus size={17} /> Personnaliser
          </button>
        </div>
      </div>
    </div>
  );
}

function CopyDrinkModal({
  drink,
  initialDay,
  close,
  submit,
}: {
  drink: any;
  initialDay: string;
  close: () => void;
  submit: (day: string, move: boolean) => Promise<void>;
}) {
  const [day, setDay] = useState(initialDay),
    [move, setMove] = useState(false),
    [saving, setSaving] = useState(false);
  return (
    <div className="modal" onClick={close}>
      <div className="sheet" onClick={(event) => event.stopPropagation()}>
        <div className="eyebrow">{drink.drink_name}</div>
        <h2>Copier ou déplacer</h2>
        <div className="form">
          <label className="wide">
            Journée de destination
            <input
              type="date"
              value={day}
              onChange={(event) => setDay(event.target.value)}
            />
          </label>
          <label className="wide checklabel">
            <input
              type="checkbox"
              checked={move}
              onChange={(event) => setMove(event.target.checked)}
            />{" "}
            Déplacer au lieu de copier
          </label>
        </div>
        <p className="muted">
          L’heure, le volume, le degré d’alcool, la quantité, la durée et les
          notes seront conservés.
        </p>
        <div className="actions">
          <button className="ghost" onClick={close}>
            Annuler
          </button>
          <button
            className="add"
            disabled={saving}
            onClick={async () => {
              setSaving(true);
              await submit(day, move);
            }}
          >
            {saving ? "Enregistrement…" : move ? "Déplacer" : "Copier"}
          </button>
        </div>
      </div>
    </div>
  );
}

function DrinkSheet({
  preset,
  day,
  drink,
  close,
  saved,
}: {
  preset: Preset;
  day: string;
  drink?: any;
  close: () => void;
  saved: () => void;
}) {
  const initialStart = () => {
      const d = new Date(Date.now() - new Date().getTimezoneOffset() * 60000);
      return day === d.toISOString().slice(0, 10)
        ? d.toISOString().slice(0, 16)
        : `${day}T12:00`;
    },
    [name, setName] = useState(drink?.drink_name || preset.name),
    [volume, setVolume] = useState(drink?.volume_ml || preset.volume_ml),
    [abv, setAbv] = useState(drink?.abv_percent ?? preset.abv_percent),
    [duration, setDuration] = useState(drink?.duration_minutes ?? 30),
    [started, setStarted] = useState(
      drink
        ? new Date(
            new Date(drink.started_at).getTime() -
              new Date().getTimezoneOffset() * 60000,
          )
            .toISOString()
            .slice(0, 16)
        : initialStart(),
    );
  const submit = async () => {
    await api(drink ? `/drinks/${drink.id}` : "/drinks", {
      method: drink ? "PATCH" : "POST",
      body: JSON.stringify({
        drink_type: preset.drink_type,
        drink_name: name,
        volume_ml: volume,
        abv_percent: abv,
        quantity: drink?.quantity || 1,
        started_at: new Date(started).toISOString(),
        duration_minutes: duration,
      }),
    });
    saved();
  };
  return (
    <div className="modal" onClick={close}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h2>{drink ? "Modifier la consommation" : preset.name}</h2>
        <div className="form">
          <label className="wide">
            Nom
            <input value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="wide">
            Date et heure de début
            <input
              type="datetime-local"
              value={started}
              onChange={(e) => setStarted(e.target.value)}
            />
          </label>
          <label>
            Volume (ml)
            <input
              type="number"
              value={volume}
              onChange={(e) => setVolume(+e.target.value)}
            />
          </label>
          <label>
            Alcool (%)
            <input
              type="number"
              step=".1"
              value={abv}
              onChange={(e) => setAbv(+e.target.value)}
            />
          </label>
          <label>
            Durée (min)
            <input
              type="number"
              value={duration}
              onChange={(e) => setDuration(+e.target.value)}
            />
          </label>
          <label>
            Alcool pur
            <input
              disabled
              value={`${(((volume * abv) / 100) * 0.789).toFixed(1)} g`}
            />
          </label>
        </div>
        <div className="actions">
          <button className="ghost" onClick={close}>
            Annuler
          </button>
          <button className="add" onClick={submit}>
            {drink ? "Enregistrer" : "Ajouter"}
          </button>
        </div>
      </div>
    </div>
  );
}
if ("serviceWorker" in navigator) {
  window.addEventListener("load", () =>
    navigator.serviceWorker.register("/sw.js"),
  );
}
createRoot(document.getElementById("root")!).render(<App />);
