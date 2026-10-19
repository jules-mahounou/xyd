#!/usr/bin/env bash
# Vérifie que chaque table créée par supabase/migrations existe bien dans le projet Supabase en ligne.
# Lecture publique (clé anon) : aucun droit d'écriture nécessaire.
#   « table absente » (PostgREST PGRST205 / HTTP 404) → migration non appliquée → échec.
#   Toute autre réponse (200, 401 faute de droits anon…) prouve que la table existe.
# Usage : SUPABASE_URL=… SUPABASE_ANON_KEY=… .github/scripts/check-schema.sh
set -uo pipefail
: "${SUPABASE_URL:?SUPABASE_URL manquant}" "${SUPABASE_ANON_KEY:?SUPABASE_ANON_KEY manquant}"
cd "$(dirname "$0")/../.."

tables=$(grep -hoiE 'create table if not exists public\.[a-z_0-9]+' supabase/migrations/*.sql | sed -E 's/.*public\.//I' | sort -u)
missing=()
for t in $tables; do
  code=$(curl -sS -o /tmp/schema-check.json -w "%{http_code}" "${SUPABASE_URL%/}/rest/v1/$t?select=*&limit=0" \
    -H "apikey: $SUPABASE_ANON_KEY") || code=000
  if [ "$code" = "000" ]; then echo "::error::Supabase injoignable (projet en pause ?)"; exit 1; fi
  if [ "$code" = "404" ] || grep -q 'PGRST205' /tmp/schema-check.json; then missing+=("$t"); fi
done

if [ ${#missing[@]} -gt 0 ]; then
  echo "::error::Tables absentes en ligne : ${missing[*]}. Les migrations ne sont pas appliquées : relance le workflow « Backend (Supabase) » (et vérifie le secret SUPABASE_ACCESS_TOKEN)."
  exit 1
fi
echo "Schéma Supabase à jour : $(echo $tables | wc -w) tables présentes."
