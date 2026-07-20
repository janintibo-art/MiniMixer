# 🎛 Mini Mixer

Mini table de mixage pour Android :
- **Widget** d'écran d'accueil (look mini console) qui ouvre le mixer d'un tap
- **Faders verticaux** pour les canaux audio : Média, Appel, Sonnerie, Notif, Alarme
- **Égaliseur** Bass / Médium / Aigu (s'applique à la sortie audio globale)
- **Menu Filtres** : Bass Boost, Son 3D (Virtualizer), Loudness, Réverbération (presets)
- **Apps ouvertes** : faders pour les apps qui jouent du son (via les sessions média,
  nécessite l'accès aux notifications)

## Build
Le workflow GitHub Actions (`.github/workflows/build.yml`) compile automatiquement
l'APK debug à chaque push. Récupère-le dans l'onglet **Actions → dernier run →
Artifacts → MiniMixer-APK**.

## Limites Android (sans root)
- Le volume **par application** n'existe pas dans l'API Android publique : ici on
  utilise les sessions média (marche bien pour le Cast/Bluetooth, sinon agit sur
  le canal Média) + les canaux système.
- L'égaliseur global (session audio 0) fonctionne sur la plupart des appareils
  mais peut être bloqué par certains constructeurs.
- Les effets restent actifs tant que l'app est en vie.
