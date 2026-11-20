-- =====================================================================
-- xyd — annonces animées : GIF et courtes vidéos MP4 (en plus des images).
-- media_type : 'image' | 'gif' | 'video'. Pour une vidéo, image_url = vignette
-- (1re image, affichée pendant le chargement et dans les styles non animés).
-- Idempotent.
-- =====================================================================
alter table public.announcements add column if not exists media_type text not null default 'image';
alter table public.announcements add column if not exists video_url text;
do $$ begin
  alter table public.announcements add constraint announcements_media_type_chk check (media_type in ('image', 'gif', 'video'));
exception when duplicate_object then null; end $$;

-- Bucket public dédié : GIF et MP4 « pas lourds » (12 Mo max).
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('ads', 'ads', true, 12582912, array['image/gif', 'video/mp4'])
on conflict (id) do update set public = true, file_size_limit = 12582912, allowed_mime_types = array['image/gif', 'video/mp4'];

drop policy if exists "ads admin select" on storage.objects;
create policy "ads admin select" on storage.objects for select to authenticated
  using (bucket_id = 'ads' and (select public.is_admin()));
drop policy if exists "ads admin insert" on storage.objects;
create policy "ads admin insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'ads' and (select public.is_admin()));
drop policy if exists "ads admin update" on storage.objects;
create policy "ads admin update" on storage.objects for update to authenticated
  using (bucket_id = 'ads' and (select public.is_admin()));
drop policy if exists "ads admin delete" on storage.objects;
create policy "ads admin delete" on storage.objects for delete to authenticated
  using (bucket_id = 'ads' and (select public.is_admin()));
