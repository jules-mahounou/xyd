// Edge Function : renvoie une URL signée (temporaire) vers une vidéo du bucket Cloudflare R2.
// L'utilisateur doit être connecté ; la lecture du catalogue passe par la RLS avec SON jeton.
//
// Secrets requis (supabase secrets set ...) :
//   R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET
// Optionnel : R2_URL_TTL (secondes, défaut 21600 = 6 h)
import { createClient } from "jsr:@supabase/supabase-js@2";
import { AwsClient } from "npm:aws4fetch@1.0.20";

const R2 = new AwsClient({
  accessKeyId: Deno.env.get("R2_ACCESS_KEY_ID")!,
  secretAccessKey: Deno.env.get("R2_SECRET_ACCESS_KEY")!,
  service: "s3",
  region: "auto",
});
const ENDPOINT = `https://${Deno.env.get("R2_ACCOUNT_ID")}.r2.cloudflarestorage.com/${Deno.env.get("R2_BUCKET")}`;
const TTL = Deno.env.get("R2_URL_TTL") ?? "21600";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const auth = req.headers.get("Authorization") ?? "";
  const token = auth.replace(/^Bearer\s+/i, "");
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
  let key: string | null = null;
  const ep = await supabase.from("episodes").select("video_key").eq("id", mediaId).maybeSingle();
  key = ep.data?.video_key ?? null;
  if (!key) {
    const film = await supabase.from("titles").select("video_key").eq("id", mediaId).eq("kind", "film").maybeSingle();
    key = film.data?.video_key ?? null;
  }
  if (!key) return json({ error: "Vidéo introuvable" }, 404);

  const path = key.replace(/^\/+/, "").split("/").map(encodeURIComponent).join("/");
  const url = new URL(`${ENDPOINT}/${path}`);
  url.searchParams.set("X-Amz-Expires", TTL);
  const signed = await R2.sign(new Request(url, { method: "GET" }), { aws: { signQuery: true } });

  return json({ url: signed.url, expires_in: Number(TTL) });
});
