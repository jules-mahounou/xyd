-- =====================================================================
-- xyd — gestion des utilisateurs + notification d'inscription par mail
-- • profiles : nom, email, statut (active / blocked) — tout compte a accès dès l'inscription
-- • app_settings : réglages de la notification modifiables depuis l'admin
-- • trigger sur auth.users : crée le profil et déclenche l'Edge Function notify-signup
-- Aucun mot de passe n'est stocké ni transmis.
-- Idempotent.
-- =====================================================================
create extension if not exists pg_net with schema extensions;

create table if not exists public.profiles (
  user_id      uuid primary key references auth.users(id) on delete cascade,
  email        text,
  name         text,
  status       text not null default 'active' check (status in ('active', 'blocked')),
  created_at   timestamptz not null default now(),
  notified_at  timestamptz
);

-- Comptes déjà existants : actifs.
insert into public.profiles (user_id, email, name, created_at)
select id, email, coalesce(nullif(raw_user_meta_data->>'name', ''), split_part(email, '@', 1)), created_at
  from auth.users
on conflict (user_id) do nothing;

-- Plus de mode « en attente » : tout compte a accès dès l'inscription.
update public.profiles set status = 'active' where status not in ('active', 'blocked');
alter table public.profiles drop constraint if exists profiles_status_check;
alter table public.profiles add constraint profiles_status_check check (status in ('active', 'blocked'));

create table if not exists public.app_settings (
  key   text primary key,
  value jsonb not null
);
insert into public.app_settings (key, value)
values ('signup', '{"notify": true, "recipient": null, "fields": ["name", "email", "created_at"]}')
on conflict (key) do nothing;

alter table public.profiles     enable row level security;
alter table public.app_settings enable row level security;

drop policy if exists "own or admin read" on public.profiles;
create policy "own or admin read" on public.profiles for select to authenticated
  using (user_id = (select auth.uid()) or (select public.is_admin()));
drop policy if exists "admin update" on public.profiles;
create policy "admin update" on public.profiles for update to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
grant select, update on public.profiles to authenticated;

drop policy if exists "admin all" on public.app_settings;
create policy "admin all" on public.app_settings for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
grant select, insert, update on public.app_settings to authenticated;

-- Compte actif (non suspendu). Les admins le sont toujours ; un compte sans profil est actif.
create or replace function public.is_active()
returns boolean language sql stable security definer set search_path = '' as $$
  select public.is_admin()
      or coalesce((select status = 'active' from public.profiles where user_id = (select auth.uid())), true);
$$;
revoke all on function public.is_active() from public, anon;
grant execute on function public.is_active() to authenticated;

-- Le catalogue n'est pas visible des comptes suspendus.
drop policy if exists "catalog read" on public.titles;
create policy "catalog read" on public.titles for select to authenticated using (published and (select public.is_active()));
drop policy if exists "catalog read" on public.seasons;
create policy "catalog read" on public.seasons for select to authenticated using ((select public.is_active()));
drop policy if exists "catalog read" on public.episodes;
create policy "catalog read" on public.episodes for select to authenticated using ((select public.is_active()));
drop policy if exists "catalog read" on public.categories;
create policy "catalog read" on public.categories for select to authenticated using ((select public.is_active()));
drop policy if exists "catalog read" on public.title_categories;
create policy "catalog read" on public.title_categories for select to authenticated using ((select public.is_active()));

-- Nouvelle inscription : profil actif + notification par mail à l'admin.
-- Ne bloque jamais l'inscription, même en cas d'erreur.
create or replace function public.handle_new_user()
returns trigger language plpgsql security definer set search_path = '' as $$
declare
  s   jsonb;
  url text;
begin
  begin
    select value into s from public.app_settings where key = 'signup';
    insert into public.profiles (user_id, email, name, created_at)
    values (new.id, new.email, coalesce(nullif(new.raw_user_meta_data->>'name', ''), split_part(new.email, '@', 1)), now())
    on conflict (user_id) do nothing;

    select value->>'url' into url from public.app_settings where key = 'functions';
    if url is not null and coalesce((s->>'notify')::boolean, true) then
      perform net.http_post(
        url := url || '/notify-signup',
        body := jsonb_build_object('user_id', new.id),
        headers := '{"Content-Type": "application/json"}'::jsonb
      );
    end if;
  exception when others then
    raise warning 'handle_new_user: %', sqlerrm;
  end;
  return new;
end; $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.handle_new_user();

update public.app_settings set value = value - 'require_approval' where key = 'signup';
