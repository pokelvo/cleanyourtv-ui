"""Service de mise à jour de CleanYourTV UI sur le NAS (stdlib seulement).

La TV :
  GET  /api/status?build=<sha>   ce qui est nouveau pour une TV dont l'appli vient du commit <sha> (Config.BUILD_ID)
  POST /api/check                redemande à GitHub (au plus toutes les 2 minutes)
  POST /api/update               fusionne le dépôt d'origine si besoin, puis compile ; la TV suit /api/status
  GET  /api/apk                  l'APK compilée pour la tête de ma-tv (quand state == "ready")
Le PC (jeton de /data/token, en-tête X-Token) :
  GET  /api/bundle?have=<sha>    la branche ma-tv en bundle git (seulement ce qui manque après <sha>)
  POST /api/push                 un bundle de ma-tv : accepté s'il prolonge celle du NAS (sinon 409 : tirer d'abord)

Le dépôt est /data/repo, sur ma-tv ; origin = le fork sur GitHub. Réglages et clé de signature : /data/secrets,
copiés dans launcher-ui avant chaque compilation (ignorés par git).
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PORT = 8765
DATA = "/data"
REPO = DATA + "/repo"
BRANCH = "ma-tv"
FORK = "origin/main"
APK = DATA + "/out/CleanYourTV-UI.apk"
APK_INFO = DATA + "/out/apk.json"
FETCH_EVERY = 6 * 3600
FETCH_AGAIN = 120

lock = threading.RLock()        # tout ce qui touche au dépôt
state = {"state": "idle", "message": "", "files": [], "checked_at": 0, "fork_new": [], "head": ""}


def log(msg):
    print("[%s] %s" % (datetime.now().strftime("%Y-%m-%d %H:%M:%S"), msg), flush=True)


def git(*args, check=True, timeout=600):
    r = subprocess.run(["git", "-C", REPO] + list(args), capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError("git %s : %s" % (" ".join(args), (r.stderr or r.stdout).strip()[:400]))
    return r


def lines(text):
    return [l for l in text.splitlines() if l.strip()]


def refresh_cache():
    """Tête de ma-tv et commits launcher-ui du fork pas encore fusionnés (sous lock)."""
    state["head"] = git("rev-parse", BRANCH).stdout.strip()
    state["fork_new"] = lines(git("log", "--format=%s", "%s..%s" % (BRANCH, FORK), "--", "launcher-ui").stdout)


def fetch(force_age):
    if time.time() - state["checked_at"] < force_age:
        return
    with lock:
        try:
            git("fetch", "-q", "origin", "main", timeout=120)
            state["checked_at"] = int(time.time())
        except Exception as e:  # pas d'Internet : on garde l'ancienne réponse
            log("fetch : %s" % e)
        refresh_cache()


def local_new(build):
    """Les commits de ma-tv que l'appli de la TV n'a pas (ses titres)."""
    head = state["head"]
    if not build or build == head:
        return []
    # Une version que le NAS n'a pas (compilée sur le PC, pas encore envoyée par nas.sh push) est plus récente que
    # la sienne : ne rien proposer, ce serait revenir en arrière.
    if git("cat-file", "-e", build + "^{commit}", check=False).returncode != 0:
        return []
    # Seulement ce qui change l'APK (app/, build.sh) : la doc ou le service du NAS ne demandent rien à la TV.
    return lines(git("log", "--format=%s", "%s..%s" % (build, BRANCH), "--", "launcher-ui/app", "launcher-ui/build.sh").stdout)


def apk_sha():
    try:
        with open(APK_INFO) as f:
            return json.load(f).get("sha", "")
    except (OSError, ValueError):
        return ""


def set_state(name, message="", files=None):
    state["state"], state["message"], state["files"] = name, message, files or []
    log("état : %s %s" % (name, message))


def build_log_path():
    return DATA + "/logs/build-%s.log" % datetime.now().strftime("%Y%m%d-%H%M%S")


def update_job():
    """Fusion du fork (si besoin) puis compilation. Un conflit ou un échec laisse ma-tv comme avant."""
    with lock:
        try:
            set_state("working", "Recherche des nouveautés")
            fetch(0)
            mark = None
            if state["fork_new"]:
                mark = "avant-maj-" + datetime.now().strftime("%Y%m%d-%H%M%S")
                git("tag", mark)
                set_state("working", "Fusion des nouveautés")
                if git("merge", "--no-edit", "-q", FORK, check=False).returncode != 0:
                    files = lines(git("diff", "--name-only", "--diff-filter=U", check=False).stdout)
                    git("merge", "--abort", check=False)
                    git("tag", "-d", mark, check=False)
                    refresh_cache()
                    set_state("conflict", "Le dépôt d'origine a modifié les mêmes passages que vos personnalisations.", files)
                    return
            refresh_cache()
            head = state["head"]
            if apk_sha() == head and os.path.exists(APK):
                set_state("ready", "Déjà compilée")
                return
            set_state("working", "Compilation (1 à 3 minutes)")
            for name in ("config.properties", "cleanyourtv-ui.keystore"):
                src = DATA + "/secrets/" + name
                if not os.path.exists(src):
                    # Sans la clé, build.sh en créerait une autre : la TV refuserait l'APK (signature différente).
                    set_state("error", "Il manque /data/secrets/%s : rien n'a été compilé." % name)
                    return
                shutil.copy(src, REPO + "/launcher-ui/" + name)
            path = build_log_path()
            env = dict(os.environ, JAVA_TOOL_OPTIONS="-Xmx1200m")
            with open(path, "w") as out:
                r = subprocess.run(["bash", "launcher-ui/build.sh"], cwd=REPO, stdout=out, stderr=subprocess.STDOUT,
                                   env=env, timeout=1800)
            built = REPO + "/launcher-ui/CleanYourTV-UI.apk"
            if r.returncode != 0 or not os.path.exists(built):
                if mark:
                    git("reset", "-q", "--hard", mark)
                    refresh_cache()
                set_state("error", "La compilation a échoué (journal : %s). Rien n'a changé." % os.path.basename(path))
                return
            shutil.copy(built, APK + ".tmp")
            os.replace(APK + ".tmp", APK)
            with open(APK_INFO, "w") as f:
                json.dump({"sha": head, "at": int(time.time())}, f)
            set_state("ready", "Prête à installer")
        except Exception as e:
            git("merge", "--abort", check=False)
            set_state("error", str(e)[:300])


job = None


def start_update():
    global job
    if job is not None and job.is_alive():
        return False
    job = threading.Thread(target=update_job, daemon=True)
    job.start()
    return True


def token_ok(handler):
    try:
        with open(DATA + "/token") as f:
            want = f.read().strip()
    except OSError:
        return False
    return bool(want) and handler.headers.get("X-Token", "") == want


class Handler(BaseHTTPRequestHandler):
    server_version = "cytv/1"

    def log_message(self, fmt, *args):
        if not self.path.startswith("/api/status"):
            log("%s %s" % (self.address_string(), fmt % args))

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def status(self, build):
        out = {k: state[k] for k in ("state", "message", "files", "checked_at", "fork_new", "head")}
        out["local_new"] = []
        if lock.acquire(timeout=2):     # pendant une compilation, on répond quand même (sans local_new)
            try:
                out["local_new"] = local_new(build)
            finally:
                lock.release()
        out["apk_ready"] = state["head"] != "" and apk_sha() == state["head"] and os.path.exists(APK)
        if out["state"] == "ready" and not out["apk_ready"]:
            out["state"] = "idle"
        return out

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        if u.path == "/api/status":
            threading.Thread(target=fetch, args=(FETCH_EVERY,), daemon=True).start()
            self.send_json(self.status(q.get("build", [""])[0]))
        elif u.path == "/api/apk":
            if not os.path.exists(APK) or apk_sha() != state["head"]:
                return self.send_json({"error": "pas d'APK prête"}, 404)
            size = os.path.getsize(APK)
            self.send_response(200)
            self.send_header("Content-Type", "application/vnd.android.package-archive")
            self.send_header("Content-Length", str(size))
            self.end_headers()
            with open(APK, "rb") as f:
                shutil.copyfileobj(f, self.wfile)
        elif u.path == "/api/bundle":
            if not token_ok(self):
                return self.send_json({"error": "jeton"}, 403)
            have = q.get("have", [""])[0]
            with lock, tempfile.TemporaryDirectory() as d:
                b = d + "/b.bundle"
                spec = [BRANCH]
                if have and have != state["head"] and git("cat-file", "-e", have + "^{commit}", check=False).returncode == 0:
                    spec.append("^" + have)
                if have == state["head"]:
                    return self.send_json({"up_to_date": True, "head": have})
                git("bundle", "create", "-q", b, *spec)
                data = open(b, "rb").read()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("X-Head", state["head"])
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        else:
            self.send_json({"error": "inconnu"}, 404)

    def do_POST(self):
        u = urlparse(self.path)
        if u.path == "/api/check":
            fetch(FETCH_AGAIN)
            self.send_json(self.status(parse_qs(u.query).get("build", [""])[0]))
        elif u.path == "/api/update":
            started = start_update()
            self.send_json({"started": started}, 202)
        elif u.path == "/api/push":
            if not token_ok(self):
                return self.send_json({"error": "jeton"}, 403)
            n = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(n)
            if job is not None and job.is_alive():
                return self.send_json({"error": "une mise à jour est en cours, réessayez dans quelques minutes"}, 409)
            with lock, tempfile.TemporaryDirectory() as d:
                b = d + "/p.bundle"
                with open(b, "wb") as f:
                    f.write(body)
                if git("bundle", "verify", "-q", b, check=False).returncode != 0:
                    return self.send_json({"error": "bundle incomplet : faites d'abord nas.sh pull"}, 409)
                git("fetch", "-q", b, "+%s:refs/incoming/%s" % (BRANCH, BRANCH))
                new = "refs/incoming/" + BRANCH
                if git("merge-base", "--is-ancestor", BRANCH, new, check=False).returncode != 0:
                    return self.send_json({"error": "le NAS a des commits que le PC n'a pas : faites d'abord nas.sh pull"}, 409)
                git("reset", "-q", "--hard", new)
                refresh_cache()
            self.send_json({"ok": True, "head": state["head"]})
        else:
            self.send_json({"error": "inconnu"}, 404)


def watch_self():
    """S'arrête quand server.py change : run.sh relance la nouvelle version."""
    me = os.path.abspath(__file__)
    start = os.path.getmtime(me)
    while True:
        time.sleep(5)
        try:
            if os.path.getmtime(me) != start and (job is None or not job.is_alive()):
                log("server.py a changé : redémarrage")
                os._exit(0)
        except OSError:
            pass


if __name__ == "__main__":
    if not os.path.isdir(REPO + "/.git"):
        log("pas de dépôt (/data/repo) : en attente de /data/seed.bundle")
        time.sleep(30)
        sys.exit(1)
    with lock:
        refresh_cache()
    threading.Thread(target=watch_self, daemon=True).start()
    threading.Thread(target=fetch, args=(0,), daemon=True).start()
    log("service prêt sur le port %d, ma-tv = %s" % (PORT, state["head"][:12]))
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
