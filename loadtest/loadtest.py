#!/usr/bin/env python3
"""Concurrency test for the ID service: proves zero duplicates under parallel load.

Starts N genuinely parallel OS processes (multiprocessing, not threads). Each one loops for D seconds over
random (template, city) pairs, sending single or bulk (1-50) requests through Nginx to all backend replicas.
Every ID returned is collected; the run fails if any ID appears twice.

Errors are expected and reported but do not fail the run: EST_ALLOTMENT always returns 422, and requests
fail while backends restart (`docker compose restart backend` mid-run). A failed request issues nothing the
client sees, so it can only leave a gap, never a duplicate.

Run:   docker compose --profile test run --rm loadtest
       python loadtest/loadtest.py --target http://localhost:8080 --workers 50 --duration 30
Exit:  0 = no duplicates, 1 = duplicates found or no IDs issued.
"""

import argparse
import collections
import json
import multiprocessing
import os
import random
import sys
import time
import urllib.error
import urllib.request

TEMPLATES = ["PG_RECEIPT", "TL_LICENSE", "PGR_COMPLAINT", "WS_CONNECTION", "BPA_PERMIT", "EST_ALLOTMENT"]
CITIES = ["pb.amritsar", "LDH", "jal", "PTA", "ka.bengaluru", "MYS", "mh.pune", "NGP", "tn.chennai", "mdu"]


def call(target, path, body, timeout):
    """POST JSON; returns (status, parsed body or None, upstream). Network errors return status 0."""
    req = urllib.request.Request(target + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read()), r.headers.get("X-Upstream", "?")
    except urllib.error.HTTPError as e:
        try:
            payload = json.loads(e.read())
        except ValueError:
            payload = None
        return e.code, payload, e.headers.get("X-Upstream", "?") if e.headers else "?"
    except (urllib.error.URLError, OSError, ValueError):
        return 0, None, "?"


def worker(args):
    """One OS process. Returns the IDs it received and counters for the summary."""
    index, target, duration, bulk_ratio, max_bulk, timeout = args
    rnd = random.Random(index * 7919 + os.getpid())
    ids, statuses, errors, upstreams = [], collections.Counter(), collections.Counter(), collections.Counter()
    requests = 0
    deadline = time.monotonic() + duration
    while time.monotonic() < deadline:
        body = {"templateId": rnd.choice(TEMPLATES), "city": rnd.choice(CITIES)}
        bulk = rnd.random() < bulk_ratio
        if bulk:
            body["count"] = rnd.randint(1, max_bulk)
        status, payload, upstream = call(target, "/api/ids/bulk" if bulk else "/api/ids", body, timeout)
        requests += 1
        statuses[status] += 1
        if status == 200:
            got = payload["ids"] if bulk else [payload["id"]]
            if bulk and len(got) != body["count"]:
                errors["bulk returned wrong count"] += 1
            ids.extend(got)
            upstreams[upstream] += 1
        elif payload and "error" in payload:
            errors[f'{status} {payload["error"]["code"]}'] += 1
        else:
            errors[f"{status or 'no response'} (unstructured)"] += 1
        if status == 0 or status >= 500:
            time.sleep(0.05)  # no backend or no database right now; back off a little
    return ids, requests, statuses, errors, upstreams


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--target", default=os.environ.get("TARGET", "http://localhost:8080"))
    p.add_argument("--workers", type=int, default=int(os.environ.get("WORKERS", 50)))
    p.add_argument("--duration", type=float, default=float(os.environ.get("DURATION", 30)), help="seconds")
    p.add_argument("--bulk-ratio", type=float, default=0.3, help="share of requests that are bulk")
    p.add_argument("--max-bulk", type=int, default=50)
    p.add_argument("--timeout", type=float, default=10)
    p.add_argument("--out", default=os.environ.get("OUT", "out/loadtest-result.json"), help="JSON summary")
    a = p.parse_args()

    target = a.target.rstrip("/")
    print(f"Target {target}: {a.workers} parallel processes for {a.duration:.0f}s, "
          f"{a.bulk_ratio:.0%} bulk (1-{a.max_bulk}), all templates x cities", flush=True)

    started = time.monotonic()
    with multiprocessing.Pool(a.workers) as pool:
        results = pool.map(worker, [(i, target, a.duration, a.bulk_ratio, a.max_bulk, a.timeout)
                                    for i in range(a.workers)])
    elapsed = time.monotonic() - started

    ids, requests = [], 0
    statuses, errors, upstreams = collections.Counter(), collections.Counter(), collections.Counter()
    for w_ids, w_requests, w_statuses, w_errors, w_upstreams in results:
        ids.extend(w_ids)
        requests += w_requests
        statuses.update(w_statuses)
        errors.update(w_errors)
        upstreams.update(w_upstreams)

    counts = collections.Counter(ids)
    duplicates = {i: n for i, n in counts.items() if n > 1}
    by_template = collections.Counter(i.split("-")[0].split("/")[0] for i in ids)

    summary = {
        "target": target, "workers": a.workers, "duration_s": round(elapsed, 1),
        "requests": requests, "requests_per_s": round(requests / elapsed, 1),
        "ids": len(ids), "distinct_ids": len(counts), "duplicates": len(duplicates),
        "ids_per_s": round(len(ids) / elapsed, 1),
        "http_status": {str(k): v for k, v in sorted(statuses.items())},
        "errors": dict(errors.most_common()),
        "ids_by_prefix": dict(by_template.most_common()),
        "backend_instances": dict(upstreams.most_common()),
        "duplicate_examples": dict(list(duplicates.items())[:10]),
    }

    print()
    print(f"Requests      {requests:>9,}  ({summary['requests_per_s']:,} req/s over {elapsed:.1f}s)")
    print(f"IDs issued    {len(ids):>9,}  ({summary['ids_per_s']:,} IDs/s)")
    print(f"Distinct IDs  {len(counts):>9,}")
    print(f"DUPLICATES    {len(duplicates):>9,}")
    print(f"HTTP status   {summary['http_status']}")
    print(f"Errors        {summary['errors'] or 'none'}")
    print(f"By prefix     {summary['ids_by_prefix']}")
    print(f"Instances     {summary['backend_instances']}")

    if a.out:
        os.makedirs(os.path.dirname(a.out) or ".", exist_ok=True)
        with open(a.out, "w") as f:
            json.dump(summary, f, indent=2)
        print(f"Summary saved to {a.out}")

    if duplicates:
        print(f"\nFAIL: {len(duplicates)} IDs were issued more than once, e.g. {summary['duplicate_examples']}")
        return 1
    if not ids:
        print("\nFAIL: no IDs were issued; is the service up?")
        return 1
    print(f"\nPASS: {len(ids):,} IDs, 0 duplicates")
    return 0


if __name__ == "__main__":
    sys.exit(main())
