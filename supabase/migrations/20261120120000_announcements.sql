-- =====================================================================
-- xyd — annonces (visuels portrait / carré / paysage, titre, description,
-- lien facultatif), rangées dans les sections comme les séries et films.
-- Table séparée de `titles` : les anciennes versions de l'app ne les voient pas.
-- Idempotent.
-- =====================================================================

create table if not exists public.announcements (
  id          uuid primary key default gen_random_uuid(),
  title       text not null default '',
  description text not null default '',
  image_url   text,
  ratio       text not null default 'landscape' check (ratio in ('portrait', 'square', 'landscape')),
  link_url    text,
  published   boolean not null default true,
  created_at  timestamptz not null default now()
);

-- Même espace de `position` que title_categories : titres et annonces s'entremêlent dans une section.
create table if not exists public.announcement_categories (
  category_id     uuid not null references public.categories(id) on delete cascade,
  announcement_id uuid not null references public.announcements(id) on delete cascade,
  position        int  not null default 0,
  primary key (category_id, announcement_id)
);
create index if not exists announcement_categories_ann_idx on public.announcement_categories(announcement_id);

alter table public.announcements           enable row level security;
alter table public.announcement_categories enable row level security;

drop policy if exists "read published" on public.announcements;
create policy "read published" on public.announcements for select to authenticated
  using (published or (select public.is_admin()));
drop policy if exists "catalog read" on public.announcement_categories;
create policy "catalog read" on public.announcement_categories for select to authenticated using (true);

drop policy if exists "admin all" on public.announcements;
create policy "admin all" on public.announcements for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
drop policy if exists "admin all" on public.announcement_categories;
create policy "admin all" on public.announcement_categories for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));

grant select on public.announcements, public.announcement_categories to authenticated;
grant insert, update, delete on public.announcements, public.announcement_categories to authenticated;

-- Réordonne une section mélangée en une fois. Clés : uuid d'un titre, ou « a:<uuid> » pour une annonce.
create or replace function public.reorder_category_items(p_category uuid, p_keys text[])
returns void language plpgsql security invoker set search_path = '' as $$
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  update public.title_categories tc set position = o.ord::int
    from unnest(p_keys) with ordinality as o(k, ord)
   where tc.category_id = p_category and tc.title_id::text = o.k;
  update public.announcement_categories ac set position = o.ord::int
    from unnest(p_keys) with ordinality as o(k, ord)
   where ac.category_id = p_category and 'a:' || ac.announcement_id::text = o.k;
end; $$;
revoke all on function public.reorder_category_items(uuid, text[]) from public;
grant execute on function public.reorder_category_items(uuid, text[]) to authenticated;
