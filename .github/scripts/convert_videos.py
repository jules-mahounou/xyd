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
OK_FORMATS = ("mov", "mp4", "matroska", "webm")      # conteneurs lus par l'app (MP4/MOV, MKV/WebM)
OK_VIDEO = ("h264", "hevc", "vp9", "av1")             # vidéo décodée par (presque) tous les téléphones
OK_AUDIO = ("aac", "mp3", "opus", "vorbis", "flac")    # AC3, DTS… : souvent pas de décodeur → vidéo muette
TMP = Path(os.environ.get("RUNNER_TEMP", "/tmp")) / "xyd-convert"
TMP.mkdir(parents=True, exist_ok=True)


def rest(method, path, body=None, prefer=None):
    req = urllib.request.Request(f"{URL}/rest/v1/{path}", method=method,
                                 data=None if body is None else json.dumps(body).encode())
    req.add_header("apikey", KEY)
    if KEY.startswith("eyJ"):  # ancienne clé JWT ; les clés sb_secret_… passent par apikey seul
        req.add_header("Authorization", f"Bearer {KEY}")
    req.add_header("Content-Type", "application/json")
    if prefer:
        req.add_header("Prefer", prefer)
    with urllib.request.urlopen(req, timeout=60) as r:
        raw = r.read()
    return json.loads(raw) if raw else None


def todo():
    """Toutes les vidéos de cette part (le vrai format est lu dans le fichier, pas dans l'extension)."""
    rows = []
    for table, extra in (("titles", "&kind=eq.film"), ("episodes", "")):
        for from_ in range(0, 100000, 1000):
            q = f"{table}?select=id,video_key,storage_id,size_bytes,duration_s&video_key=not.is.null{extra}&order=id&offset={from_}&limit=1000"
            page = rest("GET", q)
            rows += [dict(r, table=table) for r in page]
            if len(page) < 1000:
                break
    return [r for r in rows if zlib.crc32(r["id"].encode()) % SHARDS == SHARD]


_clients = {}


def region_of(row):
    """Région S3 valide : « s3.eu-central-003 » (saisie B2) → « eu-central-003 », sinon déduite de l'endpoint."""
    r = (row.get("region") or "").strip().removeprefix("s3.")
    if not r or r == "auto":
        host = urllib.parse.urlparse(row["endpoint"]).hostname or ""
        if host.startswith("s3.") and host.count(".") >= 3:
            r = host.split(".")[1]
    return r or "auto"


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
                 signature_version="s3v4",
                 # boto3 récent ajoute des sommes de contrôle CRC que R2 / B2 n'acceptent pas toujours
                 request_checksum_calculation="when_required", response_checksum_validation="when_required")
    if not row or row.get("is_env"):
        c = boto3.client("s3", endpoint_url=f"https://{os.environ['R2_ACCOUNT_ID']}.r2.cloudflarestorage.com",
                         aws_access_key_id=os.environ["R2_ACCESS_KEY_ID"],
                         aws_secret_access_key=os.environ["R2_SECRET_ACCESS_KEY"], region_name="auto", config=cfg)
        res = (c, os.environ["R2_BUCKET"])
    else:
        c = boto3.client("s3", endpoint_url=row["endpoint"].rstrip("/"), aws_access_key_id=row["access_key"],
                         aws_secret_access_key=row["secret_key"], region_name=region_of(row), config=cfg)
        res = (c, row["bucket"])
    _clients[storage_id] = res
    return res


_paid = None


def paid_storage(storage_id):
    """Stockage à sortie payante (Backblaze…) : on n'y lit rien tant que ONLY_FREE=1. None = R2 d'origine (gratuit)."""
    global _paid
    if _paid is None:
        rows = rest("GET", "storage_backends?select=id,provider,billable,is_env")
        # Payant = Backblaze, ou marqué « payant » dans l'admin. R2 et S3 non marqués : sortie gratuite.
        _paid = {x["id"] for x in rows if not x["is_env"] and (x["billable"] is True or (x["billable"] is None and x["provider"] == "b2"))}
    return storage_id in _paid


def sniff(head):
    """Vrai conteneur d'après les premiers octets du fichier."""
    if head[4:8] in (b"ftyp", b"moov", b"mdat", b"free", b"wide", b"skip"):
        return "mp4"
    if head[:4] == b"\x1a\x45\xdf\xa3":
        return "mkv"
    if head[:4] == b"RIFF" and head[8:12] == b"AVI ":
        return "avi"
    if head[:3] == b"FLV":
        return "flv"
    if head[:4] == b"\x30\x26\xb2\x75":
        return "wmv"
    if head[:4] == b"\x00\x00\x01\xba":
        return "mpg"
    if head[:1] == b"\x47" and len(head) > 188 and head[188:189] == b"\x47":
        return "ts"
    return "inconnu"


def inspect(r):
    """Lit seulement les 512 premiers octets du fichier (coût négligeable) → raison de convertir, ou None."""
    c, bucket = storage(r["storage_id"])
    head = c.get_object(Bucket=bucket, Key=r["video_key"], Range="bytes=0-511")["Body"].read()
    kind = sniff(head)
    if kind in ("mp4", "mkv"):
        return "ok", kind
    if kind == "inconnu":
        return "inconnu (non touché)", kind
    return f"convertir: {kind}", kind


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
    copy_v = (v["codec_name"] == "h264" and v.get("pix_fmt") in ("yuv420p", "yuvj420p")) or v["codec_name"] == "hevc"
    copy_a = a is not None and a["codec_name"] in ("aac", "mp3")
    cmd = ["ffmpeg", "-y", "-v", "error", "-fflags", "+genpts", "-i", str(src), "-map", "0:v:0", "-map", "0:a:0?",
           "-sn", "-dn", "-map_metadata", "-1"]
    cmd += (["-c:v", "copy"] + (["-tag:v", "hvc1"] if v["codec_name"] == "hevc" else [])) if copy_v else [
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
    allv = todo()
    only_free = os.environ.get("ONLY_FREE", "1") == "1"
    print(f"Analyse de {len(allv)} vidéo(s) (part {SHARD + 1}/{SHARDS})" + (" · stockages payants ignorés" if only_free else ""))
    items = []
    skipped = 0
    for r in allv:
        if only_free and paid_storage(r["storage_id"]):
            skipped += 1
            continue
        try:
            verdict, kind = inspect(r)
        except Exception as e:
            verdict, kind = "erreur de lecture", f"{type(e).__name__}: {e}"[:300]
        print(f"  [{verdict}] {r['video_key']}" + (f" — {kind}" if verdict.startswith("erreur") else ""))
        if verdict.startswith("convertir"):
            items.append(r)
    if skipped:
        print(f"  ({skipped} vidéo(s) sur un stockage payant, non analysée(s))")
    total = sum(r["size_bytes"] or 0 for r in items)
    print(f"→ {len(items)} vidéo(s) à convertir · {total / 1e9:.1f} Go")
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
