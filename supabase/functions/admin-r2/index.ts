// Edge Function réservée aux admins (page web /admin), pour le pool de stockages :
//  - { action: "sign-upload", key, size, storage_id? } -> réserve l'espace puis URL PUT pré-signée
//  - { action: "release", storage_id, size }           -> libère une réservation (envoi annulé/échoué)
//  Envoi en plusieurs morceaux, reprenable (outil terminal « xyd ») :
//  - { action: "mp-start", key, size, storage_id? }    -> réserve l'espace, ouvre l'envoi -> { storage_id, upload_id }
//  - { action: "mp-sign", key, storage_id, upload_id, parts: [n] } -> URL PUT pré-signées des morceaux
//  - { action: "mp-list", key, storage_id, upload_id } -> morceaux déjà reçus [{ n, etag, size }]
//  - { action: "mp-complete", key, storage_id, upload_id, parts: [{ n, etag }] } -> assemble le fichier
//  - { action: "mp-abort", key, storage_id, upload_id, size } -> annule et libère l'espace
//  - { action: "delete", items: [{ key, storage_id, size }] } -> supprime des vidéos et libère l'espace
//  - { action: "test", storage_id }                    -> vérifie les identifiants (écriture + suppression)
//  - { action: "setup-cors", storage_id, origins, key_id?, app_key? }
//      -> pose la règle CORS qui autorise l'envoi depuis l'admin (B2 : API native ; S3 : PutBucketCors)
import { createClient } from "jsr:@supabase/supabase-js@2";
import { backend, forget, objectUrl, presign, presignPart, removeObject } from "../_shared/storage.ts";
import { crypto as stdCrypto } from "jsr:@std/crypto@1";

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

    // ---------- envoi en plusieurs morceaux ----------
    if (typeof body.action === "string" && body.action.startsWith("mp-")) {
      if (!validKey(body.key)) return json({ error: "Clé invalide" }, 400);
      const key = body.key;
      if (body.action === "mp-start") {
        const size = Math.max(0, Math.round(Number(body.size) || 0));
        const wanted = validId(body.storage_id) ? body.storage_id : null;
        const { data: storageId, error } = await supabase.rpc("reserve_storage", { p_size: size, p_storage: wanted });
        if (error) return json({ error: error.message }, 500);
        if (!storageId) return json({ error: wanted ? "Ce stockage n'a plus assez de place" : "Plus assez de place dans le pool de stockage" }, 409);
        try {
          const b = await backend(storageId);
          const u = objectUrl(b, key);
          u.searchParams.set("uploads", "");
          const r = await b.client.fetch(u, { method: "POST", headers: { "Content-Type": "video/mp4" } });
          const txt = await r.text();
          const id = /<UploadId>([^<]+)<\/UploadId>/.exec(txt)?.[1];
          if (!r.ok || !id) throw new Error(`Ouverture refusée (HTTP ${r.status}) : ${txt.slice(0, 200)}`);
          const { data: st } = await supabase.from("storage_backends").select("name").eq("id", storageId).single();
          return json({ storage_id: storageId, upload_id: id, storage_name: st?.name ?? null });
        } catch (e) {
          await supabase.rpc("release_storage", { p_storage: storageId, p_size: size });
          throw e;
        }
      }
      const sid = validId(body.storage_id) ? body.storage_id : null;
      const uploadId = typeof body.upload_id === "string" && /^[A-Za-z0-9._~+\/=-]{8,1024}$/.test(body.upload_id) ? body.upload_id : null;
      if (!uploadId) return json({ error: "upload_id invalide" }, 400);
      const b = await backend(sid);
      if (body.action === "mp-sign") {
        const parts = (Array.isArray(body.parts) ? body.parts : []).map(Number).filter((n) => Number.isInteger(n) && n >= 1 && n <= 10000).slice(0, 200);
        const urls: Record<number, string> = {};
        for (const n of parts) urls[n] = await presignPart(b, key, uploadId, n, 3 * 3600);
        return json({ urls });
      }
      if (body.action === "mp-list") {
        const parts: { n: number; etag: string; size: number }[] = [];
        let marker = "0";
        for (let i = 0; i < 20; i++) {
          const u = objectUrl(b, key);
          u.searchParams.set("uploadId", uploadId);
          u.searchParams.set("part-number-marker", marker);
          const r = await b.client.fetch(u);
          const txt = await r.text();
          if (r.status === 404) return json({ error: "Envoi expiré ou inconnu", gone: true }, 404);
          if (!r.ok) return json({ error: `Liste refusée (HTTP ${r.status})` }, 502);
          for (const m of txt.matchAll(/<Part>([\s\S]*?)<\/Part>/g)) {
            parts.push({
              n: Number(/<PartNumber>(\d+)<\/PartNumber>/.exec(m[1])?.[1]),
              etag: (/<ETag>([^<]*)<\/ETag>/.exec(m[1])?.[1] ?? "").replace(/&quot;/g, '"'),
              size: Number(/<Size>(\d+)<\/Size>/.exec(m[1])?.[1] ?? 0),
            });
          }
          if (!/<IsTruncated>true<\/IsTruncated>/.test(txt)) break;
          marker = /<NextPartNumberMarker>(\d+)<\/NextPartNumberMarker>/.exec(txt)?.[1] ?? "";
          if (!marker) break;
        }
        return json({ parts });
      }
      if (body.action === "mp-complete") {
        const parts = (Array.isArray(body.parts) ? body.parts : []) as { n?: unknown; etag?: unknown }[];
        const xml = "<CompleteMultipartUpload>" + parts
          .map((p) => ({ n: Number(p.n), etag: String(p.etag ?? "").replace(/[<>&]/g, "") }))
          .filter((p) => Number.isInteger(p.n) && p.n >= 1 && p.etag)
          .sort((a, c) => a.n - c.n)
          .map((p) => `<Part><PartNumber>${p.n}</PartNumber><ETag>${p.etag}</ETag></Part>`).join("") + "</CompleteMultipartUpload>";
        const u = objectUrl(b, key);
        u.searchParams.set("uploadId", uploadId);
        const r = await b.client.fetch(u, { method: "POST", body: xml, headers: { "Content-Type": "application/xml" } });
        const txt = await r.text();
        if (!r.ok || /<Error>/.test(txt)) return json({ error: `Assemblage refusé (HTTP ${r.status}) : ${txt.slice(0, 300)}` }, 502);
        return json({ ok: true });
      }
      if (body.action === "mp-abort") {
        const u = objectUrl(b, key);
        u.searchParams.set("uploadId", uploadId);
        const r = await b.client.fetch(u, { method: "DELETE" });
        if (sid) await supabase.rpc("release_storage", { p_storage: sid, p_size: Math.max(0, Number(body.size) || 0) });
        return json({ ok: r.ok || r.status === 404 });
      }
      return json({ error: "Action inconnue" }, 400);
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
        const ok = await removeObject(b, it.key as string);
        if (ok && sid) await supabase.rpc("release_storage", { p_storage: sid, p_size: Math.max(0, Number(it.size) || 0) });
        return { key: it.key, ok };
      }));
      return json({ deleted: results });
    }

    if (body.action === "test") {
      if (!validId(body.storage_id)) return json({ error: "storage_id invalide" }, 400);
      forget(body.storage_id);
      const b = await backend(body.storage_id);
      const testKey = `films/.xyd-test-${crypto.randomUUID()}.txt`;
      const url = objectUrl(b, testKey);
      const put = await b.client.fetch(url, { method: "PUT", body: "xyd" });
      if (!put.ok) return json({ ok: false, error: `Écriture refusée (HTTP ${put.status}) : ${(await put.text()).slice(0, 200)}` });
      const delOk = await removeObject(b, testKey);
      return json({ ok: true, delete_ok: delOk });
    }

    if (body.action === "setup-cors") {
      if (!validId(body.storage_id)) return json({ error: "storage_id invalide" }, 400);
      const origins = (Array.isArray(body.origins) ? body.origins : [])
        .filter((o): o is string => typeof o === "string" && /^https:\/\/[a-z0-9.-]+(:\d+)?$/i.test(o));
      if (!origins.length) return json({ error: "Origine invalide" }, 400);
      const service = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
      const { data: rows } = await supabase.from("storage_backends").select("provider, bucket").eq("id", body.storage_id).single();
      const { data: creds } = await service.rpc("storage_credentials", { p_id: body.storage_id });
      const c = Array.isArray(creds) ? creds[0] : creds;
      if (!rows || !c) return json({ error: "Stockage introuvable" }, 404);
      if (c.is_env) return json({ error: "Le R2 principal se configure dans le tableau de bord Cloudflare." }, 400);

      if (rows.provider === "b2") {
        // authorize + list_buckets + update_bucket = 3 transactions classe C, comptées dans le budget du jour.
        const { data: allowed } = await supabase.rpc("meter_storage_ops", { p_storage: body.storage_id, p_class_b: 0, p_class_c: 3 });
        if (allowed === false) return json({ ok: false, error: "Budget du jour en transactions classe C atteint pour ce compte B2. Réessaie demain." });
        // Clé optionnelle (ex. clé maître) utilisée une seule fois et jamais enregistrée.
        const keyId = typeof body.key_id === "string" && body.key_id ? body.key_id : c.access_key;
        const appKey = typeof body.app_key === "string" && body.app_key ? body.app_key : c.secret_key;
        return json(await b2Cors(keyId, appKey, rows.bucket ?? c.bucket, origins));
      }
      return json(await s3Cors(await backend(body.storage_id), origins));
    }

    return json({ error: "Action inconnue" }, 400);
  } catch (e) {
    return json({ error: (e as Error).message }, 500);
  }
});

// ---------- CORS ----------

/** Backblaze B2 : la règle doit lister s3_put explicitement (l'interface web ne propose que le téléchargement). */
async function b2Cors(keyId: string, appKey: string, bucketName: string, origins: string[]) {
  const auth = await fetch("https://api.backblazeb2.com/b2api/v3/b2_authorize_account", {
    headers: { Authorization: "Basic " + btoa(`${keyId}:${appKey}`) },
  });
  const a = await auth.json();
  if (!auth.ok) return { ok: false, error: `Connexion B2 refusée : ${a.message ?? auth.status}` };
  const apiUrl = a.apiInfo?.storageApi?.apiUrl;
  const call = async (name: string, payload: unknown) => {
    const r = await fetch(`${apiUrl}/b2api/v3/${name}`, {
      method: "POST",
      headers: { Authorization: a.authorizationToken, "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    return { ok: r.ok, status: r.status, body: await r.json().catch(() => ({})) };
  };
  const list = await call("b2_list_buckets", { accountId: a.accountId, bucketName });
  const bucket = list.body.buckets?.[0];
  if (!list.ok || !bucket) return { ok: false, error: `Bucket « ${bucketName} » introuvable avec cette clé.` };
  const upd = await call("b2_update_bucket", {
    accountId: a.accountId,
    bucketId: bucket.bucketId,
    corsRules: [{
      corsRuleName: "xydAdminUpload",
      allowedOrigins: origins,
      allowedOperations: ["s3_put", "s3_get", "s3_head", "b2_download_file_by_name", "b2_download_file_by_id"],
      allowedHeaders: ["*"],
      maxAgeSeconds: 3600,
    }],
  });
  if (!upd.ok) {
    const needMaster = upd.status === 401 || upd.status === 403;
    return {
      ok: false,
      need_master_key: needMaster,
      error: needMaster
        ? "Cette clé n'a pas le droit de modifier le bucket (writeBuckets). Utilise ta clé maître B2, une seule fois."
        : `B2 a refusé la règle : ${upd.body.message ?? upd.status}`,
    };
  }
  return { ok: true };
}

/** S3 générique (IDrive e2, Filebase…) : PutBucketCors. */
async function s3Cors(b: { client: { fetch: (u: URL, init: RequestInit) => Promise<Response> }; base: string }, origins: string[]) {
  const xml = `<CORSConfiguration><CORSRule>${origins.map((o) => `<AllowedOrigin>${o}</AllowedOrigin>`).join("")}` +
    `<AllowedMethod>PUT</AllowedMethod><AllowedMethod>GET</AllowedMethod><AllowedMethod>HEAD</AllowedMethod>` +
    `<AllowedHeader>*</AllowedHeader><ExposeHeader>ETag</ExposeHeader><MaxAgeSeconds>3600</MaxAgeSeconds>` +
    `</CORSRule></CORSConfiguration>`;
  const bytes = new TextEncoder().encode(xml);
  const md5 = btoa(String.fromCharCode(...new Uint8Array(await stdCrypto.subtle.digest("MD5", bytes))));
  const r = await b.client.fetch(new URL(`${b.base}?cors`), {
    method: "PUT",
    body: bytes,
    headers: { "Content-MD5": md5, "Content-Type": "application/xml" },
  });
  if (!r.ok) return { ok: false, error: `Le fournisseur a refusé la règle CORS (HTTP ${r.status}) : ${(await r.text()).slice(0, 200)}` };
  return { ok: true };
}
