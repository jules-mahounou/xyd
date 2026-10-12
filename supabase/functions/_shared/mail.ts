// Envoi de mails via SMTP (secrets SMTP_HOST, SMTP_PORT, SMTP_USER, SMTP_PASSWORD, SMTP_FROM optionnel).
// Port 465 (TLS implicite) recommandé : les Edge Functions Supabase bloquent les ports 25 et 587.
import nodemailer from "npm:nodemailer@6.9.16";

export function smtpConfigured(): boolean {
  return !!(Deno.env.get("SMTP_HOST") && Deno.env.get("SMTP_USER") && Deno.env.get("SMTP_PASSWORD"));
}

export async function sendMail(to: string, subject: string, text: string, html?: string) {
  const port = Number(Deno.env.get("SMTP_PORT") ?? "465");
  const transport = nodemailer.createTransport({
    host: Deno.env.get("SMTP_HOST"),
    port,
    secure: port === 465,
    auth: { user: Deno.env.get("SMTP_USER"), pass: Deno.env.get("SMTP_PASSWORD") },
  });
  const from = Deno.env.get("SMTP_FROM") || `xyd <${Deno.env.get("SMTP_USER")}>`;
  await transport.sendMail({ from, to, subject, text, html });
}
