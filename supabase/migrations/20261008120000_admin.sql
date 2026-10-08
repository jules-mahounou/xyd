-- =====================================================================
-- xyd — administration (page web /admin)
-- Idempotent : peut être rejoué sans risque.
-- =====================================================================

-- ---------- Administrateurs ----------
create table if not exists public.admins (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);
alter table public.admins enable row level security;

drop policy if exists "self read" on public.admins;
create policy "self read" on public.admins for select to authenticated
  using (user_id = (select auth.uid()));
grant select on public.admins to authenticated;

-- security definer : utilisable dans les policies sans exposer la table.
create or replace function public.is_admin()
returns boolean
language sql stable security definer
set search_path = ''
as $$
  select exists (select 1 from public.admins where user_id = (select auth.uid()));
$$;
revoke all on function public.is_admin() from public;
grant execute on function public.is_admin() to authenticated;

-- ---------- Écriture du catalogue réservée aux admins ----------
drop policy if exists "admin all" on public.titles;
create policy "admin all" on public.titles for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
drop policy if exists "admin all" on public.seasons;
create policy "admin all" on public.seasons for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
drop policy if exists "admin all" on public.episodes;
create policy "admin all" on public.episodes for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));

grant insert, update, delete on public.titles, public.seasons, public.episodes to authenticated;

-- Versions : l'admin peut aussi les gérer depuis la page.
drop policy if exists "admin all" on public.app_versions;
create policy "admin all" on public.app_versions for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
grant insert, update, delete on public.app_versions to authenticated;

-- ---------- Images (affiches) : bucket Supabase Storage public ----------
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('posters', 'posters', true, 5242880, array['image/jpeg', 'image/png', 'image/webp'])
on conflict (id) do update set public = true;

-- Lecture via l'API (nécessaire pour upsert / list) ; l'affichage public passe par l'URL publique.
drop policy if exists "posters admin select" on storage.objects;
create policy "posters admin select" on storage.objects for select to authenticated
  using (bucket_id = 'posters' and (select public.is_admin()));
drop policy if exists "posters admin insert" on storage.objects;
create policy "posters admin insert" on storage.objects for insert to authenticated
  with check (bucket_id = 'posters' and (select public.is_admin()));
drop policy if exists "posters admin update" on storage.objects;
create policy "posters admin update" on storage.objects for update to authenticated
  using (bucket_id = 'posters' and (select public.is_admin()));
drop policy if exists "posters admin delete" on storage.objects;
create policy "posters admin delete" on storage.objects for delete to authenticated
  using (bucket_id = 'posters' and (select public.is_admin()));

-- Pour te déclarer admin (le CI le fait avec la variable ADMIN_EMAIL) :
-- insert into public.admins (user_id) select id from auth.users where email = 'toi@exemple.com' on conflict do nothing;
