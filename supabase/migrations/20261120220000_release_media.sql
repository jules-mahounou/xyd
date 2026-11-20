-- =====================================================================
-- xyd — mises à jour plus rassurantes : taille de l'APK et visuel d'annonce.
-- app_versions.size_bytes : écrit par la CI à la publication.
-- app_versions.image_url  : affiche portrait montrée en haut du modal de mise à jour.
-- Le visuel et les notes de la PROCHAINE version se préparent dans l'admin
-- (app_settings.next_release) ; la CI les recopie dans la ligne publiée.
-- Idempotent.
-- =====================================================================
alter table public.app_versions add column if not exists size_bytes bigint not null default 0;
alter table public.app_versions add column if not exists image_url  text;
