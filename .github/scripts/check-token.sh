#!/usr/bin/env bash
# Vérifie que le jeton SUPABASE_ACCESS_TOKEN (API Management) est encore valide pour le projet.
# Usage : SUPABASE_ACCESS_TOKEN=… PROJECT_REF=… .github/scripts/check-token.sh
set -uo pipefail
if [ -z "${SUPABASE_ACCESS_TOKEN:-}" ] || [ -z "${PROJECT_REF:-}" ]; then
  echo "::error::Définis SUPABASE_ACCESS_TOKEN (secret) et SUPABASE_PROJECT_REF (variable)."; exit 1
fi
code=$(curl -sS -o /dev/null -w "%{http_code}" "https://api.supabase.com/v1/projects/$PROJECT_REF" \
  -H "Authorization: Bearer $SUPABASE_ACCESS_TOKEN") || code=000
case "$code" in
  200) echo "Jeton Supabase valide." ;;
  401|403) echo "::error::Le secret SUPABASE_ACCESS_TOKEN est expiré ou révoqué (HTTP $code). Crée un nouveau jeton sur https://supabase.com/dashboard/account/tokens puis remplace le secret dans GitHub → Settings → Secrets → Actions."; exit 1 ;;
  404) echo "::error::Projet $PROJECT_REF introuvable avec ce jeton (mauvais SUPABASE_PROJECT_REF ou jeton d'un autre compte)."; exit 1 ;;
  *) echo "::error::API Supabase injoignable (HTTP $code)."; exit 1 ;;
esac
