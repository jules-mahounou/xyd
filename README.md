# xyd

Application Android privée (Kotlin + Jetpack Compose + Supabase + Cloudflare R2) pour regarder séries et films entre proches.

- **2 écrans** : Accueil et Lecteur. Tout le reste est en bottom sheets (connexion, fiche série/film, téléchargements, réglages, mise à jour).
- **Téléchargement avant lecture**, vidéos **chiffrées AES-256-CTR** sur le téléphone (clé protégée par l'Android Keystore), reprise de téléchargement.
- **Reprise de lecture** synchronisée (local + Supabase).
- **Orientation** au choix (Auto / Portrait / Paysage) ; en portrait la vidéo est en haut.
- **Mise à jour forcée** : l'app se bloque tant que la nouvelle version n'est pas installée.
- **APK ≈ 1,4 Mo** (limite de 10 Mo vérifiée par la CI, le build échoue au-delà).

## Arborescence

| Dossier | Contenu |
|---|---|
| `app/` | Application Android |
| `supabase/migrations/` | Schéma SQL + RLS |
| `supabase/functions/video-url/` | Edge Function : URL signée R2 |
| `web/` | Landing `xydhub.tech` (index.html, logo, .htaccess) |
| `.github/workflows/` | Build APK, publication, déploiement backend |

## Choix pour la taille de l'APK

| Remplacé | Par | Gain approx. |
|---|---|---|
| supabase-kt + Ktor + kotlinx.serialization | REST via `HttpURLConnection` + `org.json` (framework) | ~1,5 Mo |
| Coil / Glide | `Images.kt` (LRU mémoire + cache disque) | ~0,4 Mo |
| media3-ui, DefaultRenderersFactory, DefaultExtractorsFactory | 2 renderers + 2 extracteurs (MP4, MKV) | ~0,6 Mo |
| Room | JSON dans `filesDir` | ~0,3 Mo |
| navigation-compose, AppCompat, icônes étendues | état Compose, thème framework, 6 icônes vectorielles | ~0,5 Mo |
| PNG de l'icône | icône adaptative 100 % vectorielle | ~0,1 Mo |

Plus : R8 full mode, `shrinkResources`, locale `fr` uniquement, suppression des assertions Kotlin et des logs.

## Build local

`local.properties` :

```
supabase.url=https://XXXX.supabase.co
supabase.anonKey=eyJ...
```

puis `./gradlew assembleRelease`.
