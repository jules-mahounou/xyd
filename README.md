# xyd

Application Android privée (Kotlin + Jetpack Compose + Supabase + Cloudflare R2) pour regarder séries et films entre proches.

- **2 écrans** : Accueil et Lecteur. Tout le reste est en bottom sheets (connexion, fiche série/film, téléchargements, réglages, mise à jour).
- **Téléchargement avant lecture**, vidéos **chiffrées AES-256-CTR** sur le téléphone (clé protégée par l'Android Keystore), reprise de téléchargement.
- **Reprise de lecture** synchronisée (local + Supabase).
- **Orientation** au choix (Auto / Portrait / Paysage) ; en portrait la vidéo est en haut.
- **Mise à jour forcée** : l'app se bloque tant que la nouvelle version n'est pas installée.
- **APK ≈ 1,4 Mo** (limite de 10 Mo vérifiée par la CI, le build échoue au-delà).

## Limitation des téléchargements (quotas gratuits)

Chaque demande de vidéo passe par `grant_download()` (migration `download_limits`), appelée par l'Edge Function `video-url` :

1. **Stockage** : budget journalier (UTC) par stockage facturable (B2, S3 tiers) en octets sortants et transactions classe B/C, avec marge sous la limite gratuite. Les buckets d'un même compte partagent le budget (« compte de facturation »). R2 = sortie gratuite, non limité.
2. **Personne** : quota en durée de vidéo par jour et par mois, nombre de vidéos par jour, anti-rafale. La première vidéo du jour passe toujours.
3. **Équité** : une personne ne prend pas plus de 50 % du budget d'un stockage facturable, sauf dans les 3 dernières heures du jour UTC.
4. **Gros consommateurs** : au-delà de 3 × la médiane sur 7 jours (et 15 h minimum), quota journalier divisé par 2.

Les reprises d'une même vidéo le même jour ne sont pas recomptées. Refus = HTTP 429 avec `retry_at` : l'app met le téléchargement en pause et le relance seule après le renouvellement. Tout se règle dans l'admin (carte « Téléchargements » et formulaire de chaque stockage).

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
