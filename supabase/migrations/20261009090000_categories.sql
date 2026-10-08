-- =====================================================================
-- xyd — catégories (Top 10, Séries, Anime, …) gérées depuis l'admin
-- Idempotent : peut être rejoué sans risque.
-- =====================================================================

create table if not exists public.categories (
  id         uuid primary key default gen_random_uuid(),
  name       text not null,
  sort       int  not null default 0,        -- ordre des rangées sur l'accueil
  ranked     boolean not null default false, -- affichage « classement » (grands numéros 1, 2, 3…)
  created_at timestamptz not null default now()
);

-- Un titre peut être dans plusieurs catégories ; `position` = ordre dans la catégorie.
create table if not exists public.title_categories (
  category_id uuid not null references public.categories(id) on delete cascade,
  title_id    uuid not null references public.titles(id) on delete cascade,
  position    int  not null default 0,
  primary key (category_id, title_id)
);
create index if not exists title_categories_title_idx on public.title_categories(title_id);

alter table public.categories       enable row level security;
alter table public.title_categories enable row level security;

drop policy if exists "catalog read" on public.categories;
create policy "catalog read" on public.categories for select to authenticated using (true);
drop policy if exists "catalog read" on public.title_categories;
create policy "catalog read" on public.title_categories for select to authenticated using (true);

drop policy if exists "admin all" on public.categories;
create policy "admin all" on public.categories for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
drop policy if exists "admin all" on public.title_categories;
create policy "admin all" on public.title_categories for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));

grant select on public.categories, public.title_categories to authenticated;
grant insert, update, delete on public.categories, public.title_categories to authenticated;

-- Réordonne en une fois : catégories (p_category null) ou titres d'une catégorie.
create or replace function public.reorder_categories(p_ids uuid[])
returns void language plpgsql security invoker set search_path = '' as $$
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  update public.categories c set sort = o.ord::int
    from unnest(p_ids) with ordinality as o(id, ord) where c.id = o.id;
end; $$;

create or replace function public.reorder_category_titles(p_category uuid, p_title_ids uuid[])
returns void language plpgsql security invoker set search_path = '' as $$
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  update public.title_categories tc set position = o.ord::int
    from unnest(p_title_ids) with ordinality as o(id, ord)
   where tc.category_id = p_category and tc.title_id = o.id;
end; $$;

revoke all on function public.reorder_categories(uuid[]) from public;
revoke all on function public.reorder_category_titles(uuid, uuid[]) from public;
grant execute on function public.reorder_categories(uuid[]) to authenticated;
grant execute on function public.reorder_category_titles(uuid, uuid[]) to authenticated;

-- Catégories de départ (créées une seule fois, modifiables ensuite dans l'admin).
insert into public.categories (name, sort, ranked)
select v.name, v.sort, v.ranked
  from (values ('Top 10', 1, true), ('Anime', 2, false)) as v(name, sort, ranked)
 where not exists (select 1 from public.categories);
