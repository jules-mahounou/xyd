-- =====================================================================
-- xyd — images de couverture pour l'écran de connexion (avant connexion).
-- Ne renvoie QUE des URL d'images publiques (bucket « posters » déjà public),
-- tirées au hasard parmi les titres publiés : aucun titre, aucune donnée.
-- Idempotent.
-- =====================================================================
create or replace function public.public_covers(p_limit int default 24)
returns table (url text)
language sql stable security definer set search_path = '' as $$
  select coalesce(t.backdrop_url, t.poster_url)
    from public.titles t
   where t.published and coalesce(t.backdrop_url, t.poster_url) is not null
   order by random()
   limit least(greatest(coalesce(p_limit, 24), 1), 60);
$$;
revoke all on function public.public_covers(int) from public;
grant execute on function public.public_covers(int) to anon, authenticated;
