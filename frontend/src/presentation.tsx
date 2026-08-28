import React, { useState } from "react";
import { createRoot } from "react-dom/client";
import { ArrowRight, Code2, Heart, LockKeyhole, RefreshCw, Server, ShieldCheck, Smartphone, WifiOff } from "lucide-react";
import "./presentation.css";

const PLAY="https://play.google.com/store/apps/details?id=ca.repere.app";
const GITHUB="https://github.com/fpoisson2/alcohol-tracker";
const SUPPORT="https://buymeacoffee.com/fpoisson";
type Lang="fr"|"en";

const copy={
  fr:{
    navWhy:"Pourquoi Repère",navHow:"Comment ça marche",navOpen:"Projet libre",openApp:"Ouvrir mon serveur",
    mockToday:"Aujourd’hui",mockTracked:"consommations suivies localement",mockHome:"chez toi",systemLabel:"Système Repère local",
    eyebrow:"Libre · local · autonome",hero:"Mieux comprendre sa consommation, sans céder ses données.",
    intro:"Repère transforme tes habitudes en repères utiles. L’application Android fonctionne seule, puis se synchronise avec ton propre serveur quand tu le souhaites.",
    install:"Installer sur Google Play",source:"Voir le code source",promise:"Le contrôle local n’est pas un mode avancé. C’est le point de départ.",
    privacy:"Tes données restent chez toi",privacyText:"Le serveur est auto-hébergé. Tailscale permet d’y accéder à distance sans l’exposer publiquement.",
    offline:"Utile même hors ligne",offlineText:"Saisis, consulte et analyse tes données sur Android sans attendre une connexion au serveur.",
    open:"Ouvert par conception",openText:"Code source libre, formats exportables et infrastructure que tu peux inspecter, adapter et sauvegarder.",
    flowTitle:"Un système qui suit ton rythme",flowIntro:"Chaque pièce reste utile par elle-même. Ensemble, elles prolongent ton contrôle au lieu de le remplacer.",
    phone:"Téléphone",phoneText:"La source de vérité locale",home:"Chez toi",homeText:"Ton historique et tes sauvegardes",sync:"À ton rythme",syncText:"Une synchronisation facultative",
    featuresTitle:"Des repères, pas des jugements.",featuresIntro:"Repère rend les tendances visibles et laisse les décisions à la personne qui connaît le mieux le contexte : toi.",
    f1:"Suivi rapide",f1t:"Consommations, journées sobres et bilans quotidiens.",f2:"Tendances personnelles",f2t:"Statistiques, objectifs et évolution dans le temps.",f3:"Estimation d’alcoolémie",f3t:"Un modèle cohérent entre serveur et mobile, toujours accompagné d’un avertissement de conduite.",f4:"Santé et montre",f4t:"Health Connect et compagnon Wear OS pour les informations essentielles.",
    selfTitle:"Ton serveur. Ton adresse. Tes sauvegardes.",selfText:"Déploie Repère avec Docker ou dans un LXC, conserve la base de données sur ton infrastructure et connecte tes appareils par ton réseau privé Tailscale.",readInstall:"Lire le guide d’installation",
    community:"Construit ouvertement",communityText:"Repère grandit grâce aux personnes qui l’utilisent, le traduisent, testent ses versions et proposent des améliorations.",contribute:"Contribuer sur GitHub",support:"Soutenir le projet",
    final:"Reprends un peu de perspective.",finalText:"Commence sur Android ou connecte-toi à ton installation existante.",footer:"Un projet libre pour une santé numérique plus autonome.",
  },
  en:{
    navWhy:"Why Repère",navHow:"How it works",navOpen:"Open project",openApp:"Open my server",
    mockToday:"Today",mockTracked:"drinks tracked locally",mockHome:"at home",systemLabel:"Local-first Repère system",
    eyebrow:"Open · local · autonomous",hero:"Understand your drinking without giving up your data.",
    intro:"Repère turns habits into useful perspective. The Android app works on its own, then syncs with your own server whenever you choose.",
    install:"Get it on Google Play",source:"View source code",promise:"Local control is not an advanced mode. It is the starting point.",
    privacy:"Your data stays home",privacyText:"The server is self-hosted. Tailscale provides remote access without exposing it to the public internet.",
    offline:"Useful even offline",offlineText:"Record, review, and understand your data on Android without waiting for a server connection.",
    open:"Open by design",openText:"Open source code, exportable formats, and infrastructure you can inspect, adapt, and back up.",
    flowTitle:"A system that follows your rhythm",flowIntro:"Every part remains useful by itself. Together, they extend your control instead of replacing it.",
    phone:"Your phone",phoneText:"The local source of truth",home:"Your home",homeText:"Your history and backups",sync:"Your timing",syncText:"Optional synchronization",
    featuresTitle:"Perspective, not judgment.",featuresIntro:"Repère makes patterns visible and leaves decisions to the person who knows the context best: you.",
    f1:"Quick tracking",f1t:"Drinks, sober days, and daily check-ins.",f2:"Personal trends",f2t:"Statistics, goals, and change over time.",f3:"BAC estimate",f3t:"One consistent model across server and mobile, always paired with a driving warning.",f4:"Health and watch",f4t:"Health Connect and a Wear OS companion for essential information.",
    selfTitle:"Your server. Your address. Your backups.",selfText:"Deploy Repère with Docker or in an LXC, keep the database on your infrastructure, and connect devices over your private Tailscale network.",readInstall:"Read the installation guide",
    community:"Built in the open",communityText:"Repère grows through the people who use it, translate it, test releases, and propose improvements.",contribute:"Contribute on GitHub",support:"Support the project",
    final:"Get a little perspective.",finalText:"Start on Android or connect to an installation you already run.",footer:"An open project for more autonomous digital health.",
  }
} as const;

function Presentation(){
  const [lang,setLang]=useState<Lang>(()=>navigator.language.toLowerCase().startsWith("fr")?"fr":"en");
  const t=copy[lang];
  React.useEffect(()=>{document.documentElement.lang=lang==="fr"?"fr-CA":"en"},[lang]);
  const features=[[t.f1,t.f1t],[t.f2,t.f2t],[t.f3,t.f3t],[t.f4,t.f4t]];
  return <main className="site">
    <header className="site-nav"><a className="site-brand" href="/about"><img src="/logo.png" alt=""/><span>Repère</span></a><nav><a href="#why">{t.navWhy}</a><a href="#flow">{t.navHow}</a><a href="#open">{t.navOpen}</a></nav><div className="nav-actions"><button className="language" onClick={()=>setLang(lang==="fr"?"en":"fr")} aria-label="Change language">{lang==="fr"?"EN":"FR"}</button><a className="quiet-link" href="/">{t.openApp}</a></div></header>
    <section className="hero"><div className="hero-copy"><p className="kicker">{t.eyebrow}</p><h1>{t.hero}</h1><p className="lead">{t.intro}</p><div className="hero-actions"><a className="primary-cta" href={PLAY}>{t.install}<ArrowRight/></a><a className="secondary-cta" href={GITHUB}><Code2/>{t.source}</a></div></div><div className="hero-object" aria-label={t.systemLabel}><div className="orbit orbit-one"/><div className="orbit orbit-two"/><div className="phone-card"><div className="phone-top"><span>Repère</span><WifiOff size={15}/></div><p>{t.mockToday}</p><strong>2</strong><small>{t.mockTracked}</small><div className="mini-bars"><i/><i/><i/><i/><i/><i/><i/></div></div><div className="home-node"><Server/><span>{t.mockHome}</span></div><svg className="sync-path" viewBox="0 0 500 420" aria-hidden="true"><path d="M160 292 C 255 390, 315 320, 380 220"/><circle cx="160" cy="292" r="5"/><circle cx="380" cy="220" r="5"/></svg></div></section>
    <p className="manifesto">{t.promise}</p>
    <section className="principles" id="why"><article><LockKeyhole/><h2>{t.privacy}</h2><p>{t.privacyText}</p></article><article><WifiOff/><h2>{t.offline}</h2><p>{t.offlineText}</p></article><article><ShieldCheck/><h2>{t.open}</h2><p>{t.openText}</p></article></section>
    <section className="flow-section" id="flow"><div className="section-heading"><p className="kicker">Local first</p><h2>{t.flowTitle}</h2><p>{t.flowIntro}</p></div><div className="life-line"><article><span><Smartphone/></span><h3>{t.phone}</h3><p>{t.phoneText}</p></article><article><span><RefreshCw/></span><h3>{t.sync}</h3><p>{t.syncText}</p></article><article><span><Server/></span><h3>{t.home}</h3><p>{t.homeText}</p></article></div></section>
    <section className="features"><div className="feature-intro"><h2>{t.featuresTitle}</h2><p>{t.featuresIntro}</p></div><div className="feature-list">{features.map(([title,text],index)=><article key={title}><span>0{index+1}</span><div><h3>{title}</h3><p>{text}</p></div></article>)}</div></section>
    <section className="self-host"><div><p className="kicker">Self-hosted</p><h2>{t.selfTitle}</h2><p>{t.selfText}</p><a href={`${GITHUB}#quick-start`}>{t.readInstall}<ArrowRight/></a></div><div className="terminal" aria-label="Docker installation example"><div><i/><i/><i/></div><code><em>$</em> git clone {GITHUB}.git<br/><em>$</em> docker compose up -d<br/><span>✓ Repère is running locally</span></code></div></section>
    <section className="community" id="open"><Heart/><div><h2>{t.community}</h2><p>{t.communityText}</p></div><div><a className="primary-cta" href={GITHUB}>{t.contribute}</a><a className="secondary-cta" href={SUPPORT}>{t.support}</a></div></section>
    <section className="final-cta"><h2>{t.final}</h2><p>{t.finalText}</p><div><a className="primary-cta" href={PLAY}>{t.install}</a><a className="secondary-cta" href="/">{t.openApp}</a></div></section>
    <footer><a className="site-brand" href="/about"><img src="/logo.png" alt=""/><span>Repère</span></a><p>{t.footer}</p><a href={GITHUB}><Code2/>GitHub</a></footer>
  </main>
}

createRoot(document.getElementById("root")!).render(<Presentation/>);
