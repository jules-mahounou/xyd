#!/usr/bin/env python3
"""
xyd — outil terminal de l'admin xyd (Termux, Linux, macOS, Windows).

  xyd login                         connexion admin (une fois ; le mot de passe n'est jamais enregistré)
  xyd series [recherche]            séries et films du catalogue
  xyd storages                      remplissage des stockages
  xyd convert  <fichiers|dossier>   convertit en MP4 lisible par l'app (H.264 + AAC)
  xyd upload   <fichiers|dossier> --serie "Nom" [--saison N]   envoie et crée les épisodes
  xyd upload   <fichier> --film "Nom"                         remplace la vidéo d'un film
  xyd watch    <dossier> --serie "Nom"   surveille un dossier : convertit et envoie ce qu'on y dépose
  xyd pending [--annuler]           envois interrompus (repris automatiquement au prochain upload)
  xyd update                        installe la dernière version de l'outil

Toutes les commandes acceptent --dry-run (-n) : affiche ce qui serait fait, sans rien modifier.
"""
import argparse, getpass, hashlib, json, os, re, shutil, subprocess, sys, time, unicodedata, random, string
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("Il manque le module requests :  pip install requests")

VERSION = "1.0.0"
SITE = "https://xydhub.tech/admin/"
SELF_URL = "https://raw.githubusercontent.com/jules-mahounou/xyd/claude/intelligent-hawking-c9npwk/tools/xyd/xyd.py"
PART = 16 * 1024 * 1024  # taille d'un morceau d'envoi (B2 : 5 Mo minimum)
VIDEO_EXT = {".mp4", ".m4v", ".mkv", ".mov", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts", ".webm", ".3gp", ".divx", ".xvid"}
APP_FORMATS = {"mov", "mp4", "matroska", "webm"}  # conteneurs lus par l'app
APP_VIDEO = {"h264", "hevc", "vp9", "av1"}
APP_AUDIO = {"aac", "mp3", "opus", "vorbis", "flac"}

HOME = Path(os.environ.get("XYD_HOME", Path.home() / ".config" / "xyd"))
CONF = HOME / "config.json"
STATE = HOME / "uploads.json"   # envois en cours (reprise)
DONE = HOME / "done.json"       # fichiers déjà envoyés par « watch »

# ---------------------------------------------------------------- affichage
TTY = sys.stdout.isatty()
def c(code, s): return f"\033[{code}m{s}\033[0m" if TTY else s
ok = lambda s: print(c("32", "✓ ") + s)
warn = lambda s: print(c("33", "! ") + s)
err = lambda s: print(c("31", "✗ ") + s)
info = lambda s: print(c("36", "• ") + s)
dry = lambda s: print(c("35", "[dry-run] ") + s)

def human(n):
    n = float(n or 0)
    for u in ("o", "Ko", "Mo", "Go", "To"):
        if n < 1024 or u == "To":
            return f"{n:.0f} {u}" if u in ("o", "Ko") else f"{n:.1f} {u}".replace(".", ",")
        n /= 1024

def bar(done, total, width=28):
    p = 0 if not total else min(1, done / total)
    return f"[{'█' * int(p * width)}{'·' * (width - int(p * width))}] {p * 100:5.1f} %"

def load(path, default):
    try:
        return json.loads(path.read_text())
    except Exception:
        return default

def save(path, data):
    HOME.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=1, ensure_ascii=False))
    os.chmod(tmp, 0o600)
    tmp.replace(path)

def slug(s):
    s = unicodedata.normalize("NFD", s or "x").encode("ascii", "ignore").decode().lower()
    return (re.sub(r"[^a-z0-9]+", "-", s).strip("-")[:40]) or "x"

rand = lambda: "".join(random.choices(string.ascii_lowercase + string.digits, k=6))
pad = lambda n: f"{int(n):02d}"

# ---------------------------------------------------------------- API
class Api:
    def __init__(self):
        self.conf = load(CONF, {})
        if not self.conf.get("refresh_token"):
            sys.exit("Pas connecté : lance d'abord  xyd login")
        self.s = requests.Session()
        self.token = None

    @property
    def url(self): return self.conf["url"]

    def auth(self, force=False):
        if self.token and not force and time.time() < self.conf.get("expires_at", 0) - 60:
            return self.token
        r = requests.post(f"{self.url}/auth/v1/token?grant_type=refresh_token", json={"refresh_token": self.conf["refresh_token"]},
                          headers={"apikey": self.conf["anon"]}, timeout=30)
        if r.status_code != 200:
            sys.exit("Session expirée : relance  xyd login")
        j = r.json()
        self.token = j["access_token"]
        self.conf.update(refresh_token=j["refresh_token"], expires_at=time.time() + j.get("expires_in", 3600))
        save(CONF, self.conf)
        return self.token

    def h(self, extra=None):
        h = {"apikey": self.conf["anon"], "Authorization": f"Bearer {self.auth()}", "Content-Type": "application/json"}
        h.update(extra or {})
        return h

    def rest(self, method, path, body=None, prefer=None):
        for attempt in range(4):
            try:
                r = self.s.request(method, f"{self.url}/rest/v1/{path}", json=body,
                                   headers=self.h({"Prefer": prefer} if prefer else None), timeout=60)
            except requests.RequestException as e:
                if attempt == 3: raise RuntimeError(f"réseau : {e}")
                time.sleep(2 + attempt * 3); continue
            if r.status_code == 401 and attempt == 0:
                self.auth(force=True); continue
            if r.status_code >= 400:
                raise RuntimeError(f"base : {r.status_code} {r.text[:200]}")
            return r.json() if r.text else None

    def r2(self, body):
        for attempt in range(5):
            try:
                r = self.s.post(f"{self.url}/functions/v1/admin-r2", json=body, headers=self.h(), timeout=120)
            except requests.RequestException as e:
                if attempt == 4: raise RuntimeError(f"réseau : {e}")
                time.sleep(2 + attempt * 4); continue
            if r.status_code == 401 and attempt == 0:
                self.auth(force=True); continue
            j = r.json() if r.text else {}
            if r.status_code >= 400:
                e = RuntimeError(j.get("error") or f"HTTP {r.status_code}")
                e.gone = j.get("gone", False)
                if r.status_code >= 500 and attempt < 4:
                    time.sleep(3 + attempt * 4); continue
                raise e
            return j

# ---------------------------------------------------------------- vidéo
def need_ffmpeg():
    for b in ("ffmpeg", "ffprobe"):
        if not shutil.which(b):
            sys.exit(f"{b} introuvable. Termux :  pkg install ffmpeg")

def probe(path):
    p = subprocess.run(["ffprobe", "-v", "error", "-print_format", "json", "-show_streams", "-show_format", str(path)],
                       capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError("fichier illisible par ffprobe")
    j = json.loads(p.stdout)
    v = next((s for s in j["streams"] if s["codec_type"] == "video"), None)
    a = next((s for s in j["streams"] if s["codec_type"] == "audio"), None)
    return {
        "format": j["format"].get("format_name", ""),
        "duration": float(j["format"].get("duration") or 0),
        "bitrate": int(j["format"].get("bit_rate") or 0),
        "vcodec": v and v["codec_name"], "pix": v and v.get("pix_fmt"),
        "w": v and v.get("width"), "h": v and v.get("height"),
        "acodec": a and a["codec_name"],
    }

def app_ready(p):
    """Lisible tel quel par l'app ?"""
    return (any(f in APP_FORMATS for f in p["format"].split(",")) and p["vcodec"] in APP_VIDEO
            and (p["acodec"] is None or p["acodec"] in APP_AUDIO))

_hw = None
def hw_encoder():
    """Encodeur H.264 matériel du téléphone (bien plus rapide), si ffmpeg le propose."""
    global _hw
    if _hw is None:
        out = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
        _hw = "h264_mediacodec" if "h264_mediacodec" in out else ""
    return _hw

def plan_convert(p, mode):
    """Ce qu'il faut faire pour rendre le fichier lisible : (action, arguments vidéo, arguments son)."""
    copy_v = (p["vcodec"] == "h264" and p["pix"] in ("yuv420p", "yuvj420p")) or p["vcodec"] == "hevc"
    copy_a = p["acodec"] in ("aac", "mp3") or p["acodec"] is None
    a_args = ["-c:a", "copy"] if copy_a else ["-c:a", "aac", "-b:a", "128k", "-ac", "2"]
    if copy_v:
        v_args = ["-c:v", "copy"] + (["-tag:v", "hvc1"] if p["vcodec"] == "hevc" else [])
        return "ré-emballage (rapide, sans perte)", v_args, a_args, None
    h = p["h"] or 480
    target = 1200 if h <= 480 else 2500 if h <= 720 else 4500  # kbit/s
    if p["bitrate"]:
        target = max(600, min(target, int(p["bitrate"] / 1000 * 0.9)))
    scale = ["-vf", "scale='min(1920,iw)':-2"]
    sw = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "23", "-profile:v", "high", "-pix_fmt", "yuv420p"] + scale
    if mode != "sw" and hw_encoder():
        hw = ["-c:v", "h264_mediacodec", "-b:v", f"{target}k", "-pix_fmt", "yuv420p"] + scale
        return f"encodage matériel ({p['vcodec']} → h264, {target} kbit/s)", hw, a_args, sw
    return f"encodage logiciel ({p['vcodec']} → h264)", sw, a_args, None

def convert_file(src, dst, mode, dry_run):
    p = probe(src)
    if app_ready(p) and src.suffix.lower() in (".mp4", ".m4v", ".mov", ".mkv", ".webm"):
        return "déjà lisible", src
    label, v_args, a_args, fallback = plan_convert(p, mode)
    desc = f"{src.name} · {p['format']} {p['vcodec']}/{p['acodec']} · {human(src.stat().st_size)} → {label}"
    if dry_run:
        dry(f"convertirait {desc} → {dst}")
        return label, None
    info(desc)
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_name(dst.stem + ".part.mp4")
    for attempt, va in enumerate([v_args] + ([fallback] if fallback else [])):
        cmd = ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-stats", "-fflags", "+genpts", "-i", str(src),
               "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn", "-map_metadata", "-1", *va, *a_args,
               "-movflags", "+faststart", str(tmp)]
        t0 = time.time()
        if subprocess.run(cmd).returncode == 0:
            break
        tmp.unlink(missing_ok=True)
        if attempt == 0 and fallback:
            warn("encodeur matériel en échec, passage à l'encodage logiciel (plus lent)")
            continue
        raise RuntimeError("ffmpeg a échoué")
    q = probe(tmp)
    if p["duration"] and abs(q["duration"] - p["duration"]) > max(3, 0.02 * p["duration"]):
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"durée différente après conversion ({p['duration']:.0f} s → {q['duration']:.0f} s)")
    tmp.replace(dst)
    secs = time.time() - t0
    ok(f"{dst.name} · {human(src.stat().st_size)} → {human(dst.stat().st_size)} en " + (f"{secs / 60:.1f} min" if secs >= 60 else f"{secs:.0f} s"))
    return label, dst

# ---------------------------------------------------------------- fichiers / épisodes
def list_videos(paths):
    out = []
    for p in map(Path, paths):
        if p.is_dir():
            out += sorted((f for f in p.iterdir() if f.is_file() and f.suffix.lower() in VIDEO_EXT
                           and not f.name.endswith(".part.mp4")), key=natural)
        elif p.is_file():
            out.append(p)
        else:
            warn(f"introuvable : {p}")
    return out

def natural(p):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", p.name)]

def parse_se(name, default_season):
    """Saison / épisode d'après le nom du fichier : S01E06, 1x06, E06, Ep 6, Épisode 6, … 06."""
    n = Path(name).stem
    for rx in (r"[Ss](\d{1,2})\s*[._ -]?\s*[Ee][Pp]?(\d{1,3})", r"\b(\d{1,2})x(\d{1,3})\b"):
        m = re.search(rx, n)
        if m:
            return int(m.group(1)), int(m.group(2))
    m = re.search(r"(?:\b[Ee][Pp]?|[ÉéEe]pisode|[Ee]pisode)\s*[._ -]?\s*(\d{1,3})\b", n)
    if m:
        return default_season, int(m.group(1))
    nums = [int(x) for x in re.findall(r"(?<![\dpx])(\d{1,3})(?!\d|p\b)", n)]
    return (default_season, nums[-1]) if nums else (default_season, None)

def clean_name(name):
    """Titre de l'épisode tiré du nom du fichier (vide si le nom ne contient que la série et le numéro)."""
    n = re.sub(r"[\[(].*?[\])]", " ", Path(name).stem)
    n = re.sub(r"[._]+", " ", n)
    m = re.search(r"[Ss]\d{1,2}\s*[ -]?\s*[Ee][Pp]?\d{1,3}|\b\d{1,2}x\d{1,3}\b|(?:\b[Ee][Pp]?|[ÉéEe]pisode)\s*-?\s*\d{1,3}\b|\b\d{1,3}\b", n)
    if m:
        n = n[m.end():]
    n = re.sub(r"\b(1080p|720p|480p|x264|x265|h264|hevc|xvid|divx|web[- ]?dl|bluray|multi|vostfr|french)\b.*", "", n, flags=re.I)
    return re.sub(r"\s+", " ", n).strip(" -")

def find_title(api, name, kind):
    rows = api.rest("GET", f"titles?select=id,name,kind&kind=eq.{kind}&name=ilike.*{requests.utils.quote(name)}*&order=name")
    exact = [r for r in rows if r["name"].lower() == name.lower()]
    if len(exact) == 1 or len(rows) == 1:
        return (exact or rows)[0]
    if not rows:
        sys.exit(f"Aucun{'e série' if kind == 'serie' else ' film'} « {name} ». Crée-le d'abord dans l'admin, ou vérifie avec  xyd series")
    sys.exit(f"« {name} » est ambigu : " + ", ".join(r["name"] for r in rows) + ". Précise le nom.")

def fingerprint(f):
    st = f.stat()
    return hashlib.sha1(f"{f.resolve()}|{st.st_size}|{int(st.st_mtime)}".encode()).hexdigest()[:16]

# ---------------------------------------------------------------- envoi reprenable
def send(api, f, key, storage_id, label):
    """Envoie f sous la clé key, en morceaux de 16 Mo, avec reprise. Retourne l'id du stockage."""
    size = f.stat().st_size
    fp = fingerprint(f)
    pending = load(STATE, {})
    job = pending.get(fp)
    if job and job["key"] != key:
        key = job["key"]  # on reprend l'envoi commencé, sous sa clé d'origine
    if job:
        try:
            done = {p["n"]: p["etag"] for p in api.r2({"action": "mp-list", "key": key, "storage_id": job["storage_id"],
                                                         "upload_id": job["upload_id"]})["parts"]}
            info(f"reprise de l'envoi : {len(done)} morceau(x) déjà reçu(s)")
        except RuntimeError as e:
            if getattr(e, "gone", False):
                warn("envoi précédent expiré, on recommence")
                api.r2({"action": "release", "storage_id": job["storage_id"], "size": size}) if job.get("storage_id") else None
                pending.pop(fp, None); save(STATE, pending); job = None
            else:
                raise
    if not job:
        start = api.r2({"action": "mp-start", "key": key, "size": size, "storage_id": storage_id})
        job = {"key": key, "storage_id": start["storage_id"], "upload_id": start["upload_id"], "size": size,
               "file": str(f.resolve()), "label": label, "started": time.time()}
        pending[fp] = job; save(STATE, pending)
        info(f"destination : {start.get('storage_name') or start['storage_id']}")
        done = {}
    total = (size + PART - 1) // PART
    sent = sum(min(PART, size - (n - 1) * PART) for n in done)
    todo = [n for n in range(1, total + 1) if n not in done]
    t0, sent0 = time.time(), sent
    with open(f, "rb") as fh:
        for i in range(0, len(todo), 50):
            batch = todo[i:i + 50]
            urls = api.r2({"action": "mp-sign", "key": key, "storage_id": job["storage_id"], "upload_id": job["upload_id"], "parts": batch})["urls"]
            for n in batch:
                fh.seek((n - 1) * PART)
                chunk = fh.read(PART)
                for attempt in range(6):
                    try:
                        r = requests.put(urls[str(n)], data=chunk, timeout=300)
                        if r.status_code >= 400:
                            raise RuntimeError(f"stockage : HTTP {r.status_code} {r.text[:160]}")
                        done[n] = r.headers.get("ETag", "")
                        break
                    except (requests.RequestException, RuntimeError) as e:
                        if attempt == 5:
                            raise RuntimeError(f"morceau {n} : {e} (relance la commande pour reprendre)")
                        wait = 3 * (attempt + 1)
                        print(f"\r  morceau {n} : {e} · nouvel essai dans {wait} s", end="", flush=True)
                        time.sleep(wait)
                sent += len(chunk)
                speed = (sent - sent0) / max(1, time.time() - t0)
                print(f"\r  {bar(sent, size)} {human(sent)}/{human(size)} · {human(speed)}/s   ", end="", flush=True)
    print()
    api.r2({"action": "mp-complete", "key": key, "storage_id": job["storage_id"], "upload_id": job["upload_id"],
            "parts": [{"n": n, "etag": e} for n, e in sorted(done.items())]})
    pending = load(STATE, {}); pending.pop(fp, None); save(STATE, pending)
    return key, job["storage_id"]

# ---------------------------------------------------------------- commandes
def cmd_login(a):
    info("lecture des réglages publics de l'admin…")
    html = requests.get(SITE, timeout=30).text
    url = re.search(r'SUPABASE_URL\s*=\s*"([^"]+)"', html)
    anon = re.search(r'SUPABASE_ANON_KEY\s*=\s*"([^"]+)"', html)
    if not url or not anon or url.group(1).startswith("__"):
        sys.exit("Impossible de lire l'adresse Supabase sur xydhub.tech/admin")
    email = a.email or input("E-mail admin : ").strip()
    if a.dry_run:
        dry(f"se connecterait à {url.group(1)} en tant que {email} (rien n'est enregistré)")
        return
    pw = getpass.getpass("Mot de passe (jamais enregistré) : ")
    r = requests.post(f"{url.group(1)}/auth/v1/token?grant_type=password", json={"email": email, "password": pw},
                      headers={"apikey": anon.group(1)}, timeout=30)
    if r.status_code != 200:
        sys.exit("Connexion refusée : " + (r.json().get("error_description") or r.text[:120]))
    j = r.json()
    save(CONF, {"url": url.group(1), "anon": anon.group(1), "email": email, "refresh_token": j["refresh_token"],
                "expires_at": time.time() + j.get("expires_in", 3600)})
    api = Api()
    if api.rest("POST", "rpc/is_admin", {}) is not True:
        CONF.unlink(missing_ok=True)
        sys.exit("Ce compte n'est pas administrateur.")
    ok(f"connecté en admin ({email}). Session gardée dans {CONF} (lisible par toi seul).")

def cmd_logout(a):
    if a.dry_run:
        return dry(f"supprimerait la session {CONF}")
    CONF.unlink(missing_ok=True); ok("déconnecté")

def cmd_series(a):
    api = Api()
    q = f"&name=ilike.*{requests.utils.quote(a.query)}*" if a.query else ""
    rows = api.rest("GET", f"titles?select=id,name,kind,published,seasons(number,episodes(count))&order=name{q}")
    for t in rows:
        if t["kind"] == "film":
            print(f"  🎬 {t['name']}{'' if t['published'] else c('2', ' (masqué)')}")
        else:
            ss = sorted(t.get("seasons") or [], key=lambda s: s["number"])
            det = ", ".join(f"S{pad(s['number'])}:{s['episodes'][0]['count'] if s['episodes'] else 0}" for s in ss) or "aucune saison"
            print(f"  📺 {t['name']} — {det}{'' if t['published'] else c('2', ' (masquée)')}")
    print(f"{len(rows)} titre(s)")

def cmd_storages(a):
    api = Api()
    rows = api.rest("GET", "storage_backends?select=name,provider,priority,active,quota_bytes,used_bytes&order=priority,created_at")
    for x in rows:
        free = x["quota_bytes"] - x["used_bytes"]
        tag = c("2", "inactif") if not x["active"] else c("33", "presque plein") if free < 1e9 else c("32", "disponible")
        print(f"  {x['priority']:>2}. {x['name']:<20} {x['provider']:<4} {bar(x['used_bytes'], x['quota_bytes'], 16)} "
              f"{human(x['used_bytes'])}/{human(x['quota_bytes'])} · {tag}")
    tot, used = sum(x["quota_bytes"] for x in rows if x["active"]), sum(x["used_bytes"] for x in rows if x["active"])
    print(f"Pool : {human(used)} / {human(tot)} · libre {human(tot - used)}")

def cmd_convert(a):
    need_ffmpeg()
    files = list_videos(a.paths)
    if not files:
        sys.exit("Aucune vidéo trouvée.")
    n = 0
    for f in files:
        dst = (Path(a.out) if a.out else f.parent / "xyd-mp4") / (f.stem + ".mp4")
        if dst.exists() and not a.dry_run:
            info(f"{f.name} : déjà converti ({dst})"); continue
        try:
            label, out = convert_file(f, dst, a.encoder, a.dry_run)
            if label == "déjà lisible":
                ok(f"{f.name} : déjà lisible par l'app, rien à faire")
            n += 1
        except Exception as e:
            err(f"{f.name} : {e}")
    if a.dry_run:
        dry(f"{n} fichier(s) analysé(s), rien n'a été modifié")

def upload_series(api, a, files, dry_run):
    t = find_title(api, a.serie, "serie")
    seasons = {s["number"]: s for s in api.rest("GET", f"seasons?select=id,number,episodes(id,number,name,video_key,storage_id,size_bytes)&title_id=eq.{t['id']}")}
    plan, taken = [], set()
    for f in files:
        sn, en = parse_se(f.name, a.saison or 1)
        if a.saison and sn != a.saison and not a.garder_saison:
            sn = a.saison
        if en is None:
            warn(f"{f.name} : numéro d'épisode introuvable dans le nom, ignoré (renomme en « S01E06 … »)"); continue
        if (sn, en) in taken:
            warn(f"{f.name} : S{pad(sn)}E{pad(en)} en double dans ta sélection, ignoré"); continue
        taken.add((sn, en))
        ex = next((e for e in (seasons.get(sn) or {}).get("episodes", []) if e["number"] == en), None)
        if ex and not a.remplacer:
            info(f"{f.name} → S{pad(sn)}E{pad(en)} existe déjà, ignoré (ajoute --remplacer pour le remplacer)"); continue
        plan.append((f, sn, en, ex))
    print(f"\n{c('1', t['name'])} : {len(plan)} fichier(s) à envoyer")
    for f, sn, en, ex in plan:
        what = c("33", "remplace") if ex else c("32", "nouveau")
        season = "" if sn in seasons else c("36", " (saison créée)")
        print(f"  S{pad(sn)}E{pad(en)} {what}{season} ← {f.name} ({human(f.stat().st_size)})")
    if dry_run:
        dry("aucun envoi, aucune modification")
        return 0
    if plan and not a.oui and sys.stdin.isatty() and input("\nContinuer ? [o/N] ").strip().lower() not in ("o", "oui", "y"):
        return 0
    sent = 0
    for f, sn, en, ex in plan:
        label = f"{t['name']} S{pad(sn)}E{pad(en)}"
        print(c("1", f"\n▶ {label}") + f" · {f.name}")
        try:
            src = prepare(f, a)
            if src is None:
                continue
            p = probe(src)
            if sn not in seasons:
                s = api.rest("POST", "seasons?select=id,number", {"title_id": t["id"], "number": sn, "name": f"Saison {sn}"},
                             prefer="return=representation")[0]
                s["episodes"] = []; seasons[sn] = s
            key = f"series/{slug(t['name'])}-{t['id'][:8]}/s{pad(sn)}/e{pad(en)}-{rand()}.{ext_for(src, p)}"
            key, sid = send(api, src, key, a.stockage_id, label)
            row = {"video_key": key, "storage_id": sid, "size_bytes": src.stat().st_size, "duration_s": int(round(p["duration"]))}
            try:
                if ex:
                    api.rest("PATCH", f"episodes?id=eq.{ex['id']}", row)
                    api.r2({"action": "delete", "items": [{"key": ex["video_key"], "storage_id": ex["storage_id"], "size": ex["size_bytes"]}]})
                    ok(f"{label} remplacé (ancienne vidéo effacée)")
                else:
                    api.rest("POST", "episodes", dict(row, season_id=seasons[sn]["id"], number=en,
                                                      name=clean_name(f.name) or f"Épisode {en}"))
                    ok(f"{label} en ligne")
            except Exception:
                api.r2({"action": "delete", "items": [{"key": key, "storage_id": sid, "size": src.stat().st_size}]})
                raise
            sent += 1
        except Exception as e:
            err(f"{label} : {e}")
    return sent

def upload_film(api, a, files, dry_run):
    if len(files) != 1:
        sys.exit("--film : un seul fichier à la fois")
    f = files[0]
    t = find_title(api, a.film, "film")
    cur = api.rest("GET", f"titles?select=video_key,storage_id,size_bytes&id=eq.{t['id']}")[0]
    print(f"\n{c('1', t['name'])} : remplace la vidéo du film ← {f.name} ({human(f.stat().st_size)})")
    if dry_run:
        dry("aucun envoi, aucune modification"); return 0
    if not a.oui and sys.stdin.isatty() and input("Continuer ? [o/N] ").strip().lower() not in ("o", "oui", "y"):
        return 0
    src = prepare(f, a)
    if src is None:
        return 0
    p = probe(src)
    key = f"films/{slug(t['name'])}-{t['id'][:8]}-{rand()}.{ext_for(src, p)}"
    key, sid = send(api, src, key, a.stockage_id, t["name"])
    try:
        api.rest("PATCH", f"titles?id=eq.{t['id']}", {"video_key": key, "storage_id": sid, "size_bytes": src.stat().st_size,
                                                    "duration_s": int(round(p["duration"]))})
    except Exception:
        api.r2({"action": "delete", "items": [{"key": key, "storage_id": sid, "size": src.stat().st_size}]})
        raise
    if cur.get("video_key"):
        api.r2({"action": "delete", "items": [{"key": cur["video_key"], "storage_id": cur["storage_id"], "size": cur["size_bytes"]}]})
    ok(f"{t['name']} : nouvelle vidéo en ligne")
    return 1

def ext_for(src, p):
    return "mkv" if "matroska" in p["format"] and src.suffix.lower() == ".mkv" else "mp4"

def prepare(f, a):
    """Vérifie que l'app saura lire le fichier ; le convertit si --convert, sinon refuse."""
    p = probe(f)
    if app_ready(p):
        return f
    if not a.convert:
        err(f"{f.name} : format {p['format']} {p['vcodec']}/{p['acodec']} non lisible par l'app. Ajoute --convert.")
        return None
    dst = f.parent / "xyd-mp4" / (f.stem + ".mp4")
    if dst.exists() and app_ready(probe(dst)):
        info(f"version convertie trouvée : {dst.name}")
        return dst
    _, out = convert_file(f, dst, a.encoder, False)
    return out

def resolve_storage(api, a):
    a.stockage_id = None
    if a.stockage:
        rows = api.rest("GET", f"storage_backends?select=id,name&name=ilike.{requests.utils.quote(a.stockage)}")
        if not rows:
            sys.exit(f"Stockage « {a.stockage} » introuvable (voir  xyd storages)")
        a.stockage_id = rows[0]["id"]

def cmd_upload(a):
    need_ffmpeg()
    if bool(a.serie) == bool(a.film):
        sys.exit("Indique --serie \"Nom\" ou --film \"Nom\"")
    api = Api()
    resolve_storage(api, a)
    files = list_videos(a.paths)
    if not files:
        sys.exit("Aucune vidéo trouvée.")
    if a.dry_run:
        for f in files:
            try:
                p = probe(f)
                if not app_ready(p):
                    (dry if a.convert else warn)(f"{f.name} : {p['format']} {p['vcodec']}/{p['acodec']} → "
                                                + ("serait converti avant l'envoi" if a.convert else "non lisible par l'app, il faudra --convert"))
            except Exception as e:
                warn(f"{f.name} : {e}")
    (upload_film if a.film else upload_series)(api, a, files, a.dry_run)

def cmd_watch(a):
    need_ffmpeg()
    folder = Path(a.paths[0])
    if not folder.is_dir():
        sys.exit("watch : indique un dossier")
    api = Api()
    resolve_storage(api, a)
    a.oui = True
    seen, done = {}, load(DONE, {})
    info(f"surveillance de {folder} toutes les {a.interval} s (Ctrl+C pour arrêter)"
         + (" · dry-run" if a.dry_run else "") + (" · pense à  termux-wake-lock" if "TERMUX_VERSION" in os.environ else ""))
    while True:
        ready = []
        for f in list_videos([folder]):
            st = f.stat()
            sig = (st.st_size, int(st.st_mtime))
            if fingerprint(f) in done:
                continue
            if seen.get(f) == sig and time.time() - st.st_mtime > 20:  # taille stable : copie terminée
                ready.append(f)
            seen[f] = sig
        if ready:
            a.paths = [str(x) for x in ready]
            if a.dry_run:
                (upload_film if a.film else upload_series)(api, a, ready, True)
                for f in ready:
                    done[fingerprint(f)] = str(f)  # en dry-run : mémorisé pour cette session seulement
            else:
                (upload_film if a.film else upload_series)(api, a, ready, False)
                ok_dir = folder / "envoyes"
                ok_dir.mkdir(exist_ok=True)
                for f in ready:
                    if not any(fp == fingerprint(f) for fp in load(STATE, {})):  # pas d'envoi en suspens
                        done[fingerprint(f)] = str(f)
                        try:
                            f.rename(ok_dir / f.name)
                        except OSError:
                            pass
                save(DONE, done)
        time.sleep(a.interval)

def cmd_pending(a):
    pend = load(STATE, {})
    if not pend:
        return ok("aucun envoi interrompu")
    api = Api() if a.annuler else None
    for fp, j in list(pend.items()):
        age = (time.time() - j.get("started", time.time())) / 3600
        print(f"  {j.get('label') or j['key']} · {human(j['size'])} · commencé il y a {age:.0f} h · {j['file']}")
        if a.annuler:
            if a.dry_run:
                dry("annulerait cet envoi et libérerait l'espace réservé"); continue
            try:
                api.r2({"action": "mp-abort", "key": j["key"], "storage_id": j["storage_id"], "upload_id": j["upload_id"], "size": j["size"]})
            except Exception as e:
                warn(f"annulation : {e}")
            pend.pop(fp, None); save(STATE, pend); ok("annulé, espace libéré")
    if not a.annuler:
        info("relance la même commande upload pour reprendre, ou  xyd pending --annuler")

def cmd_update(a):
    me = Path(os.path.realpath(sys.argv[0]))
    r = requests.get(SELF_URL, timeout=60)
    if r.status_code != 200 or "def main()" not in r.text:
        sys.exit("Téléchargement de la mise à jour impossible")
    v = re.search(r'VERSION = "([^"]+)"', r.text)
    new = v.group(1) if v else "?"
    if a.dry_run:
        return dry(f"remplacerait {me} (v{VERSION}) par la v{new}")
    tmp = me.with_suffix(".new")
    tmp.write_text(r.text)
    os.chmod(tmp, 0o755)
    tmp.replace(me)
    ok(f"xyd v{VERSION} → v{new}")

# ---------------------------------------------------------------- entrée
def main():
    ap = argparse.ArgumentParser(prog="xyd", description="Outil terminal de l'admin xyd")
    ap.add_argument("--version", action="version", version=f"xyd {VERSION}")
    sub = ap.add_subparsers(dest="cmd", required=True)
    def add(name, fn, helptext):
        p = sub.add_parser(name, help=helptext)
        p.add_argument("-n", "--dry-run", action="store_true", help="montre ce qui serait fait, sans rien modifier")
        p.set_defaults(fn=fn)
        return p
    p = add("login", cmd_login, "connexion admin"); p.add_argument("--email")
    add("logout", cmd_logout, "oublie la session")
    p = add("series", cmd_series, "liste séries et films"); p.add_argument("query", nargs="?")
    add("storages", cmd_storages, "remplissage des stockages")
    p = add("convert", cmd_convert, "convertit en MP4 lisible par l'app")
    p.add_argument("paths", nargs="+"); p.add_argument("--out", help="dossier de sortie (défaut : <dossier>/xyd-mp4)")
    p.add_argument("--encoder", choices=["auto", "hw", "sw"], default="auto", help="hw = matériel, sw = logiciel")
    for name, fn, h in (("upload", cmd_upload, "envoie des vidéos"), ("watch", cmd_watch, "surveille un dossier et envoie")):
        p = add(name, fn, h)
        p.add_argument("paths", nargs="+")
        p.add_argument("--serie"); p.add_argument("--film")
        p.add_argument("--saison", type=int, help="saison à utiliser (défaut : celle du nom de fichier, sinon 1)")
        p.add_argument("--garder-saison", action="store_true", help="avec --saison : garder la saison lue dans le nom si présente")
        p.add_argument("--remplacer", action="store_true", help="remplace les épisodes qui existent déjà")
        p.add_argument("--convert", action="store_true", help="convertit d'abord les fichiers que l'app ne lit pas")
        p.add_argument("--encoder", choices=["auto", "hw", "sw"], default="auto")
        p.add_argument("--stockage", help="nom du stockage imposé (défaut : automatique)")
        p.add_argument("-y", "--oui", action="store_true", help="ne pas demander de confirmation")
        if name == "watch":
            p.add_argument("--interval", type=int, default=30)
    p = add("pending", cmd_pending, "envois interrompus"); p.add_argument("--annuler", action="store_true")
    add("update", cmd_update, "met à jour l'outil")
    a = ap.parse_args()
    try:
        a.fn(a)
    except KeyboardInterrupt:
        print("\ninterrompu — relance la même commande pour reprendre là où ça s'est arrêté")
        sys.exit(130)
    except RuntimeError as e:
        err(str(e)); sys.exit(1)

if __name__ == "__main__":
    main()
