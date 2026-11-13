"""Writes requests.csv for idgen.jmx: every template x city, once single and once bulk.

Each row carries the JSON path of the first ID and the regex it must match. The regexes avoid commas
(no {6,}) because the CSV is split on commas.

Run: python loadtest/jmeter/make_requests.py
"""
import os
import random

PATTERNS = {
    "PG_RECEIPT": r"^PG-[A-Z]{3}-\d{4}-\d{2}-\d{6}\d*$",
    "TL_LICENSE": r"^TL/[A-Z]{3}/\d{4}/\d{5}\d*$",
    "PGR_COMPLAINT": r"^PGR-\d{8}\d*$",
    "WS_CONNECTION": r"^WS-[A-Z]{3}-\d{4}\d*-\d{2}-\d{4}$",
    "BPA_PERMIT": r"^BPA-[A-Z]{3}-\d{2}-\d{2}-\d{6}\d*$",
}
CITIES = ["pb.amritsar", "pb.ludhiana", "pb.jalandhar", "pb.patiala", "ka.bengaluru",
          "ka.mysuru", "mh.pune", "mh.nagpur", "tn.chennai", "tn.madurai"]


def main():
    rnd = random.Random(42)
    rows = []
    for template, regex in PATTERNS.items():
        for city in CITIES:
            rows.append(f"single,/api/ids,{template},{city},1,$.id,{regex}")
            rows.append(f"bulk,/api/ids/bulk,{template},{city},{rnd.randint(1, 50)},$.ids[0],{regex}")
    rnd.shuffle(rows)
    assert all(r.count(",") == 6 for r in rows), "a regex contains a comma"
    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "requests.csv")
    with open(out, "w", newline="\n") as f:
        f.write("kind,path,templateId,city,count,idPath,idRegex\n" + "\n".join(rows) + "\n")
    print(f"{len(rows)} rows -> {out}")


if __name__ == "__main__":
    main()
