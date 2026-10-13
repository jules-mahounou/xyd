-- =====================================================================
-- xyd — limitation intelligente des téléchargements (protège les quotas gratuits)
--
-- Couche 1 — stockage : chaque stockage facturable (B2, S3 tiers) a un budget journalier
--            (octets sortants, transactions classe B / C). Plusieurs buckets d'un même compte
--            partagent le budget via billing_group. R2 = sortie gratuite → non facturable.
-- Couche 2 — utilisateur : quota en DURÉE de vidéo (jour + mois), nombre de fichiers par jour,
--            anti-rafale (N lancements en X minutes).
-- Couche 3 — équité : un utilisateur ne prend pas plus d'une part (50 %) du budget d'un stockage
--            facturable… sauf en fin de journée UTC, où le reste serait perdu de toute façon.
-- Couche 4 — gros consommateurs : au-delà de X fois la médiane sur 7 jours, quota réduit.
-- Les reprises (même vidéo, même jour) ne sont pas recomptées : seule une nouvelle
-- vidéo, ou un re-téléchargement complet, consomme du quota.
-- Jour = jour UTC (comme les plafonds Backblaze). Idempotent.
-- =====================================================================

-- ---------- Réglages par stockage (null = valeur par défaut selon le fournisseur) ----------
alter table public.storage_backends add column if not exists billable           boolean; -- null : tout sauf R2
alter table public.storage_backends add column if not exists daily_egress_bytes bigint;  -- null : 900 Mo (1 Go gratuit B2)
alter table public.storage_backends add column if not exists daily_class_b      int;     -- null : 2 250 (2 500 gratuites)
alter table public.storage_backends add column if not exists daily_class_c      int;     -- null : 2 250
alter table public.storage_backends add column if not exists billing_group      text;    -- même texte = même compte, budget partagé
alter table public.storage_backends add column if not exists url_ttl            int;     -- durée des URL signées (s)

-- Quota personnel en minutes/jour (null = réglage global, 0 = téléchargements bloqués).
alter table public.profiles add column if not exists daily_minutes int;

-- ---------- Réglages globaux ----------
insert into public.app_settings (key, value) values ('limits', '{}'::jsonb) on conflict (key) do nothing;

create or replace function public.download_limits()
returns jsonb language sql stable security definer set search_path = '' as $$
  select jsonb_build_object(
    'enabled', true,
    'user_daily_minutes', 480,      -- 8 h de vidéo par jour et par personne
    'user_monthly_minutes', 6000,   -- 100 h par mois
    'user_daily_files', 20,
    'burst_files', 6,               -- au plus 6 lancements…
    'burst_window_min', 10,         -- …en 10 minutes
    'resume_grants', 12,            -- reprises gratuites par vidéo et par jour
    'billable_user_share', 0.5,     -- part max d'un budget facturable pour une personne
    'late_release_hours', 3,        -- les 3 dernières heures UTC : part levée
    'heavy_factor', 3,              -- gros consommateur : > 3 × la médiane sur 7 jours…
    'heavy_min_minutes', 900,       -- …et plus de 15 h sur 7 jours
    'heavy_penalty', 0.5,           -- quota journalier multiplié par 0,5
    'admins_exempt', false          -- les admins sont limités comme tout le monde
  ) || coalesce((select value from public.app_settings where key = 'limits'), '{}'::jsonb);
$$;

-- ---------- Journal ----------
create table if not exists public.download_grants (
  user_id        uuid not null references auth.users(id) on delete cascade,
  media_id       uuid not null,
  day            date not null,
  storage_id     uuid,
  bill_key       text,
  billable       boolean not null default false,
  bytes          bigint  not null default 0,   -- octets comptés (estimation de la sortie)
  minutes        numeric not null default 0,   -- durée de vidéo comptée
  charges        int     not null default 1,   -- nombre de fois où la vidéo a été comptée
  regrants       int     not null default 0,   -- reprises depuis le dernier comptage
  hwm            bigint  not null default 0,   -- position max signalée par l'app
  created_at     timestamptz not null default now(),
  last_charge_at timestamptz not null default now(),
  last_at        timestamptz not null default now(),
  primary key (user_id, media_id, day)
);
create index if not exists download_grants_day_idx on public.download_grants(day);
create index if not exists download_grants_bill_idx on public.download_grants(bill_key, day);

create table if not exists public.storage_usage_daily (
  bill_key     text not null,
  day          date not null,
  egress_bytes bigint not null default 0,
  class_b      int    not null default 0,
  class_c      int    not null default 0,
  downloads    int    not null default 0,
  refused      int    not null default 0,
  primary key (bill_key, day)
);

create table if not exists public.download_refusals (
  user_id uuid not null references auth.users(id) on delete cascade,
  day     date not null,
  reason  text not null,
  count   int  not null default 0,
  last_at timestamptz not null default now(),
  primary key (user_id, day, reason)
);

alter table public.download_grants     enable row level security;
alter table public.storage_usage_daily enable row level security;
alter table public.download_refusals   enable row level security;
drop policy if exists "own or admin read" on public.download_grants;
create policy "own or admin read" on public.download_grants for select to authenticated
  using (user_id = (select auth.uid()) or (select public.is_admin()));
drop policy if exists "admin read" on public.storage_usage_daily;
create policy "admin read" on public.storage_usage_daily for select to authenticated using ((select public.is_admin()));
drop policy if exists "own or admin read" on public.download_refusals;
create policy "own or admin read" on public.download_refusals for select to authenticated
  using (user_id = (select auth.uid()) or (select public.is_admin()));
grant select on public.download_grants, public.storage_usage_daily, public.download_refusals to authenticated;

-- ---------- Outils ----------
create or replace function public.dl_today() returns date language sql stable set search_path = '' as $$
  select (now() at time zone 'utc')::date;
$$;

-- Gros consommateur : minutes sur 7 jours > max(seuil, facteur × médiane), avec au moins 3 personnes actives.
create or replace function public.dl_is_heavy(p_uid uuid, s jsonb)
returns boolean language sql stable security definer set search_path = '' as $$
  with per as (
    select user_id, sum(minutes) m from public.download_grants
     where day > public.dl_today() - 7 group by user_id
  )
  select (select count(*) from per) >= 3
     and coalesce((select m from per where user_id = p_uid), 0) >
         greatest((s->>'heavy_min_minutes')::numeric,
                  (s->>'heavy_factor')::numeric * coalesce((select percentile_cont(0.5) within group (order by m) from per), 0));
$$;

-- Quota journalier effectif d'une personne (override admin, pénalité gros consommateur).
create or replace function public.dl_daily_quota(p_uid uuid, s jsonb)
returns numeric language sql stable security definer set search_path = '' as $$
  select coalesce((select daily_minutes from public.profiles where user_id = p_uid), (s->>'user_daily_minutes')::numeric)
       * case when public.dl_is_heavy(p_uid, s) then (s->>'heavy_penalty')::numeric else 1 end;
$$;

-- 90 → « 1 h 30 », 45 → « 45 min ».
create or replace function public.dl_fmt_minutes(m numeric) returns text language sql immutable set search_path = '' as $$
  select case when m >= 60 then floor(m / 60)::text || ' h' || case when round(m)::int % 60 > 0 then ' ' || lpad((round(m)::int % 60)::text, 2, '0') else '' end
              else round(m)::text || ' min' end;
$$;

create or replace function public.dl_refuse(p_uid uuid, p_bill text, p_reason text, p_status int, p_message text, p_retry timestamptz)
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
  insert into public.download_refusals as r (user_id, day, reason, count) values (p_uid, public.dl_today(), p_reason, 1)
  on conflict (user_id, day, reason) do update set count = r.count + 1, last_at = now();
  if p_bill is not null then
    update public.storage_usage_daily set refused = refused + 1 where bill_key = p_bill and day = public.dl_today();
  end if;
  return jsonb_build_object('ok', false, 'status', p_status, 'reason', p_reason, 'message', p_message, 'retry_at', p_retry);
end; $$;

-- ---------- Autorisation d'un téléchargement (appelée par l'Edge Function video-url) ----------
-- p_offset : octets déjà présents sur le téléphone (null = ancienne version de l'app).
create or replace function public.grant_download(p_media uuid, p_offset bigint default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare
  v_uid     uuid := auth.uid();
  s         jsonb := public.download_limits();
  v_today   date := public.dl_today();
  v_reset   timestamptz := (public.dl_today() + 1)::timestamp at time zone 'utc';
  v_key     text; v_storage uuid; v_size bigint; v_dur int;
  b         public.storage_backends;
  v_bill    text; v_billable boolean := false;
  v_cap     bigint; v_bcap int; v_ttl int;
  g         public.download_grants;
  u         public.storage_usage_daily;
  v_cost    bigint; v_min numeric;
  v_on      boolean := coalesce((s->>'enabled')::boolean, true);
  v_userchk boolean;
  v_daily numeric; v_used numeric; v_used_m numeric; v_files int; v_burst int; v_mine bigint;
  v_late    timestamptz;
  v_oldest  timestamptz;
begin
  if v_uid is null then return jsonb_build_object('ok', false, 'status', 401, 'message', 'Non connecté'); end if;
  if not public.is_active() then return jsonb_build_object('ok', false, 'status', 403, 'message', 'Accès suspendu'); end if;

  -- Vidéo : épisode, sinon film publié.
  select e.video_key, e.storage_id, e.size_bytes, e.duration_s into v_key, v_storage, v_size, v_dur
    from public.episodes e where e.id = p_media;
  if v_key is null then
    select t.video_key, t.storage_id, t.size_bytes, t.duration_s into v_key, v_storage, v_size, v_dur
      from public.titles t where t.id = p_media and t.kind = 'film' and t.published;
  end if;
  if v_key is null then return jsonb_build_object('ok', false, 'status', 404, 'message', 'Vidéo introuvable'); end if;
  -- Taille ou durée inconnue : estimation à ~1,6 Mbit/s.
  v_size := coalesce(nullif(v_size, 0), greatest(coalesce(v_dur, 0), 1500)::bigint * 200000);
  v_dur  := coalesce(nullif(v_dur, 0), (v_size / 200000)::int);

  if v_storage is null then select * into b from public.storage_backends where is_env limit 1;
  else select * into b from public.storage_backends where id = v_storage; end if;
  if b.id is not null then
    v_billable := coalesce(b.billable, b.provider <> 'r2');
    v_bill := coalesce(nullif(trim(b.billing_group), ''), b.id::text);
    v_cap  := coalesce(b.daily_egress_bytes, 900000000);
    v_bcap := coalesce(b.daily_class_b, 2250);
  end if;
  v_ttl := coalesce(b.url_ttl, case when v_billable then 10800 else 21600 end);
  v_userchk := v_on and not (coalesce((s->>'admins_exempt')::boolean, false) and public.is_admin());

  perform pg_advisory_xact_lock(hashtextextended('xyd-dl:' || v_uid::text, 0));
  if v_billable and v_on then
    insert into public.storage_usage_daily (bill_key, day) values (v_bill, v_today) on conflict do nothing;
    select * into u from public.storage_usage_daily where bill_key = v_bill and day = v_today for update;
  end if;

  select * into g from public.download_grants where user_id = v_uid and media_id = p_media and day = v_today for update;

  -- Reprise (même vidéo, même jour) : gratuite, sauf re-téléchargement complet ou trop de reprises.
  if found and g.regrants < (s->>'resume_grants')::int and not (p_offset = 0 and g.hwm > 0) then
    if v_billable and v_on then
      if u.class_b + 1 > v_bcap then
        return public.dl_refuse(v_uid, v_bill, 'transactions', 429,
          'Ce stockage a atteint son nombre de requêtes gratuites pour aujourd''hui. Le téléchargement reprendra demain.', v_reset);
      end if;
      update public.storage_usage_daily set class_b = class_b + 1 where bill_key = v_bill and day = v_today;
    end if;
    update public.download_grants set regrants = regrants + 1, hwm = greatest(hwm, coalesce(p_offset, 0)), last_at = now()
     where user_id = v_uid and media_id = p_media and day = v_today;
    return jsonb_build_object('ok', true, 'resume', true, 'storage_id', v_storage, 'video_key', v_key, 'ttl', v_ttl);
  end if;

  -- Nouveau comptage. Reprise d'un téléchargement commencé un jour précédent : seul le reste compte.
  v_cost := v_size;
  if g.user_id is null and coalesce(p_offset, 0) > 0 and exists (
       select 1 from public.download_grants where user_id = v_uid and media_id = p_media and day >= v_today - 3) then
    v_cost := greatest(0, v_size - least(p_offset, v_size));
  end if;
  v_min := round(v_dur / 60.0 * v_cost / v_size, 2);

  -- Couches 2 et 4 : utilisateur.
  if v_userchk then
    v_daily := public.dl_daily_quota(v_uid, s);
    if v_daily <= 0 then
      return public.dl_refuse(v_uid, null, 'blocked', 403, 'Les téléchargements sont désactivés pour ton compte. Contacte l''administrateur.', null);
    end if;
    select count(*), min(last_charge_at) into v_burst, v_oldest from public.download_grants
     where user_id = v_uid and last_charge_at > now() - make_interval(mins => (s->>'burst_window_min')::int);
    if v_burst >= (s->>'burst_files')::int then
      return public.dl_refuse(v_uid, null, 'burst', 429,
        format('Trop de téléchargements lancés d''un coup. Patiente quelques minutes (%s max en %s min).', s->>'burst_files', s->>'burst_window_min'),
        v_oldest + make_interval(mins => (s->>'burst_window_min')::int));
    end if;
    select count(*), coalesce(sum(minutes), 0) into v_files, v_used from public.download_grants where user_id = v_uid and day = v_today;
    if v_files >= (s->>'user_daily_files')::int then
      return public.dl_refuse(v_uid, null, 'files', 429,
        format('Limite de %s vidéos par jour atteinte. Tu pourras continuer demain.', s->>'user_daily_files'), v_reset);
    end if;
    -- La première vidéo du jour passe toujours (même un long film).
    if v_used > 0 and v_used + v_min > v_daily then
      return public.dl_refuse(v_uid, null, 'daily', 429,
        format('Limite du jour atteinte : %s de vidéo par jour (%s utilisées). Tu pourras continuer demain.',
          public.dl_fmt_minutes(v_daily), public.dl_fmt_minutes(v_used)),
        v_reset);
    end if;
    select coalesce(sum(minutes), 0) into v_used_m from public.download_grants
     where user_id = v_uid and day >= date_trunc('month', v_today)::date;
    if v_used_m > 0 and v_used_m + v_min > (s->>'user_monthly_minutes')::numeric then
      return public.dl_refuse(v_uid, null, 'monthly', 429,
        format('Limite du mois atteinte (%s h de vidéo). Elle se renouvelle le 1er du mois.', round((s->>'user_monthly_minutes')::numeric / 60)),
        (date_trunc('month', v_today) + interval '1 month')::timestamp at time zone 'utc');
    end if;
  end if;

  -- Couches 1 et 3 : stockage facturable.
  if v_billable and v_on then
    if u.egress_bytes + v_cost > v_cap or u.class_b + 1 > v_bcap then
      return public.dl_refuse(v_uid, v_bill, 'budget', 429,
        'Cette vidéo est sur un stockage au quota gratuit limité, épuisé pour aujourd''hui. Elle sera disponible demain.', v_reset);
    end if;
    v_late := v_reset - make_interval(hours => (s->>'late_release_hours')::int);
    if v_userchk and now() < v_late then
      select coalesce(sum(bytes), 0) into v_mine from public.download_grants
       where user_id = v_uid and bill_key = v_bill and day = v_today;
      if v_mine > 0 and v_mine + v_cost > (s->>'billable_user_share')::numeric * v_cap then
        return public.dl_refuse(v_uid, v_bill, 'share', 429,
          'Tu as utilisé ta part du quota partagé de ce stockage aujourd''hui : on garde le reste pour les autres. Réessaie en fin de journée ou demain.', v_late);
      end if;
    end if;
    update public.storage_usage_daily set egress_bytes = egress_bytes + v_cost, class_b = class_b + 1, downloads = downloads + 1
     where bill_key = v_bill and day = v_today;
  end if;

  insert into public.download_grants as d (user_id, media_id, day, storage_id, bill_key, billable, bytes, minutes, hwm)
  values (v_uid, p_media, v_today, v_storage, v_bill, v_billable, v_cost, v_min, coalesce(p_offset, 0))
  on conflict (user_id, media_id, day) do update set
    bytes = d.bytes + excluded.bytes, minutes = d.minutes + excluded.minutes, charges = d.charges + 1,
    regrants = 0, hwm = excluded.hwm, last_charge_at = now(), last_at = now();

  -- Ménage occasionnel : 90 jours d'historique.
  if random() < 0.02 then
    delete from public.download_grants where day < v_today - 90;
    delete from public.download_refusals where day < v_today - 90;
    delete from public.storage_usage_daily where day < v_today - 90;
  end if;

  return jsonb_build_object('ok', true, 'resume', false, 'storage_id', v_storage, 'video_key', v_key, 'ttl', v_ttl,
    'minutes_today', coalesce(v_used, 0) + v_min, 'daily_minutes', v_daily);
end; $$;

-- ---------- Opérations d'administration comptées (ex. CORS B2 : transactions classe C) ----------
create or replace function public.meter_storage_ops(p_storage uuid, p_class_b int, p_class_c int)
returns boolean language plpgsql security definer set search_path = '' as $$
declare b public.storage_backends; v_bill text; u public.storage_usage_daily;
begin
  if auth.uid() is not null and not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  select * into b from public.storage_backends where id = p_storage;
  if b.id is null or not coalesce(b.billable, b.provider <> 'r2') then return true; end if;
  v_bill := coalesce(nullif(trim(b.billing_group), ''), b.id::text);
  insert into public.storage_usage_daily (bill_key, day) values (v_bill, public.dl_today()) on conflict do nothing;
  select * into u from public.storage_usage_daily where bill_key = v_bill and day = public.dl_today() for update;
  if u.class_b + p_class_b > coalesce(b.daily_class_b, 2250) or u.class_c + p_class_c > coalesce(b.daily_class_c, 2250) then
    return false;
  end if;
  update public.storage_usage_daily set class_b = class_b + p_class_b, class_c = class_c + p_class_c
   where bill_key = v_bill and day = public.dl_today();
  return true;
end; $$;

-- ---------- Quota de la personne connectée (affiché dans l'app) ----------
create or replace function public.my_download_quota()
returns jsonb language plpgsql stable security definer set search_path = '' as $$
declare s jsonb := public.download_limits(); v_uid uuid := auth.uid(); v_used numeric; v_used_m numeric; v_files int;
begin
  if v_uid is null then return null; end if;
  select count(*), coalesce(sum(minutes), 0) into v_files, v_used from public.download_grants where user_id = v_uid and day = public.dl_today();
  select coalesce(sum(minutes), 0) into v_used_m from public.download_grants
   where user_id = v_uid and day >= date_trunc('month', public.dl_today())::date;
  return jsonb_build_object(
    'enabled', coalesce((s->>'enabled')::boolean, true),
    'daily_minutes', public.dl_daily_quota(v_uid, s), 'used_minutes', v_used,
    'monthly_minutes', (s->>'user_monthly_minutes')::numeric, 'used_month_minutes', v_used_m,
    'daily_files', (s->>'user_daily_files')::int, 'used_files', v_files,
    'heavy', public.dl_is_heavy(v_uid, s),
    'reset_at', (public.dl_today() + 1)::timestamp at time zone 'utc');
end; $$;

-- ---------- Statistiques admin : qui télécharge quoi ----------
create or replace function public.admin_download_stats(p_days int default 7)
returns table (user_id uuid, email text, name text, daily_override int,
               minutes_today numeric, files_today bigint, billable_bytes_today numeric,
               minutes_period numeric, files_period bigint, bytes_period numeric,
               refused_today bigint, heavy boolean, daily_quota numeric)
language plpgsql stable security definer set search_path = '' as $$
declare s jsonb := public.download_limits();
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  return query
  select p.user_id, p.email, p.name, p.daily_minutes,
         coalesce(sum(g.minutes) filter (where g.day = public.dl_today()), 0),
         count(g.media_id) filter (where g.day = public.dl_today()),
         coalesce(sum(g.bytes) filter (where g.day = public.dl_today() and g.billable), 0),
         coalesce(sum(g.minutes), 0), count(g.media_id), coalesce(sum(g.bytes), 0),
         coalesce((select sum(r.count) from public.download_refusals r where r.user_id = p.user_id and r.day = public.dl_today()), 0)::bigint,
         public.dl_is_heavy(p.user_id, s), public.dl_daily_quota(p.user_id, s)
    from public.profiles p
    left join public.download_grants g on g.user_id = p.user_id and g.day > public.dl_today() - greatest(1, p_days)
   group by p.user_id, p.email, p.name, p.daily_minutes
   order by 8 desc, 5 desc;
end; $$;

revoke all on function public.download_limits() from public, anon;
revoke all on function public.dl_is_heavy(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.dl_daily_quota(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.dl_refuse(uuid, text, text, int, text, timestamptz) from public, anon, authenticated;
revoke all on function public.grant_download(uuid, bigint) from public, anon;
revoke all on function public.meter_storage_ops(uuid, int, int) from public, anon;
revoke all on function public.my_download_quota() from public, anon;
revoke all on function public.admin_download_stats(int) from public, anon;
grant execute on function public.download_limits() to authenticated;
grant execute on function public.grant_download(uuid, bigint) to authenticated;
grant execute on function public.meter_storage_ops(uuid, int, int) to authenticated, service_role;
grant execute on function public.my_download_quota() to authenticated;
grant execute on function public.admin_download_stats(int) to authenticated;
