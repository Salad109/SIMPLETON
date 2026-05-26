#!/usr/bin/env python3
"""Reconstruct SOCRATES's TLE catalog and load it into the satellite table.

socrates.csv carries DSE_1 / DSE_2 (days since each side's TLE epoch) for every conjunction.
Per NORAD that means a target TLE epoch (TCA - DSE * 1 day). For each, pick the gp_history
row whose EPOCH is closest within MATCH_TOL_SEC. Wipes the satellite table (CASCADE).
"""
import csv
import json
import subprocess
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from http.cookiejar import CookieJar
from pathlib import Path

import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ENV_FILE = ROOT / ".env"
SOCRATES_CSV = HERE / "socrates.csv"
OUT_DIR = HERE / ".cache"
SLICE_DIR = OUT_DIR / "slices"
CSV_PATH = OUT_DIR / "satellite.csv"

T_END = datetime(2026, 5, 9, 19, 0, 0, tzinfo=timezone.utc)
T_QUERY_CUTOFF = datetime(2026, 5, 10, 7, 2, 0, tzinfo=timezone.utc)
T_START = T_END - timedelta(days=30)
SLICE_HOURS = 12
MATCH_TOL_SEC = 60.0

COLUMNS = [
    ("norad_cat_id",        "NORAD_CAT_ID",        "raw"),
    ("object_name",         "OBJECT_NAME",         "raw"),
    ("object_id",           "OBJECT_ID",           "raw"),
    ("object_type",         "OBJECT_TYPE",         "raw"),
    ("classification_type", "CLASSIFICATION_TYPE", "raw"),
    ("country_code",        "COUNTRY_CODE",        "raw"),
    ("launch_date",         "LAUNCH_DATE",         "raw"),
    ("site",                "SITE",                "raw"),
    ("decay_date",          "DECAY_DATE",          "raw"),
    ("epoch",               "EPOCH",               "ts_utc"),
    ("creation_date",       "CREATION_DATE",       "ts"),
    ("tle_line0",           "TLE_LINE0",           "raw"),
    ("tle_line1",           "TLE_LINE1",           "raw"),
    ("tle_line2",           "TLE_LINE2",           "raw"),
    ("mean_motion",         "MEAN_MOTION",         "raw"),
    ("mean_motion_dot",     "MEAN_MOTION_DOT",     "raw"),
    ("mean_motion_ddot",    "MEAN_MOTION_DDOT",    "raw"),
    ("eccentricity",        "ECCENTRICITY",        "raw"),
    ("inclination",         "INCLINATION",         "raw"),
    ("raan",                "RA_OF_ASC_NODE",      "raw"),
    ("arg_perigee",         "ARG_OF_PERICENTER",   "raw"),
    ("mean_anomaly",        "MEAN_ANOMALY",        "raw"),
    ("ephemeris_type",      "EPHEMERIS_TYPE",      "raw"),
    ("bstar",               "BSTAR",               "raw"),
    ("rcs_size",            "RCS_SIZE",            "raw"),
    ("element_set_no",      "ELEMENT_SET_NO",      "raw"),
    ("rev_at_epoch",        "REV_AT_EPOCH",        "raw"),
    ("semi_major_axis_km",  "SEMIMAJOR_AXIS",      "raw"),
    ("period",              "PERIOD",              "raw"),
    ("perigee_km",          "PERIAPSIS",           "raw"),
    ("apogee_km",           "APOAPSIS",            "raw"),
    ("file_number",         "FILE",                "raw"),
    ("gp_id",               "GP_ID",               "raw"),
]


def load_env():
    env = {}
    for line in ENV_FILE.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def time_slices():
    cur = T_START
    while cur < T_END:
        nxt = min(cur + timedelta(hours=SLICE_HOURS), T_END)
        yield cur, nxt
        cur = nxt


def fmt_url_dt(dt):
    return dt.strftime("%Y-%m-%d") + "%20" + dt.strftime("%H:%M:%S")


def login(env):
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    body = urllib.parse.urlencode({
        "identity": env["SPACETRACK_USERNAME"],
        "password": env["SPACETRACK_PASSWORD"],
    }).encode()
    req = urllib.request.Request("https://www.space-track.org/ajaxauth/login", data=body)
    with opener.open(req, timeout=60) as resp:
        if resp.status != 200:
            raise SystemExit(f"login failed: HTTP {resp.status}")
    return opener


def is_cached(dest):
    return dest.exists() and dest.stat().st_size > 0


def fetch_slice(opener, start, end, dest):
    url = (
        "https://www.space-track.org/basicspacedata/query/class/gp_history"
        "/DECAY_DATE/null-val"
        f"/EPOCH/{fmt_url_dt(start)}--{fmt_url_dt(end)}"
        f"/CREATION_DATE/%3C{fmt_url_dt(T_QUERY_CUTOFF)}"
        "/orderby/NORAD_CAT_ID,EPOCH"
        "/format/json"
    )
    print(f"  GET {dest.name} {start.isoformat()} -> {end.isoformat()}")
    req = urllib.request.Request(url)
    with opener.open(req, timeout=600) as resp:
        if resp.status != 200:
            raise SystemExit(f"slice fetch failed: HTTP {resp.status}")
        data = resp.read()
    tmp = dest.with_suffix(dest.suffix + ".part")
    tmp.write_bytes(data)
    tmp.rename(dest)


def extract_targets(socrates_path):
    soc = pd.read_csv(socrates_path)
    soc["tca"] = pd.to_datetime(soc["TCA"], utc=True)
    side1 = pd.DataFrame({
        "norad": soc["NORAD_CAT_ID_1"].astype(int),
        "epoch": soc["tca"] - pd.to_timedelta(soc["DSE_1"], unit="D"),
    })
    side2 = pd.DataFrame({
        "norad": soc["NORAD_CAT_ID_2"].astype(int),
        "epoch": soc["tca"] - pd.to_timedelta(soc["DSE_2"], unit="D"),
    })
    allside = pd.concat([side1, side2])
    allside["epoch_s"] = allside["epoch"].astype("int64") / 1e9
    per = allside.groupby("norad")["epoch_s"].median().reset_index()
    per["epoch"] = pd.to_datetime(per["epoch_s"], unit="s", utc=True).dt.to_pydatetime()
    targets = dict(zip(per["norad"].astype(int), per["epoch"]))
    print(f"  parsed {len(targets)} NORAD -> target-epoch entries")
    return targets


def merge_by_target(slice_files, targets):
    best = {}
    for p in slice_files:
        for r in json.loads(p.read_text()):
            try:
                ncid = int(r["NORAD_CAT_ID"])
            except (TypeError, ValueError, KeyError):
                continue
            target = targets.get(ncid)
            if target is None or not r.get("TLE_LINE1") or not r.get("TLE_LINE2"):
                continue
            ep = r.get("EPOCH")
            if not ep:
                continue
            try:
                ep_dt = datetime.fromisoformat(ep).replace(tzinfo=timezone.utc)
            except ValueError:
                continue
            diff = abs((ep_dt - target).total_seconds())
            if diff > MATCH_TOL_SEC:
                continue
            cur = best.get(ncid)
            if cur is None or diff < cur[0]:
                best[ncid] = (diff, r)
    matched = {n: rec for n, (_, rec) in best.items()}
    missing = len(targets) - len(matched)
    print(f"matched {len(matched)} / {len(targets)} ({len(matched)/len(targets)*100:.1f}%); "
          f"{missing} no match within {MATCH_TOL_SEC}s")
    return matched


def render(value, kind):
    if value is None:
        return ""
    s = str(value).strip()
    if not s:
        return ""
    if kind == "ts_utc":
        return s.replace("T", " ") + "+00"
    if kind == "ts":
        return s.replace("T", " ")
    return s


def write_csv(records, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        for ncid in sorted(records.keys()):
            r = records[ncid]
            w.writerow([render(r.get(jk), kind) for (_, jk, kind) in COLUMNS])
    print(f"wrote {path}")


def load_into_db(env, csv_path):
    db = env.get("POSTGRES_DB", "conjunction_db")
    user = env.get("POSTGRES_USER", "conjunction_user")
    pw = env.get("POSTGRES_PASSWORD", "")
    cols = ",".join(c for (c, _, _) in COLUMNS)
    container_csv = "/tmp/socrates-satellite.csv"

    subprocess.run(
        ["docker", "compose", "cp", str(csv_path), f"postgres:{container_csv}"],
        cwd=ROOT, check=True,
    )

    sql = (
        "BEGIN;\n"
        "TRUNCATE satellite CASCADE;\n"
        f"\\copy satellite ({cols}) FROM '{container_csv}' WITH (FORMAT csv);\n"
        "COMMIT;\n"
    ).encode("utf-8")

    cmd = [
        "docker", "compose", "exec", "-T",
        "-e", f"PGPASSWORD={pw}",
        "postgres", "psql", "-v", "ON_ERROR_STOP=1",
        "-U", user, "-d", db,
    ]
    proc = subprocess.run(cmd, input=sql, cwd=ROOT, capture_output=True)
    sys.stdout.write(proc.stdout.decode("utf-8", errors="replace"))
    sys.stderr.write(proc.stderr.decode("utf-8", errors="replace"))
    if proc.returncode != 0:
        raise SystemExit(f"psql failed (rc={proc.returncode})")


def main():
    env = load_env()
    SLICE_DIR.mkdir(parents=True, exist_ok=True)

    targets = extract_targets(SOCRATES_CSV)

    print(f"fetching gp_history: {T_START.isoformat()} -> {T_END.isoformat()} ({SLICE_HOURS}h slices)")
    pending = [(i, s, e, SLICE_DIR / f"slice-{i:02d}.json") for i, (s, e) in enumerate(time_slices())]
    misses = [job for job in pending if not is_cached(job[3])]
    for _, _, _, dest in pending:
        if is_cached(dest):
            print(f"  skip {dest.name} (cached)")
    if misses:
        opener = login(env)
        for _, s, e, dest in misses:
            fetch_slice(opener, s, e, dest)
    else:
        print("  all slices cached, skipping Space-Track login")

    files = sorted(SLICE_DIR.glob("slice-*.json"))
    records = merge_by_target(files, targets)
    write_csv(records, CSV_PATH)
    load_into_db(env, CSV_PATH)


if __name__ == "__main__":
    main()
