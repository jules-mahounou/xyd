-- =====================================================================
-- xyd — requêtes des utilisateurs (demande de série / film, avis, problème, autre)
-- • envoyées depuis l'accueil de l'app, lues et traitées dans l'admin
-- • l'admin peut répondre : la réponse s'affiche dans l'app
-- • anti-abus : 10 requêtes max par compte sur 24 h
-- Idempotent.
-- =====================================================================
create table if not exists public.user_requests (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  kind        text not null check (kind in ('request', 'feedback', 'bug', 'other')),
  message     text not null check (char_length(btrim(message)) between 3 and 2000),
  app_version text,
  status      text not null default 'new' check (status in ('new', 'seen', 'done')),
  reply       text check (reply is null or char_length(reply) <= 2000),
  created_at  timestamptz not null default now(),
  updated_at  timestamptz not null default now()
);
create index if not exists user_requests_user_idx on public.user_requests (user_id, created_at desc);
create index if not exists user_requests_status_idx on public.user_requests (status, created_at desc);

alter table public.user_requests enable row level security;

drop policy if exists "own insert" on public.user_requests;
create policy "own insert" on public.user_requests for insert to authenticated
  with check (user_id = (select auth.uid()) and (select public.is_active()));
drop policy if exists "own or admin read" on public.user_requests;
create policy "own or admin read" on public.user_requests for select to authenticated
  using (user_id = (select auth.uid()) or (select public.is_admin()));
drop policy if exists "admin update" on public.user_requests;
create policy "admin update" on public.user_requests for update to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
drop policy if exists "admin delete" on public.user_requests;
create policy "admin delete" on public.user_requests for delete to authenticated
  using ((select public.is_admin()));
grant select, insert, update, delete on public.user_requests to authenticated;

-- À l'insertion : statut / réponse imposés (un utilisateur ne peut pas se répondre) + limite de débit.
create or replace function public.user_requests_guard()
returns trigger language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'INSERT' then
    if not public.is_admin() then
      new.status := 'new';
      new.reply := null;
      if (select count(*) from public.user_requests
           where user_id = new.user_id and created_at > now() - interval '24 hours') >= 10 then
        raise exception 'Trop de requêtes envoyées aujourd''hui, réessaie demain.' using errcode = 'P0001';
      end if;
    end if;
    new.message := btrim(new.message);
  end if;
  new.updated_at := now();
  return new;
end; $$;

drop trigger if exists user_requests_guard on public.user_requests;
create trigger user_requests_guard before insert or update on public.user_requests
  for each row execute function public.user_requests_guard();
