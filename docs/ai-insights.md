# Analyse comportementale OpenAI

L’analyse IA est volontairement déclenchée par l’utilisateur depuis l’onglet **Stats → Comprendre mes comportements**. Elle n’est pas exécutée automatiquement et ne constitue ni un diagnostic médical ni une preuve de causalité.

Le serveur envoie uniquement des statistiques agrégées : journées observées, moyennes, heures de début, durée, vitesse, tranches d’ABV, jours de semaine et séries hebdomadaires/mensuelles. Les notes personnelles et le texte du journal ne sont pas envoyés.

Pour l’activer dans l’installation native :

```bash
sudo systemctl edit alcohol-tracker
```

Ajouter :

```ini
[Service]
Environment=OPENAI_API_KEY=sk-...
Environment=OPENAI_MODEL=gpt-5.6-sol
```

Puis appliquer :

```bash
sudo systemctl daemon-reload
sudo systemctl restart alcohol-tracker
```

Sans clé, l’application continue de fonctionner et affiche simplement que l’analyse OpenAI n’est pas configurée.
