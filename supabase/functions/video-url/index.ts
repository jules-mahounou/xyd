// Edge Function : renvoie une URL signée (temporaire) vers une vidéo du bucket Cloudflare R2.
// L'utilisateur doit être connecté ; la lecture du catalogue passe par la RLS avec SON jeton.
//
// Secrets requis (stockage d'origine) : R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET
// Les autres stockages du pool sont lus dans la table storage_backends (clés dans Vault).
// Optionnel : R2_URL_TTL (secondes, défaut 21600 = 6 h)
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
  try {
    mediaId = String((await req.json()).media_id ?? "");
  } catch { /* corps invalide */ }
  if (!/^[0-9a-f-]{36}$/i.test(mediaId)) return json({ error: "media_id invalide" }, 400);

  // Épisode, sinon film. `storage_id` désigne le stockage du pool qui contient la vidéo.
  const ep = await supabase.from("episodes").select("video_key, storage_id").eq("id", mediaId).maybeSingle();
  let row: { video_key: string | null; storage_id: string | null } | null = ep.data;
  if (!row?.video_key) {
    const film = await supabase.from("titles").select("video_key, storage_id").eq("id", mediaId).eq("kind", "film").maybeSingle();
    row = film.data;
  }
  if (!row?.video_key) return json({ error: "Vidéo introuvable" }, 404);

  try {
    const b = await backend(row.storage_id);
    return json({ url: await presign(b, row.video_key, "GET", TTL), expires_in: TTL });
  } catch (e) {
    return json({ error: (e as Error).message }, 500);
  }
});
