import { useEffect, useState } from "react";

export type Locale = "fr-CA" | "en";
const STORAGE_KEY = "repere-locale";

const messages = {
  "fr-CA": {
    "nav.now": "Maintenant", "nav.stats": "Stats", "nav.insights": "Repères",
    "nav.success": "Succès", "nav.goals": "Objectifs", "nav.settings": "Réglages",
    "title.now": "Tableau personnel", "title.stats": "Statistiques",
    "title.insights": "Repères personnels", "title.success": "Succès",
    "title.goals": "Objectifs", "title.settings": "Réglages", "title.history": "Historique",
    "network.offline": "Hors ligne", "network.pending": "{count} en attente",
    "update.title": "Une nouvelle version de Repère est disponible.",
    "update.action": "Mettre à jour", "settings.about": "À propos et soutien",
    "settings.language": "Langue", "settings.version": "Version web",
    "settings.support": "Soutenir Repère", "settings.github": "Signaler un problème sur GitHub",
    "language.fr": "Français (Canada)", "language.en": "English",
    "login.user": "Utilisateur", "login.password": "Mot de passe (8 caractères min.)",
    "login.create": "Créer un compte", "login.submit": "Connexion",
    "android.install": "Installer l’application Android sur Google Play",
    "project.discover": "Découvrir le projet Repère",
  },
  en: {
    "nav.now": "Now", "nav.stats": "Stats", "nav.insights": "Insights",
    "nav.success": "Achievements", "nav.goals": "Goals", "nav.settings": "Settings",
    "title.now": "Personal dashboard", "title.stats": "Statistics",
    "title.insights": "Personal insights", "title.success": "Achievements",
    "title.goals": "Goals", "title.settings": "Settings", "title.history": "History",
    "network.offline": "Offline", "network.pending": "{count} pending",
    "update.title": "A new version of Repère is available.",
    "update.action": "Update", "settings.about": "About and support",
    "settings.language": "Language", "settings.version": "Web version",
    "settings.support": "Support Repère", "settings.github": "Report an issue on GitHub",
    "language.fr": "Français (Canada)", "language.en": "English",
    "login.user": "Username", "login.password": "Password (8 characters minimum)",
    "login.create": "Create account", "login.submit": "Sign in",
    "android.install": "Install the Android app from Google Play",
    "project.discover": "Discover the Repère project",
  },
} as const;

export type MessageKey = keyof typeof messages["fr-CA"];
const initialLocale = (): Locale => {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved === "fr-CA" || saved === "en") return saved;
  return navigator.language.toLowerCase().startsWith("fr") ? "fr-CA" : "en";
};

export function useI18n() {
  const [locale, setLocaleState] = useState<Locale>(initialLocale);
  useEffect(() => { document.documentElement.lang = locale; }, [locale]);
  const setLocale = (value: Locale) => { localStorage.setItem(STORAGE_KEY, value);setLocaleState(value); };
  const t = (key: MessageKey, values:Record<string,string|number>={}) =>
    Object.entries(values).reduce((text,[name,value])=>text.replace(`{${name}}`,String(value)),messages[locale][key] as string);
  return { locale, setLocale, t };
}
