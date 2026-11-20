-- =====================================================================
-- xyd — format d'annonce « libre » : le visuel garde ses proportions réelles
-- (layout.aspect = largeur / hauteur, mesuré par l'admin), affiché en entier.
-- Idempotent.
-- =====================================================================
alter table public.announcements drop constraint if exists announcements_ratio_check;
alter table public.announcements add constraint announcements_ratio_check
  check (ratio in ('portrait', 'square', 'landscape', 'wide', 'free'));
