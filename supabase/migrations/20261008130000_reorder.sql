-- =====================================================================
-- xyd — réordonner épisodes et saisons depuis l'admin
-- Les contraintes d'unicité deviennent « deferrable » : on peut permuter
-- les numéros en une seule instruction sans conflit transitoire.
-- =====================================================================

alter table public.episodes drop constraint if exists episodes_season_id_number_key;
alter table public.episodes add constraint episodes_season_id_number_key
  unique (season_id, number) deferrable initially immediate;

alter table public.seasons drop constraint if exists seasons_title_id_number_key;
alter table public.seasons add constraint seasons_title_id_number_key
  unique (title_id, number) deferrable initially immediate;

-- Renumérote les épisodes d'une saison selon l'ordre des ids (1, 2, 3…).
create or replace function public.reorder_episodes(p_season uuid, p_ids uuid[])
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'Réservé aux administrateurs';
  end if;
  set constraints all deferred;
  update public.episodes e
     set number = o.ord::int
    from unnest(p_ids) with ordinality as o(id, ord)
   where e.id = o.id and e.season_id = p_season;
end;
$$;

-- Renumérote les saisons d'une série selon l'ordre des ids.
create or replace function public.reorder_seasons(p_title uuid, p_ids uuid[])
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if not public.is_admin() then
    raise exception 'Réservé aux administrateurs';
  end if;
  set constraints all deferred;
  update public.seasons s
     set number = o.ord::int
    from unnest(p_ids) with ordinality as o(id, ord)
   where s.id = o.id and s.title_id = p_title;
end;
$$;

revoke all on function public.reorder_episodes(uuid, uuid[]) from public;
revoke all on function public.reorder_seasons(uuid, uuid[]) from public;
grant execute on function public.reorder_episodes(uuid, uuid[]) to authenticated;
grant execute on function public.reorder_seasons(uuid, uuid[]) to authenticated;
