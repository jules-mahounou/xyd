// Edge Function appelée par le trigger SQL à chaque inscription (via pg_net) :
// envoie un mail à l'admin avec les champs choisis dans l'admin. Un profil n'est notifié qu'une fois.
// Body : { user_id }    — ou, depuis l'admin connecté : { test: true } pour un mail d'essai.

import { createClient } from "jsr:@supabase/supabase-js@2";
import { sendMail, smtpConfigured } from "../_shared/mail.ts";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });

const service = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
  auth: { persistSession: false },
});

const LABELS: Record<string, string> = {
  name: "Nom", email: "Email", created_at: "Inscription", status: "Statut", user_id: "Identifiant",
};
const STATUS: Record<string, string> = { active: "actif", blocked: "suspendu" };

function render(profile: Record<string, unknown>, fields: string[]) {
  const lines = fields.filter((f) => f in LABELS).map((f) => {
    let v = String(profile[f] ?? "");
    if (f === "created_at" && v) v = new Date(v).toLocaleString("fr-FR", { timeZone: "Europe/Paris" });
    if (f === "status") v = STATUS[v] ?? v;
    return [LABELS[f], v] as const;
  });
  const text = lines.map(([k, v]) => `${k} : ${v}`).join("\n");
  const html = `<div style="font-family:system-ui,sans-serif;font-size:15px">
    <p><b>Nouvelle inscription sur xyd</b></p>
    <table style="border-collapse:collapse">${lines.map(([k, v]) =>
      `<tr><td style="padding:4px 12px 4px 0;color:#666">${k}</td><td style="padding:4px 0">${v.replace(/</g, "&lt;")}</td></tr>`).join("")}</table>
    <p style="color:#666;font-size:13px">Gérer les comptes : <a href="https://xydhub.tech/admin/">xydhub.tech/admin</a></p>
  </div>`;
  return { text, html };
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);
  if (!smtpConfigured()) return json({ ok: false, error: "SMTP non configuré (secrets SMTP_*)" });

  let body: Record<string, unknown> = {};
  try { body = await req.json(); } catch { /* vide */ }

  const { data: s } = await service.from("app_settings").select("value").eq("key", "signup").maybeSingle();
  const settings = (s?.value ?? {}) as { notify?: boolean; recipient?: string | null; fields?: string[] };
  const fields = settings.fields?.length ? settings.fields : ["name", "email", "created_at"];

  // Mail d'essai : réservé à un admin connecté.
  if (body.test === true) {
    const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
    const user = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
      global: { headers: { Authorization: `Bearer ${token}` } }, auth: { persistSession: false },
    });
    const { data: isAdmin } = await user.rpc("is_admin");
    if (isAdmin !== true) return json({ error: "Réservé aux administrateurs" }, 403);
    if (!settings.recipient) return json({ ok: false, error: "Aucun destinataire défini" });
    const { text, html } = render(
      { name: "Exemple", email: "exemple@mail.com", created_at: new Date().toISOString(), status: "active", user_id: "00000000-0000-0000-0000-000000000000" },
      fields,
    );
    try {
      await sendMail(settings.recipient, "[xyd] Test — nouvelle inscription", text, html);
      return json({ ok: true });
    } catch (e) {
      return json({ ok: false, error: (e as Error).message });
    }
  }

  const userId = String(body.user_id ?? "");
  if (!/^[0-9a-f-]{36}$/i.test(userId)) return json({ error: "user_id invalide" }, 400);
  if (settings.notify === false || !settings.recipient) return json({ ok: true, skipped: true });

  // Ne notifie qu'une fois, et seulement pour une inscription récente (évite tout renvoi abusif).
  const { data: p } = await service.from("profiles").select("*").eq("user_id", userId).maybeSingle();
  if (!p || p.notified_at) return json({ ok: true, skipped: true });
  if (Date.now() - new Date(p.created_at).getTime() > 15 * 60_000) return json({ ok: true, skipped: true });

  const { text, html } = render(p, fields);
  try {
    await sendMail(settings.recipient, `[xyd] Nouvelle inscription : ${p.name ?? p.email}`, text, html);
    await service.from("profiles").update({ notified_at: new Date().toISOString() }).eq("user_id", userId);
    return json({ ok: true });
  } catch (e) {
    return json({ ok: false, error: (e as Error).message }, 500);
  }
});
