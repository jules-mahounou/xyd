#!/usr/bin/env python3
"""
Bilan des stockages vidéo (lecture seule, aucun fichier lu ni modifié) :
ordre de remplissage, quotas, nombre de vidéos, clés valides, CORS pour un envoi depuis l'admin.
"""
import os, sys, urllib.request
from collections import Counter

sys.path.insert(0, os.path.dirname(__file__))
import convert_videos as cv  # rest(), storage()

ORIGIN = "https://xydhub.tech"


def count_videos():
    n, size = Counter(), Counter()
    for table, extra in (("titles", "&kind=eq.film"), ("episodes", "")):
        for off in range(0, 100000, 1000):
            page = cv.rest("GET", f"{table}?select=storage_id,size_bytes&video_key=not.is.null{extra}&offset={off}&limit=1000")
            for r in page:
                n[r["storage_id"]] += 1
                size[r["storage_id"]] += r["size_bytes"] or 0
            if len(page) < 1000:
                break
    return n, size


def cors_ok(client, bucket):
    """Pré-vol CORS d'un PUT signé, comme le fait le navigateur de l'admin."""
    url = client.generate_presigned_url("put_object", Params={"Bucket": bucket, "Key": "__cors_test__"}, ExpiresIn=300)
    req = urllib.request.Request(url, method="OPTIONS")
    req.add_header("Origin", ORIGIN)
    req.add_header("Access-Control-Request-Method", "PUT")
    req.add_header("Access-Control-Request-Headers", "content-type")
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            allow = r.headers.get("Access-Control-Allow-Origin")
            return f"OK ({allow})" if allow in ("*", ORIGIN) else f"NON (réponse {r.status}, aucune autorisation pour {ORIGIN})"
    except urllib.error.HTTPError as e:
        return f"NON (HTTP {e.code})"
    except Exception as e:
        return f"NON ({type(e).__name__})"


def real_usage(c, bucket):
    """Ce que le fournisseur facture : fichiers visibles + anciennes versions cachées + envois inachevés."""
    live = old = 0
    nlive = nold = 0
    try:
        for page in c.get_paginator("list_object_versions").paginate(Bucket=bucket):
            for v in page.get("Versions", []):
                if v.get("IsLatest"):
                    live += v["Size"]; nlive += 1
                else:
                    old += v["Size"]; nold += 1
    except Exception:  # R2 : pas de versions
        for page in c.get_paginator("list_objects_v2").paginate(Bucket=bucket):
            for v in page.get("Contents", []):
                live += v["Size"]; nlive += 1
    parts = 0
    try:
        mp = c.list_multipart_uploads(Bucket=bucket).get("Uploads", [])
        for u in mp:
            for pg in c.get_paginator("list_parts").paginate(Bucket=bucket, Key=u["Key"], UploadId=u["UploadId"]):
                parts += sum(p["Size"] for p in pg.get("Parts", []))
        nmp = len(mp)
    except Exception:
        nmp = 0
    total = live + old + parts
    return (f"{total / 1e9:.2f} Go = {live / 1e9:.2f} Go de fichiers ({nlive})"
            f" + {old / 1e9:.2f} Go d'anciennes versions cachées ({nold})"
            f" + {parts / 1e9:.2f} Go d'envois inachevés ({nmp})")


def referenced_keys():
    """Clés vidéo utilisées par l'app, par stockage : jamais touchées par le nettoyage."""
    keys = {}
    for table, extra in (("titles", "&kind=eq.film"), ("episodes", "")):
        for off in range(0, 100000, 1000):
            page = cv.rest("GET", f"{table}?select=storage_id,video_key&video_key=not.is.null{extra}&offset={off}&limit=1000")
            for r in page:
                keys.setdefault(r["storage_id"], set()).add(r["video_key"])
            if len(page) < 1000:
                break
    return keys


def purge_hidden(c, bucket, keep):
    """Efface les anciennes versions cachées et les marqueurs de suppression (fichiers déjà supprimés/remplacés)."""
    victims, size = [], 0
    for page in c.get_paginator("list_object_versions").paginate(Bucket=bucket):
        for v in page.get("Versions", []):
            if not v.get("IsLatest") and v["Key"] not in keep:
                victims.append({"Key": v["Key"], "VersionId": v["VersionId"]}); size += v["Size"]
        for m in page.get("DeleteMarkers", []):
            if m["Key"] not in keep:
                victims.append({"Key": m["Key"], "VersionId": m["VersionId"]})
    done = 0
    for v in victims:  # une par une : B2 ne gère pas toujours DeleteObjects
        try:
            c.delete_object(Bucket=bucket, Key=v["Key"], VersionId=v["VersionId"]); done += 1
        except Exception as e:
            print(f"      ✗ {v['Key']} : {e}")
    return done, len(victims), size


def main():
    rows = cv.rest("GET", "storage_backends?select=id,name,provider,endpoint,region,bucket,is_env,quota_bytes,used_bytes,priority,active,created_at,billable&order=priority,created_at")
    n, size = count_videos()
    purge = os.environ.get("PURGE_VERSIONS") == "1"
    refs = referenced_keys() if purge else {}
    print(f"{len(rows)} stockage(s), dans l'ordre de remplissage automatique :\n")
    for i, x in enumerate(rows, 1):
        free = (x["quota_bytes"] or 0) - (x["used_bytes"] or 0)
        print(f"{i:>2}. {x['name']} · {x['provider']} · priorité {x['priority']} · {'ACTIF' if x['active'] else 'inactif'} · créé le {x['created_at'][:10]}")
        print(f"    {('secrets GitHub' if x['is_env'] else (x['endpoint'] or '?') + ' / ' + (x['bucket'] or '?'))} · région {x['region']}")
        print(f"    rempli {x['used_bytes'] / 1e9:.2f} / {x['quota_bytes'] / 1e9:.2f} Go (libre {max(0, free) / 1e9:.2f} Go) · {n[x['id']] + (n[None] if x['is_env'] else 0)} vidéo(s), {(size[x['id']] + (size[None] if x['is_env'] else 0)) / 1e9:.2f} Go réels")
        try:
            c, bucket = cv.storage(None if x["is_env"] else x["id"])
            c.head_bucket(Bucket=bucket)
            keys = "OK"
            cors = cors_ok(c, bucket)
            real = real_usage(c, bucket)
        except Exception as e:
            keys, cors = f"ÉCHEC ({type(e).__name__}: {str(e)[:120]})", "-"
        print(f"    clés / bucket : {keys} · CORS envoi depuis l'admin : {cors}")
        if keys == "OK":
            if purge and x["provider"] == "b2":
                keep = refs.get(x["id"], set()) | (refs.get(None, set()) if x["is_env"] else set())
                done, total, freed = purge_hidden(c, bucket, keep)
                print(f"    🧹 nettoyage : {done}/{total} élément(s) caché(s) effacé(s) · {freed / 1e9:.2f} Go libérés")
                real = real_usage(c, bucket)
            print(f"    occupé réellement dans le bucket : {real}")
        print()


if __name__ == "__main__":
    main()
