-- =====================================================================
-- xyd — mise en page des annonces + format « bandeau » (4:1).
-- layout (jsonb) : { "showText": bool, "position": "left"|"center"|"right",
--                    "theme": "light"|"dark", "cta": "texte du bouton" }
-- Rien de rempli (ou showText = false) = le média seul, sans texte.
-- Idempotent.
-- =====================================================================
alter table public.announcements add column if not exists layout jsonb not null default '{}'::jsonb;

alter table public.announcements drop constraint if exists announcements_ratio_check;
alter table public.announcements add constraint announcements_ratio_check
  check (ratio in ('portrait', 'square', 'landscape', 'wide'));
