-- Soirées ciné : événements programmés par l'admin, auxquels les utilisateurs s'inscrivent.
-- Rejoué à chaque déploiement : tout est idempotent.

-- Photo de profil (voir 20261126170000_profile_avatar.sql), déjà utilisée par upcoming_events() ci-dessous.
alter table public.profiles add column if not exists avatar_url text;

create table if not exists public.events (
  id          uuid primary key default gen_random_uuid(),
  title       text not null,
  description text not null default '',
  starts_at   timestamptz not null,
  ends_at     timestamptz,                -- défaut : début + durée du contenu (+ 30 min)
  cover_url   text,
  media_id    uuid,                       -- film ou épisode projeté
  published   boolean not null default false,
  created_at  timestamptz not null default now()
);

create table if not exists public.event_participants (
  event_id  uuid not null references public.events(id) on delete cascade,
  user_id   uuid not null default auth.uid() references auth.users(id) on delete cascade,
  joined_at timestamptz not null default now(),
  primary key (event_id, user_id)
);
create index if not exists event_participants_user_idx on public.event_participants(user_id);

alter table public.events enable row level security;
alter table public.event_participants enable row level security;

drop policy if exists events_read on public.events;
create policy events_read on public.events for select to authenticated using (published or public.is_admin());
drop policy if exists events_admin on public.events;
create policy events_admin on public.events for all to authenticated using (public.is_admin()) with check (public.is_admin());

drop policy if exists event_participants_read on public.event_participants;
create policy event_participants_read on public.event_participants for select to authenticated
  using (user_id = auth.uid() or public.is_admin());
drop policy if exists event_participants_join on public.event_participants;
create policy event_participants_join on public.event_participants for insert to authenticated
  with check (user_id = auth.uid() and public.is_active()
              and exists (select 1 from public.events e where e.id = event_id and e.published));
drop policy if exists event_participants_leave on public.event_participants;
create policy event_participants_leave on public.event_participants for delete to authenticated
  using (user_id = auth.uid() or public.is_admin());

-- Fin effective d'un événement.
create or replace function public.event_end(e public.events)
returns timestamptz language sql stable set search_path = '' as $$
  select coalesce(e.ends_at, e.starts_at + make_interval(secs => greatest(coalesce(
    (select duration_s from public.episodes where id = e.media_id),
    (select duration_s from public.titles where id = e.media_id), 0), 5400)) + interval '30 minutes');
$$;

-- Événements à venir ou en cours, avec le nombre d'inscrits, quelques prénoms et « je participe ».
-- (Les inscriptions des autres ne sont pas lisibles directement : seuls ces résumés sortent.)
create or replace function public.upcoming_events()
returns jsonb language sql stable security definer set search_path = '' as $$
  select coalesce(jsonb_agg(x order by x->>'starts_at'), '[]'::jsonb) from (
    select jsonb_build_object(
      'id', e.id, 'title', e.title, 'description', e.description,
      'starts_at', e.starts_at, 'ends_at', public.event_end(e),
      'cover_url', e.cover_url, 'media_id', e.media_id,
      'count', (select count(*) from public.event_participants p where p.event_id = e.id),
      'joined', exists (select 1 from public.event_participants p where p.event_id = e.id and p.user_id = auth.uid()),
      'people', coalesce((
        select jsonb_agg(jsonb_build_object('n', n, 'a', av)) from (
          select coalesce(nullif(pr.name, ''), split_part(pr.email, '@', 1)) as n, pr.avatar_url as av
            from public.event_participants p join public.profiles pr on pr.user_id = p.user_id
           where p.event_id = e.id
           order by (p.user_id = auth.uid()) desc, p.joined_at desc
           limit 5) s), '[]'::jsonb)
    ) as x
    from public.events e
    where e.published and public.event_end(e) > now() and auth.uid() is not null
    order by e.starts_at
    limit 5
  ) t;
$$;
revoke all on function public.upcoming_events() from public, anon;
grant execute on function public.upcoming_events() to authenticated;

-- Le contenu d'une soirée à laquelle on est inscrit ne compte pas dans le quota personnel
-- (du moment de l'inscription jusqu'au lendemain de la fin). Le budget des stockages payants
-- s'applique toujours : les contenus de soirée doivent être sur R2 (vérifié dans l'admin).
create or replace function public.event_media_ok(p_media uuid, p_user uuid)
returns boolean language sql stable security definer set search_path = '' as $$
  select exists (
    select 1 from public.events e
      join public.event_participants p on p.event_id = e.id and p.user_id = p_user
     where e.media_id = p_media and e.published and now() < public.event_end(e) + interval '1 day');
$$;
revoke all on function public.event_media_ok(uuid, uuid) from public, anon, authenticated;
