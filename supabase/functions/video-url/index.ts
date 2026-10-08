// Edge Function : renvoie une URL signée (temporaire) vers une vidéo du bucket Cloudflare R2.
// L'utilisateur doit être connecté ; la lecture du catalogue passe par la RLS avec SON jeton.
//
// Secrets requis : R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET
// Optionnel : R2_URL_TTL (secondes, défaut 21600 = 6 h)
import { createClient } from "jsr:@supabase/supabase-js@2";
import { presign } from "../_shared/r2.ts";

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

  let mediaId = "";
  try {
    mediaId = String((await req.json()).media_id ?? "");
  } catch { /* corps invalide */ }
  if (!/^[0-9a-f-]{36}$/i.test(mediaId)) return json({ error: "media_id invalide" }, 400);

  // Épisode, sinon film.
  const ep = await supabase.from("episodes").select("video_key").eq("id", mediaId).maybeSingle();
  let key: string | null = ep.data?.video_key ?? null;
  if (!key) {
    const film = await supabase.from("titles").select("video_key").eq("id", mediaId).eq("kind", "film").maybeSingle();
    key = film.data?.video_key ?? null;
  }
  if (!key) return json({ error: "Vidéo introuvable" }, 404);

  return json({ url: await presign(key, "GET", TTL), expires_in: TTL });
});
