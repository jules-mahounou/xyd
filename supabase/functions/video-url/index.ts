// Edge Function : renvoie une URL signée (temporaire) vers une vidéo du bucket Cloudflare R2.
// L'utilisateur doit être connecté ; la lecture du catalogue passe par la RLS avec SON jeton.
//
// Secrets requis (stockage d'origine) : R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET
// Les autres stockages du pool sont lus dans la table storage_backends (clés dans Vault).
// Optionnel : R2_URL_TTL (secondes, défaut 21600 = 6 h ; 3 h max sur un stockage facturable, cf. url_ttl)
// Chaque demande passe par grant_download() (migration download_limits) qui protège les quotas gratuits.
import { createClient } from "jsr:@supabase/supabase-js@2";
import { backend, presign } from "../_shared/storage.ts";

const TTL = Number(Deno.env.get("R2_URL_TTL") ?? "21600");

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "Non connecté" }, 401);

  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false },
  });
  const { data: user, error: authError } = await supabase.auth.getUser(token);
  if (authError || !user?.user) return json({ error: "Session expirée" }, 401);
  const { data: active } = await supabase.rpc("is_active");
  if (active === false) return json({ error: "Accès suspendu" }, 403);

  let mediaId = "";
  let offset: number | null = null;
  try {
    const body = await req.json();
    mediaId = String(body.media_id ?? "");
    if (body.offset != null && Number.isFinite(Number(body.offset))) offset = Math.max(0, Math.floor(Number(body.offset)));
  } catch { /* corps invalide */ }
  if (!/^[0-9a-f-]{36}$/i.test(mediaId)) return json({ error: "media_id invalide" }, 400);

  // Limitation multicouche (quota par personne, budget journalier des stockages facturables,
  // part équitable, gros consommateurs) : décidée en SQL, de façon atomique. Voir grant_download().
  const { data: g, error: grantError } = await supabase.rpc("grant_download", { p_media: mediaId, p_offset: offset });
  if (grantError) return json({ error: grantError.message }, 500);
  if (!g?.ok) {
    return json({ error: g?.message ?? "Téléchargement refusé", reason: g?.reason ?? null, retry_at: g?.retry_at ?? null }, g?.status ?? 429);
  }

  try {
    const b = await backend(g.storage_id);
    const ttl = Math.min(TTL, Number(g.ttl) || TTL);
    return json({ url: await presign(b, g.video_key, "GET", ttl), expires_in: ttl });
  } catch (e) {
    return json({ error: (e as Error).message }, 500);
  }
});
