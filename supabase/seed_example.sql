-- Exemple d'ajout de contenu (à adapter puis exécuter dans le SQL Editor).
-- Les fichiers vidéo doivent d'abord être envoyés dans le bucket R2 au chemin indiqué par video_key.

-- Un film
insert into public.titles (kind, name, synopsis, year, poster_url, backdrop_url, video_key, duration_s, size_bytes)
values ('film', 'Mon film', 'Résumé du film.', 2024,
        'https://xydhub.tech/img/mon-film-poster.jpg', 'https://xydhub.tech/img/mon-film.jpg',
        'films/mon-film.mp4', 6300, 180000000);

-- Une série, une saison, deux épisodes
with t as (
  insert into public.titles (kind, name, synopsis, year, poster_url, backdrop_url)
  values ('serie', 'Ma série', 'Résumé de la série.', 2023,
          'https://xydhub.tech/img/ma-serie-poster.jpg', 'https://xydhub.tech/img/ma-serie.jpg')
  returning id
), s as (
  insert into public.seasons (title_id, number, name) select id, 1, 'Saison 1' from t returning id
)
insert into public.episodes (season_id, number, name, video_key, duration_s, size_bytes)
select s.id, e.number, e.name, e.key, e.dur, e.size from s,
  (values (1, 'Pilote',   'series/ma-serie/s01/e01.mp4', 2700, 150000000),
          (2, 'Épisode 2','series/ma-serie/s01/e02.mp4', 2640, 145000000)) as e(number, name, key, dur, size);
