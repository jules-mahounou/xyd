-- =====================================================================
-- xyd — schéma initial (à exécuter une fois dans Supabase > SQL Editor)
-- =====================================================================

-- ---------- Catalogue ----------
create table if not exists public.titles (
  id           uuid primary key default gen_random_uuid(),
  kind         text not null check (kind in ('serie', 'film')),
  name         text not null,
  synopsis     text not null default '',
  poster_url   text,              -- affiche verticale 2:3 (URL publique)
  backdrop_url text,              -- image 16:9 (URL publique)
  year         int,
  -- Films uniquement : la vidéo est portée directement par le titre.
  video_key    text,              -- chemin de l'objet dans le bucket R2, ex: films/inception.mp4
  duration_s   int  not null default 0,
  size_bytes   bigint not null default 0,
  sort         int  not null default 0,   -- ordre d'affichage (petit = en premier)
  published    boolean not null default true,
  created_at   timestamptz not null default now(),
  constraint film_has_video check (kind <> 'film' or video_key is not null)
);

create table if not exists public.seasons (
  id        uuid primary key default gen_random_uuid(),
  title_id  uuid not null references public.titles(id) on delete cascade,
  number    int  not null,
  name      text not null default '',
  unique (title_id, number)
);

create table if not exists public.episodes (
  id          uuid primary key default gen_random_uuid(),
  season_id   uuid not null references public.seasons(id) on delete cascade,
  number      int  not null,
  name        text not null default '',
  synopsis    text not null default '',
  video_key   text not null,          -- ex: series/dark/s01/e01.mp4
  duration_s  int  not null default 0,
  size_bytes  bigint not null default 0,
  created_at  timestamptz not null default now(),
  unique (season_id, number)
);

create index if not exists seasons_title_idx on public.seasons(title_id);
create index if not exists episodes_season_idx on public.episodes(season_id);

-- ---------- Reprise de lecture ----------
create table if not exists public.watch_progress (
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  media_id    uuid not null,          -- id d'un épisode ou d'un film
  position_ms bigint not null default 0,
  duration_ms bigint not null default 0,
  updated_at  timestamptz not null default now(),
  primary key (user_id, media_id)
);

-- ---------- Versions de l'APK (mise à jour forcée) ----------
create table if not exists public.app_versions (
  version_code     int primary key,
  version_name     text not null default '',
  min_version_code int  not null default 0,  -- en dessous : l'app est bloquée jusqu'à la MAJ
  apk_url          text not null,
  changelog        text not null default '',
  created_at       timestamptz not null default now()
);

-- ---------- Sécurité (RLS) ----------
alter table public.titles         enable row level security;
alter table public.seasons        enable row level security;
alter table public.episodes       enable row level security;
alter table public.watch_progress enable row level security;
alter table public.app_versions   enable row level security;

-- Catalogue : lisible par tout utilisateur connecté. Écriture : uniquement via le dashboard / service_role.
drop policy if exists "catalog read" on public.titles;
create policy "catalog read" on public.titles   for select to authenticated using (published);
drop policy if exists "catalog read" on public.seasons;
create policy "catalog read" on public.seasons  for select to authenticated using (true);
drop policy if exists "catalog read" on public.episodes;
create policy "catalog read" on public.episodes for select to authenticated using (true);

-- Progression : chacun ne voit / modifie que la sienne.
drop policy if exists "own progress" on public.watch_progress;
create policy "own progress" on public.watch_progress for all to authenticated
  using (user_id = (select auth.uid())) with check (user_id = (select auth.uid()));

-- Versions : lisibles par tous (même non connecté) pour pouvoir bloquer avant la connexion.
drop policy if exists "versions read" on public.app_versions;
create policy "versions read" on public.app_versions for select to anon, authenticated using (true);

grant select on public.titles, public.seasons, public.episodes to authenticated;
grant select, insert, update, delete on public.watch_progress to authenticated;
grant select on public.app_versions to anon, authenticated;
