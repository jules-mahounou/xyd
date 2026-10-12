-- xyd — titres « à la une » (bannière de l'accueil), choisis dans l'admin.
alter table public.titles add column if not exists featured boolean not null default false;
create index if not exists titles_featured_idx on public.titles(featured) where featured;
