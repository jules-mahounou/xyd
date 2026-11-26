-- Correctif (une seule fois) : le formulaire « + Stockage » cochait « Facturable » par défaut,
-- même pour Cloudflare R2. Les R2 se retrouvaient avec un budget de 900 Mo/jour et étaient
-- traités comme payants. R2 = sortie gratuite : on les repasse en non facturables.
-- Exécuté une seule fois (marqueur dans app_settings) : un choix fait ensuite à la main est respecté.
do $$
begin
  if not exists (select 1 from public.app_settings where key = 'fix_r2_billable_20261126') then
    update public.storage_backends set billable = false where provider = 'r2' and billable is true;
    insert into public.app_settings (key, value) values ('fix_r2_billable_20261126', '{"done": true}'::jsonb);
  end if;
end $$;
