#!/usr/bin/env python3
"""
Convertit en MP4 (H.264 + AAC) les vidéos du catalogue que l'app ne sait pas lire (AVI, WMV, FLV, MPG…).

Pour chaque vidéo concernée (film ou épisode) :
  1. téléchargement depuis son stockage (R2 d'origine, ou stockage du pool via storage_credentials) ;
  2. conversion : simple ré-emballage si la vidéo est déjà en H.264 (rapide, sans perte), sinon ré-encodage ;
  3. vérification (durée identique à ±2 %) ;
  4. envoi du MP4 à côté de l'original, contrôle de la taille envoyée ;
  5. mise à jour de la ligne en base (video_key, size_bytes, duration_s), seulement si elle n'a pas bougé entre-temps ;
  6. suppression de l'original, en dernier.
Au moindre échec, l'original reste en place et la base n'est pas touchée : relancer suffit.

Variables : SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY, R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, R2_SECRET_ACCESS_KEY, R2_BUCKET,
            DRY_RUN (1 = liste seulement), SHARD / SHARDS (répartition entre jobs), MAX_MINUTES (budget de temps).
"""
import json, os, subprocess, sys, time, urllib.parse, urllib.request, zlib
from pathlib import Path

import boto3
from botocore.config import Config

URL = os.environ["SUPABASE_URL"].rstrip("/")
KEY = os.environ["SUPABASE_SERVICE_ROLE_KEY"]
DRY = os.environ.get("DRY_RUN", "1") == "1"
SHARD, SHARDS = int(os.environ.get("SHARD", "0")), int(os.environ.get("SHARDS", "1"))
DEADLINE = time.time() + 60 * float(os.environ.get("MAX_MINUTES", "320"))
OK_EXT = (".mp4", ".m4v", ".mov", ".mkv", ".webm")  # formats lus par l'app
TMP = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "xyd-convert"
TMP.mkdir(parents=True, exist_ok=True)


def rest(method, path, body=None, prefer=None):
    req = urllib.request.Request(f"{URL}/rest/v1/{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header("apikey", KEY)
    req.add_header("Authorization", f"Bearer {KEY}")
    req.add_header("Content-Type", "application/json")
    if prefer:
        req.add_header("Prefer", prefer)
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def todo():
    """Vidéos dont l'extension n'est pas lisible par l'app."""
    rows = []
    for table, extra in (("titles", "&kind=eq.film"), ("episodes", "")):
        for from_ in range(0, 100000, 1000):
            q = f"{table}?select=id,video_key,storage_id,size_bytes,duration_s&video_key=not.is.null{extra}&order=id&offset={from_}&limit=1000"
            page = rest("GET", q)
            rows += [dict(r, table=table) for r in page if not r["video_key"].lower().endswith(OK_EXT)]
            if len(page) < 1000:
                break
    return [r for r in rows if zlib.crc32(r["id"].encode()) % SHARDS == SHARD]


_clients = {}


def storage(storage_id):
    """(client S3, bucket) du stockage d'une vidéo. None = stockage d'origine (secrets R2)."""
    if storage_id in _clients:
        return _clients[storage_id]
    row = None
    if storage_id:
        data = rest("POST", "rpc/storage_credentials", {"p_id": storage_id})
        row = data[0] if isinstance(data, list) else data
        if not row:
            raise RuntimeError("stockage introuvable")
    cfg = Config(s3={"addressing_style": "path"}, retries={"max_attempts": 5, "mode": "standard"},
                 signature_version="s3v4")
    if not row or row.get("is_env"):
        c = boto3.client("s3", endpoint_url=f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
                         aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                         aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"], region_name="auto", config=cfg)
        res = (c, os.environ["R2_BUCKET"])
    else:
        c = boto3.client("s3", endpoint_url=row["endpoint"].rstrip("/"), aws_access_key_id=row["access_key"],
                         aws_secret_access_key=row["secret_key"], region_name=row.get("region") or "auto", config=cfg)
        res = (c, row["bucket"])
    _clients[storage_id] = res
    return res


def probe(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", str(path)],
                         capture_output=True, text=True, check=True).stdout
    j = json.loads(out)
    v = next((s for s in j["streams"] if s["codec_type"] == "video"), None)
    a = next((s for s in j["streams"] if s["codec_type"] == "audio"), None)
    return v, a, float(j["format"].get("duration") or 0)


def convert(src, dst):
    v, a, dur = probe(src)
    if not v:
        raise RuntimeError("aucune piste vidéo")
    copy_v = v["codec_name"] == "h264" and v.get("pix_fmt") in ("yuv420p", "yuvj420p")
    copy_a = a is not None and a["codec_name"] in ("aac", "mp3")
    cmd = ["ffmpeg", "-y", "-v", "error", "-fflags", "+genpts", "-i", str(src), "-map", "0:v:0", "-map", "0:a:0?",
           "-sn", "-dn", "-map_metadata", "-1"]
    cmd += ["-c:v", "copy"] if copy_v else [
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-profile:v", "high", "-level:v", "4.1",
        "-pix_fmt", "yuv420p", "-vf", "scale='min(1920,iw)':-2"]
    cmd += ["-c:a", "copy"] if copy_a else ["-c:a", "aac", "-b:a", "128k", "-ac", "2"]
    cmd += ["-movflags", "+faststart", str(dst)]
    t0 = time.time()
    subprocess.run(cmd, check=True)
    _, _, dur2 = probe(dst)
    if dur > 0 and abs(dur2 - dur) > max(3, 0.02 * dur):
        raise RuntimeError(f"durée différente après conversion ({dur:.0f}s → {dur2:.0f}s)")
    mode = "ré-emballage" if copy_v else f"ré-encodage {v['codec_name']}→h264"
    return mode, dur2 or dur, time.time() - t0


def main():
    items = todo()
    total = sum(r["size_bytes"] or 0 for r in items)
    print(f"{len(items)} vidéo(s) à convertir (part {SHARD + 1}/{SHARDS}) · {total / 1e9:.1f} Go")
    for r in items:
        print(f"  - {r['table']}/{r['id']} : {r['video_key']}")
    if DRY or not items:
        return 0
    done = failed = 0
    saved = 0
    for r in items:
        if time.time() > DEADLINE:
            print("⏱ budget de temps atteint : relance le workflow pour continuer.")
            break
        old = r["video_key"]
        new = old.rsplit(".", 1)[0] + ".mp4"
        src, dst = TMP / "in.bin", TMP / "out.mp4"
        try:
            c, bucket = storage(r["storage_id"])
            try:  # nom déjà pris : on ajoute un suffixe
                c.head_object(Bucket=bucket, Key=new)
                new = old.rsplit(".", 1)[0] + f"-{int(time.time())}.mp4"
            except c.exceptions.ClientError:
                pass
            print(f"\n▶ {old}")
            c.download_file(bucket, old, str(src))
            mode, dur, secs = convert(src, dst)
            size = dst.stat().st_size
            c.upload_file(str(dst), bucket, new, ExtraArgs={"ContentType": "video/mp4"})
            if c.head_object(Bucket=bucket, Key=new)["ContentLength"] != size:
                c.delete_object(Bucket=bucket, Key=new)
                raise RuntimeError("taille envoyée incorrecte")
            patch = {"video_key": new, "size_bytes": size}
            if dur > 0:
                patch["duration_s"] = int(round(dur))
            q = f"{r['table']}?id=eq.{r['id']}&video_key=eq.{urllib.parse.quote(old, safe='')}"
            upd = rest("PATCH", q, patch, prefer="return=representation")
            if not upd:  # la ligne a changé (vidéo remplacée entre-temps) : on annule
                c.delete_object(Bucket=bucket, Key=new)
                raise RuntimeError("ligne modifiée entre-temps, ignorée")
            c.delete_object(Bucket=bucket, Key=old)
            saved += (r["size_bytes"] or src.stat().st_size) - size
            done += 1
            print(f"  ✓ {mode} en {secs / 60:.1f} min · {src.stat().st_size / 1e6:.0f} Mo → {size / 1e6:.0f} Mo · {new}")
        except Exception as e:
            failed += 1
            print(f"  ✗ échec, original conservé : {e}")
        finally:
            for p in (src, dst):
                p.unlink(missing_ok=True)
    try:
        rest("POST", "rpc/recompute_storage_usage", {})
    except Exception:
        pass
    print(f"\nTerminé : {done} converti(s), {failed} échec(s), {saved / 1e9:.2f} Go économisés.")
    return 1 if failed and not done else 0


if __name__ == "__main__":
    sys.exit(main())
