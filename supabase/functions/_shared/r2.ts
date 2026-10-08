import { AwsClient } from "npm:aws4fetch@1.0.20";

export const r2 = new AwsClient({
  accessKeyId: Deno.env.get("R2_ACCESS_KEY_ID")!,
  secretAccessKey: Deno.env.get("R2_SECRET_ACCESS_KEY")!,
  service: "s3",
  region: "auto",
});

const ENDPOINT = `https://${Deno.env.get("R2_ACCOUNT_ID")}.r2.cloudflarestorage.com/${Deno.env.get("R2_BUCKET")}`;

export function objectUrl(key: string): URL {
  const path = key.replace(/^\/+/, "").split("/").map(encodeURIComponent).join("/");
  return new URL(`${ENDPOINT}/${path}`);
}

/** URL pré-signée (GET pour lire, PUT pour envoyer) valable `ttl` secondes. */
export async function presign(key: string, method: "GET" | "PUT", ttl: number): Promise<string> {
  const url = objectUrl(key);
  url.searchParams.set("X-Amz-Expires", String(ttl));
  const signed = await r2.sign(new Request(url, { method }), { aws: { signQuery: true } });
  return signed.url;
}
