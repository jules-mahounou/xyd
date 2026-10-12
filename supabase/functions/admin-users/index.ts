// Edge Function réservée aux admins : actions sur les comptes qui exigent la clé service.
//  - { action: "set-password", user_id, password } -> l'admin définit lui-même un mot de passe
//  - { action: "delete", user_id }                -> supprime le compte (et ses données liées)
// (Valider / suspendre se fait directement sur la table profiles, protégée par RLS.)
import { createClient } from "jsr:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { ...CORS, "Content-Type": "application/json" } });

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  if (req.method !== "POST") return json({ error: "method not allowed" }, 405);

  const token = (req.headers.get("Authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "Non connecté" }, 401);
  const user = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_ANON_KEY")!, {
    global: { headers: { Authorization: `Bearer ${token}` } }, auth: { persistSession: false },
  });
  const { data: me } = await user.auth.getUser(token);
  const { data: isAdmin } = await user.rpc("is_admin");
  if (!me?.user || isAdmin !== true) return json({ error: "Réservé aux administrateurs" }, 403);

  const service = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
  let body: Record<string, unknown> = {};
  try { body = await req.json(); } catch { /* vide */ }
  const id = String(body.user_id ?? "");
  if (!/^[0-9a-f-]{36}$/i.test(id)) return json({ error: "user_id invalide" }, 400);

  if (body.action === "set-password") {
    const pw = String(body.password ?? "");
    if (pw.length < 6) return json({ error: "Mot de passe : 6 caractères minimum" }, 400);
    const { error } = await service.auth.admin.updateUserById(id, { password: pw });
    return error ? json({ error: error.message }, 500) : json({ ok: true });
  }

  if (body.action === "delete") {
    if (id === me.user.id) return json({ error: "Tu ne peux pas supprimer ton propre compte ici" }, 400);
    const { error } = await service.auth.admin.deleteUser(id);
    return error ? json({ error: error.message }, 500) : json({ ok: true });
  }

  return json({ error: "Action inconnue" }, 400);
});
