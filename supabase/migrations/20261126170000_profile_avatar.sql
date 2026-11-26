-- Profil modifiable par l'utilisateur : pseudo + photo (avatar). Rejoué à chaque déploiement : idempotent.

alter table public.profiles add column if not exists avatar_url text;

-- Photos de profil : bucket public (affichées aux autres participants), 2 Mo max, images seulement.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('avatars', 'avatars', true, 2097152, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update set public = true, file_size_limit = excluded.file_size_limit, allowed_mime_types = excluded.allowed_mime_types;

-- Chacun n'écrit / ne supprime que dans son propre dossier avatars/<son id>/.
drop policy if exists "avatars own insert" on storage.objects;
create policy "avatars own insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "avatars own update" on storage.objects;
create policy "avatars own update" on storage.objects for update to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "avatars own delete" on storage.objects;
create policy "avatars own delete" on storage.objects for delete to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
drop policy if exists "avatars own read" on storage.objects;
create policy "avatars own read" on storage.objects for select to authenticated
  using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);

-- Mise à jour de SON profil : uniquement le pseudo et la photo (le statut, l'e-mail… restent à l'admin).
create or replace function public.update_my_profile(p_name text default null, p_avatar text default null, p_clear_avatar boolean default false)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare v_uid uuid := auth.uid(); v_name text := nullif(btrim(p_name), '');
begin
  if v_uid is null then raise exception 'Non connecté'; end if;
  if v_name is not null and (char_length(v_name) < 2 or char_length(v_name) > 30) then
    raise exception 'Le pseudo doit faire entre 2 et 30 caractères';
  end if;
  if p_avatar is not null and position('/storage/v1/object/public/avatars/' || v_uid::text || '/' in p_avatar) = 0 then
    raise exception 'Photo invalide';
  end if;
  update public.profiles
     set name = coalesce(v_name, name),
         avatar_url = case when p_clear_avatar then null else coalesce(p_avatar, avatar_url) end
   where user_id = v_uid;
  return (select jsonb_build_object('name', name, 'avatar_url', avatar_url) from public.profiles where user_id = v_uid);
end; $$;
revoke all on function public.update_my_profile(text, text, boolean) from public, anon;
grant execute on function public.update_my_profile(text, text, boolean) to authenticated;
