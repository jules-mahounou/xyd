// Accès aux stockages vidéo du pool (R2, Backblaze B2, tout S3 compatible).
// - Stockage « env » : clés R2 d'origine, dans les secrets des Edge Functions (venant de GitHub).
// - Autres stockages : identifiants déchiffrés depuis Supabase Vault via storage_credentials()
//   (fonction réservée au service_role), mis en cache 5 minutes.
import { AwsClient } from "npm:aws4fetch@1.0.20";
import { createClient } from "jsr:@supabase/supabase-js@2";

export type Backend = { client: AwsClient; base: string };

const service = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

let envBackend: Backend | null = null;
function fromEnv(): Backend {
  envBackend ??= {
    client: new AwsClient({
      accessKeyId: Deno.env.get("R2_ACCESS_KEY_ID")!,
      secretAccessKey: Deno.env.get("R2_SECRET_ACCESS_KEY")!,
      service: "s3",
      region: "auto",
    }),
    base: `https://${Deno.env.get("R2_ACCOUNT_ID")}.r2.cloudflarestorage.com/${Deno.env.get("R2_BUCKET")}`,
  };
  return envBackend;
}

const cache = new Map<string, { at: number; backend: Backend }>();

/** Stockage d'une vidéo. `null` = stockage d'origine (anciennes vidéos). */
export async function backend(id: string | null | undefined): Promise<Backend> {
  if (!id) return fromEnv();
  const hit = cache.get(id);
  if (hit && Date.now() - hit.at < 5 * 60_000) return hit.backend;

  const { data, error } = await service.rpc("storage_credentials", { p_id: id });
  if (error) throw new Error(`Stockage illisible : ${error.message}`);
  const row = Array.isArray(data) ? data[0] : data;
  if (!row) throw new Error("Stockage introuvable");
  let b: Backend;
  if (row.is_env) {
    b = fromEnv();
  } else {
    if (!row.endpoint || !row.bucket || !row.access_key || !row.secret_key) {
      throw new Error("Stockage incomplet (endpoint, bucket ou clés)");
    }
    b = {
      client: new AwsClient({
        accessKeyId: row.access_key,
        secretAccessKey: row.secret_key,
        service: "s3",
        region: row.region || "auto",
      }),
      base: `${String(row.endpoint).replace(/\/+$/, "")}/${row.bucket}`,
    };
  }
  cache.set(id, { at: Date.now(), backend: b });
  return b;
}

export function forget(id: string) {
  cache.delete(id);
}

export function objectUrl(b: Backend, key: string): URL {
  const path = key.replace(/^\/+/, "").split("/").map(encodeURIComponent).join("/");
  return new URL(`${b.base}/${path}`);
}

/** URL PUT pré-signée d'un morceau n d'un envoi en plusieurs morceaux. */
export async function presignPart(b: Backend, key: string, uploadId: string, n: number, ttl: number): Promise<string> {
  const url = objectUrl(b, key);
  url.searchParams.set("partNumber", String(n));
  url.searchParams.set("uploadId", uploadId);
  url.searchParams.set("X-Amz-Expires", String(ttl));
  const signed = await b.client.sign(new Request(url, { method: "PUT" }), { aws: { signQuery: true } });
  return signed.url;
}

/** URL pré-signée (GET pour lire, PUT pour envoyer) valable `ttl` secondes. */
export async function presign(b: Backend, key: string, method: "GET" | "PUT", ttl: number): Promise<string> {
  const url = objectUrl(b, key);
  url.searchParams.set("X-Amz-Expires", String(ttl));
  const signed = await b.client.sign(new Request(url, { method }), { aws: { signQuery: true } });
  return signed.url;
}

const unxml = (s: string) => s.replace(/&amp;/g, "&").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'");

/**
 * Supprime un fichier ET toutes ses versions.
 * Sur Backblaze B2, un simple DELETE ne fait que « cacher » le fichier : l'ancienne version reste stockée
 * (et facturée) indéfiniment. On efface donc aussi chaque version et marqueur de suppression de cette clé.
 * Sur R2 (pas de versions), la liste échoue ou revient vide : seul le DELETE compte.
 */
export async function removeObject(b: Backend, key: string): Promise<boolean> {
  const r = await b.client.fetch(objectUrl(b, key), { method: "DELETE" });
  const ok = r.ok || r.status === 404;
  try {
    const clean = key.replace(/^\/+/, "");
    const list = new URL(b.base);
    list.searchParams.set("versions", "");
    list.searchParams.set("prefix", clean);
    const res = await b.client.fetch(list);
    if (!res.ok) return ok;
    const xml = await res.text();
    const versions = [...xml.matchAll(/<(?:Version|DeleteMarker)>([\s\S]*?)<\/(?:Version|DeleteMarker)>/g)]
      .map((m) => ({ key: unxml(/<Key>([\s\S]*?)<\/Key>/.exec(m[1])?.[1] ?? ""), id: unxml(/<VersionId>([\s\S]*?)<\/VersionId>/.exec(m[1])?.[1] ?? "") }))
      .filter((v) => v.key === clean && v.id && v.id !== "null");
    for (const v of versions) {
      const u = objectUrl(b, clean);
      u.searchParams.set("versionId", v.id);
      await b.client.fetch(u, { method: "DELETE" });
    }
  } catch { /* nettoyage best-effort : le fichier est déjà supprimé ou caché */ }
  return ok;
}
