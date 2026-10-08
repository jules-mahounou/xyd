// Edge Function réservée aux admins (page web /admin) :
//  - { action: "sign-upload", key }  -> URL PUT pré-signée pour envoyer une vidéo directement dans R2
//  - { action: "delete", keys: [...] } -> supprime des vidéos de R2
// Le token R2 doit avoir la permission « Object Read & Write ».
import { createClient } from "jsr:@supabase/supabase-js@2";
import { objectUrl, presign, r2 } from "../_shared/r2.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });

// Clés autorisées : films/... ou series/..., pas de remontée de dossier.
const validKey = (k: unknown): k is string =>
  typeof k === "string" && /^(films|series)\/[A-Za-z0-9._\-\/]+$/.test(k) && !k.includes("..");

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "Non connecté" }, 401);
  const supabase = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${token}` } },
    auth: { persistSession: false },
  });
  const { data: user, error: authError } = await supabase.auth.getUser(token);
  if (authError || !user?.user) return json({ error: "Session expirée" }, 401);
  const { data: isAdmin } = await supabase.rpc("is_admin");
  if (isAdmin !== true) return json({ error: "Réservé aux administrateurs" }, 403);

  let body: Record<string, unknown> = {};
  try {
    body = await req.json();
  } catch { /* corps invalide */ }

  if (body.action === "sign-upload") {
    if (!validKey(body.key)) return json({ error: "Clé invalide" }, 400);
    return json({ url: await presign(body.key, "PUT", 3 * 3600) });
  }

  if (body.action === "delete") {
    const keys = Array.isArray(body.keys) ? body.keys.filter(validKey) : [];
    const results = await Promise.all(keys.map(async (k) => {
      const r = await r2.fetch(objectUrl(k), { method: "DELETE" });
      return { key: k, ok: r.ok || r.status === 404 };
    }));
    return json({ deleted: results });
  }

  return json({ error: "Action inconnue" }, 400);
});
