-- =====================================================================
-- xyd — visuel de version animé : GIF ou courte vidéo MP4 (comme les annonces).
-- media_type : 'image' | 'gif' | 'video' ; pour une vidéo, image_url = vignette.
-- Idempotent.
-- =====================================================================
alter table public.app_versions add column if not exists media_type text not null default 'image';
alter table public.app_versions add column if not exists video_url  text;
