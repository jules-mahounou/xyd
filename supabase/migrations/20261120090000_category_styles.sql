-- =====================================================================
-- xyd — styles des sections (catégories) réglables depuis l'admin.
-- Idempotent. Un seul champ JSON pour pouvoir ajouter des styles sans migration :
--   { "card": "poster" | "accordion" | "warp",
--     "section": "row",
--     "number": { "position": "bottom-start" | "top-start" | "bottom-end" | "top-end" } }
-- Absent / vide = comportement d'avant (affiches, rangée, numéros en bas à gauche).
-- =====================================================================
alter table public.categories add column if not exists style jsonb not null default '{}'::jsonb;
