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


def main():
    rows = cv.rest("GET", "storage_backends?select=id,name,provider,endpoint,region,bucket,is_env,quota_bytes,used_bytes,priority,active,created_at,billable&order=priority,created_at")
    n, size = count_videos()
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
        except Exception as e:
            keys, cors = f"ÉCHEC ({type(e).__name__}: {str(e)[:120]})", "-"
        print(f"    clés / bucket : {keys} · CORS envoi depuis l'admin : {cors}\n")


if __name__ == "__main__":
    main()
