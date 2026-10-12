// Edge Function réservée aux admins (page web /admin), pour le pool de stockages :
//  - { action: "sign-upload", key, size, storage_id? } -> réserve l'espace puis URL PUT pré-signée
//  - { action: "release", storage_id, size }           -> libère une réservation (envoi annulé/échoué)
//  - { action: "delete", items: [{ key, storage_id, size }] } -> supprime des vidéos et libère l'espace
//  - { action: "test", storage_id }                    -> vérifie les identifiants (écriture + suppression)
import { createClient } from "jsr:@supabase/supabase-js@2";
import { backend, forget, objectUrl, presign } from "../_shared/storage.ts";

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
const validId = (v: unknown): v is string => typeof v === "string" && /^[0-9a-f-]{36}$/i.test(v);

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

  try {
    if (body.action === "sign-upload") {
      if (!validKey(body.key)) return json({ error: "Clé invalide" }, 400);
      const size = Math.max(0, Math.round(Number(body.size) || 0));
      const wanted = validId(body.storage_id) ? body.storage_id : null;
      const { data: storageId, error } = await supabase.rpc("reserve_storage", { p_size: size, p_storage: wanted });
      if (error) return json({ error: error.message }, 500);
      if (!storageId) {
        return json({ error: wanted ? "Ce stockage n'a plus assez de place" : "Plus assez de place dans le pool de stockage" }, 409);
      }
      try {
        const url = await presign(await backend(storageId), body.key, "PUT", 3 * 3600);
        return json({ url, storage_id: storageId });
      } catch (e) {
        await supabase.rpc("release_storage", { p_storage: storageId, p_size: size });
        throw e;
      }
    }

    if (body.action === "release") {
      if (!validId(body.storage_id)) return json({ error: "storage_id invalide" }, 400);
      await supabase.rpc("release_storage", { p_storage: body.storage_id, p_size: Math.max(0, Number(body.size) || 0) });
      return json({ ok: true });
    }

    if (body.action === "delete") {
      const items = (Array.isArray(body.items) ? body.items : []) as { key?: unknown; storage_id?: unknown; size?: unknown }[];
      const results = await Promise.all(items.filter((it) => validKey(it.key)).map(async (it) => {
        const sid = validId(it.storage_id) ? it.storage_id : null;
        const b = await backend(sid);
        const r = await b.client.fetch(objectUrl(b, it.key as string), { method: "DELETE" });
        const ok = r.ok || r.status === 404;
        if (ok && sid) await supabase.rpc("release_storage", { p_storage: sid, p_size: Math.max(0, Number(it.size) || 0) });
        return { key: it.key, ok };
      }));
      return json({ deleted: results });
    }

    if (body.action === "test") {
      if (!validId(body.storage_id)) return json({ error: "storage_id invalide" }, 400);
      forget(body.storage_id);
      const b = await backend(body.storage_id);
      const url = objectUrl(b, `films/.xyd-test-${crypto.randomUUID()}.txt`);
      const put = await b.client.fetch(url, { method: "PUT", body: "xyd" });
      if (!put.ok) return json({ ok: false, error: `Écriture refusée (HTTP ${put.status}) : ${(await put.text()).slice(0, 200)}` });
      const del = await b.client.fetch(url, { method: "DELETE" });
      return json({ ok: true, delete_ok: del.ok || del.status === 404 });
    }

    return json({ error: "Action inconnue" }, 400);
  } catch (e) {
    return json({ error: (e as Error).message }, 500);
  }
});
