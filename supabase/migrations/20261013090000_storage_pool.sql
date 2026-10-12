-- =====================================================================
-- xyd — pool de stockages vidéo (R2, Backblaze B2, tout S3 compatible)
-- • Les clés d'accès sont chiffrées dans Supabase Vault (jamais relisibles côté client).
-- • Le stockage « env » utilise les secrets R2 de GitHub / des Edge Functions.
-- • Réservation atomique de l'espace à chaque envoi : bascule automatique quand un stockage est plein.
-- Idempotent : peut être rejoué sans risque.
-- =====================================================================

create table if not exists public.storage_backends (
  id               uuid primary key default gen_random_uuid(),
  name             text not null,
  provider         text not null default 's3' check (provider in ('r2', 'b2', 's3')),
  endpoint         text,                       -- ex : https://<compte>.r2.cloudflarestorage.com
  region           text not null default 'auto',
  bucket           text,
  access_key_vault uuid,                       -- id du secret dans vault.secrets
  secret_key_vault uuid,
  is_env           boolean not null default false, -- true = clés dans les secrets GitHub (R2 d'origine)
  quota_bytes      bigint  not null default 9500000000, -- 9,5 Go : marge sous les 10 Go gratuits
  used_bytes       bigint  not null default 0,
  priority         int     not null default 100,        -- petit = rempli en premier
  active           boolean not null default true,
  created_at       timestamptz not null default now()
);

alter table public.episodes add column if not exists storage_id uuid references public.storage_backends(id);
alter table public.titles   add column if not exists storage_id uuid references public.storage_backends(id);

alter table public.storage_backends enable row level security;
drop policy if exists "admin all" on public.storage_backends;
create policy "admin all" on public.storage_backends for all to authenticated
  using ((select public.is_admin())) with check ((select public.is_admin()));
grant select, update on public.storage_backends to authenticated;

-- Stockage d'origine (secrets GitHub) + rattachement des vidéos existantes.
insert into public.storage_backends (name, provider, is_env, priority)
select 'R2 principal', 'r2', true, 1
 where not exists (select 1 from public.storage_backends where is_env);

update public.episodes set storage_id = (select id from public.storage_backends where is_env limit 1) where storage_id is null;
update public.titles   set storage_id = (select id from public.storage_backends where is_env limit 1)
 where storage_id is null and kind = 'film' and video_key is not null;

-- ---------- Comptage ----------
create or replace function public.recompute_storage_usage()
returns void language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is not null and not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  update public.storage_backends b set used_bytes =
      coalesce((select sum(size_bytes) from public.episodes e where e.storage_id = b.id), 0)
    + coalesce((select sum(size_bytes) from public.titles t where t.storage_id = b.id and t.kind = 'film'), 0);
end; $$;
select public.recompute_storage_usage();

-- Réserve p_size octets sur le premier stockage actif qui a la place (ou sur p_storage s'il est imposé).
create or replace function public.reserve_storage(p_size bigint, p_storage uuid default null)
returns uuid language plpgsql security definer set search_path = '' as $$
declare v_id uuid;
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  select id into v_id from public.storage_backends
   where active and used_bytes + p_size <= quota_bytes and (p_storage is null or id = p_storage)
   order by priority, created_at
   limit 1 for update;
  if v_id is null then return null; end if;
  update public.storage_backends set used_bytes = used_bytes + p_size where id = v_id;
  return v_id;
end; $$;

create or replace function public.release_storage(p_storage uuid, p_size bigint)
returns void language plpgsql security definer set search_path = '' as $$
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  update public.storage_backends set used_bytes = greatest(0, used_bytes - p_size) where id = p_storage;
end; $$;

-- ---------- Gestion des comptes (clés chiffrées dans Vault) ----------
create or replace function public.admin_save_storage(
  p_id uuid, p_name text, p_provider text, p_endpoint text, p_region text, p_bucket text,
  p_access_key text, p_secret_key text, p_quota_bytes bigint, p_priority int, p_active boolean)
returns uuid language plpgsql security definer set search_path = '' as $$
declare r public.storage_backends;
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  if p_id is null then
    insert into public.storage_backends (name, provider, endpoint, region, bucket, quota_bytes, priority, active)
    values (p_name, p_provider, p_endpoint, coalesce(nullif(p_region, ''), 'auto'), p_bucket, p_quota_bytes, p_priority, p_active)
    returning * into r;
  else
    update public.storage_backends set
      name = p_name,
      provider = case when is_env then provider else p_provider end,
      endpoint = case when is_env then endpoint else p_endpoint end,
      region = case when is_env then region else coalesce(nullif(p_region, ''), 'auto') end,
      bucket = case when is_env then bucket else p_bucket end,
      quota_bytes = p_quota_bytes, priority = p_priority, active = p_active
    where id = p_id returning * into r;
  end if;
  if r.id is null then raise exception 'Stockage introuvable'; end if;
  if r.is_env then return r.id; end if;

  -- Clés : création ou remplacement (une valeur vide = inchangée).
  if coalesce(p_access_key, '') <> '' then
    if r.access_key_vault is null then
      update public.storage_backends set access_key_vault = vault.create_secret(p_access_key, 'xyd_storage_' || r.id || '_ak', 'xyd storage access key')
       where id = r.id;
    else
      perform vault.update_secret(r.access_key_vault, p_access_key);
    end if;
  end if;
  if coalesce(p_secret_key, '') <> '' then
    if r.secret_key_vault is null then
      update public.storage_backends set secret_key_vault = vault.create_secret(p_secret_key, 'xyd_storage_' || r.id || '_sk', 'xyd storage secret key')
       where id = r.id;
    else
      perform vault.update_secret(r.secret_key_vault, p_secret_key);
    end if;
  end if;
  return r.id;
end; $$;

create or replace function public.admin_delete_storage(p_id uuid)
returns void language plpgsql security definer set search_path = '' as $$
declare r public.storage_backends;
begin
  if not public.is_admin() then raise exception 'Réservé aux administrateurs'; end if;
  select * into r from public.storage_backends where id = p_id;
  if r.id is null then return; end if;
  if r.is_env then raise exception 'Le stockage principal ne peut pas être supprimé'; end if;
  if exists (select 1 from public.episodes where storage_id = p_id)
     or exists (select 1 from public.titles where storage_id = p_id) then
    raise exception 'Ce stockage contient encore des vidéos';
  end if;
  delete from public.storage_backends where id = p_id;
  delete from vault.secrets where id in (r.access_key_vault, r.secret_key_vault);
end; $$;

-- Lecture des identifiants en clair : uniquement pour les Edge Functions (service_role).
create or replace function public.storage_credentials(p_id uuid)
returns table (is_env boolean, endpoint text, region text, bucket text, access_key text, secret_key text)
language sql security definer set search_path = '' as $$
  select b.is_env, b.endpoint, b.region, b.bucket,
         (select decrypted_secret from vault.decrypted_secrets where id = b.access_key_vault),
         (select decrypted_secret from vault.decrypted_secrets where id = b.secret_key_vault)
    from public.storage_backends b where b.id = p_id;
$$;

revoke all on function public.recompute_storage_usage() from public, anon;
revoke all on function public.reserve_storage(bigint, uuid) from public, anon;
revoke all on function public.release_storage(uuid, bigint) from public, anon;
revoke all on function public.admin_save_storage(uuid, text, text, text, text, text, text, text, bigint, int, boolean) from public, anon;
revoke all on function public.admin_delete_storage(uuid) from public, anon;
revoke all on function public.storage_credentials(uuid) from public, anon, authenticated;
grant execute on function public.recompute_storage_usage() to authenticated;
grant execute on function public.reserve_storage(bigint, uuid) to authenticated;
grant execute on function public.release_storage(uuid, bigint) to authenticated;
grant execute on function public.admin_save_storage(uuid, text, text, text, text, text, text, text, bigint, int, boolean) to authenticated;
grant execute on function public.admin_delete_storage(uuid) to authenticated;
grant execute on function public.storage_credentials(uuid) to service_role;
