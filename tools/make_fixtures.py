#!/usr/bin/env python3
"""Regenerate the JVM test fixtures from a real VPN Gate CSV snapshot.

Usage:
    python3 tools/make_fixtures.py <snapshot.csv> [--rows 5]

`app/src/test/resources/fixtures/vpngate_real_sample.csv` is a byte-faithful extract of a
live `https://www.vpngate.net/api/iphone/` response: the `*vpn_servers` marker,
the real header, N real rows *with their real Base64 configs untouched*, and the
closing `*` marker. CRLF line endings are preserved, because the parser has to
cope with them.

`app/src/test/resources/fixtures/edge_cases.csv` is hand-written here so the fixtures are
reproducible; it covers the shapes the real feed has been seen to produce plus
the truncation/corruption cases a plain-HTTP feed can suffer.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
FIXTURES = HERE.parent / "app" / "src" / "test" / "resources" / "fixtures"


def extract_real_sample(snapshot: pathlib.Path, rows: int) -> str:
    data = snapshot.read_bytes()
    lines = data.split(b"\r\n")
    if not lines or lines[0].strip() != b"*vpn_servers":
        sys.exit(f"error: {snapshot} does not start with '*vpn_servers' - is it a VPN Gate CSV?")

    header = lines[1]
    if not header.startswith(b"#HostName,IP,Score,Ping"):
        sys.exit("error: unexpected header line, refusing to build a fixture")

    body = [ln for ln in lines[2:] if ln and not ln.startswith(b"*")]
    if len(body) < rows:
        sys.exit(f"error: snapshot has only {len(body)} rows, asked for {rows}")

    kept = body[:rows]
    out = [b"*vpn_servers", header] + kept + [b"*", b""]
    return b"\r\n".join(out).decode("utf-8")


EDGE_CASES = [
    # 1. header present, CRLF, a quoted Operator containing a comma, healthy row
    b'*vpn_servers',
    b'#HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64',
    b'good-quoted,10.0.0.1,5000,12,1000000,Japan,JP,3,1000,50,5000,2weeks,"Daiyuu Nobori, Japan",,ZGV2IHR1bg0KcHJvdG8gdGNwDQpyZW1vdGUgMTAuMC4wLjEgNDQzDQpjbGllbnQNCmRldiB0dW4NCjxjYT4NCkNBQ0FDQQ0KPC9jYT4NCg==',
    # 2. ping 0 -> dead, must be rejected by ServerSelector
    b'dead-ping-zero,10.0.0.2,5000,0,1000000,Japan,JP,3,1000,50,5000,2weeks,op,,ZGV2IHR1bg0KcHJvdG8gdGNwDQpyZW1vdGUgMTAuMC4wLjIgNDQzDQpjbGllbnQNCjxjYT4NCkNBQ0FDQQ0KPC9jYT4NCg==',
    # 3. blank Base64 -> must be skipped by the parser
    b'no-config,10.0.0.3,5000,20,1000000,Japan,JP,3,1000,50,5000,2weeks,op,,',
    # 4. too few columns -> must be skipped
    b'truncated,10.0.0.4,5000,30,1000000,Japan',
    # 5. non numeric ping -> defaults to 0, then rejected as unreachable
    b'bad-numbers,10.0.0.5,not-a-score,NaN,fast,Korea,KR,x,y,z,w,2weeks,op,,ZGV2IHR1bg0KcHJvdG8gdGNwDQpyZW1vdGUgMTAuMC4wLjUgNDQzDQpjbGllbnQNCjxjYT4NCkNBQ0FDQQ0KPC9jYT4NCg==',
    # 6. second healthy row, slower, so ordering is observable
    b'good-slow,10.0.0.6,9000,95,2000000,Korea Republic of,KR,7,1000,50,5000,2weeks,op,,ZGV2IHR1bg0KcHJvdG8gdWRwDQpyZW1vdGUgMTAuMC4wLjYgMTE5NA0KY2xpZW50DQo8Y2E+DQpDQUNBQ0ENCjwvY2E+DQo=',
    # 7. escaped double quotes inside a quoted field
    b'good-escapes,10.0.0.7,7000,25,3000000,Japan,JP,1,1000,50,5000,2weeks,"he said ""hi"", ok",,ZGV2IHR1bg0KcHJvdG8gdGNwDQpyZW1vdGUgMTAuMC4wLjcgNDQzDQpjbGllbnQNCjxjYT4NCkNBQ0FDQQ0KPC9jYT4NCg==',
    # 8. config that decodes but has no CA -> must fail the decode stage
    b'good-noca,10.0.0.8,7000,30,3000000,Japan,JP,1,1000,50,5000,2weeks,op,,ZGV2IHR1bg0KcHJvdG8gdGNwDQpyZW1vdGUgMTAuMC4wLjggNDQzDQpjbGllbnQNCg==',
    # 9. hostile profile: script execution + credential prompt + management port,
    #    all of which OvpnConfigDecoder must strip before OpenVPN ever sees them
    b'hostile,10.0.0.9,8000,5,4000000,Japan,JP,1,1000,50,5000,2weeks,op,,ZGV2IHR1bg0KcHJvdG8gdGNwDQpyZW1vdGUgMTAuMC4wLjkgNDQzDQpjbGllbnQNCnVwIC90bXAvZXZpbC5zaA0Kc2NyaXB0LXNlY3VyaXR5IDMNCmF1dGgtdXNlci1wYXNzDQptYW5hZ2VtZW50IDEyNy4wLjAuMSA5OTk5DQo8Y2E+DQpDQUNBQ0ENCjwvY2E+DQo=',
    b'*',
]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("snapshot", type=pathlib.Path, help="real VPN Gate CSV snapshot")
    parser.add_argument("--rows", type=int, default=5)
    args = parser.parse_args()

    FIXTURES.mkdir(parents=True, exist_ok=True)

    real = extract_real_sample(args.snapshot, args.rows)
    real_path = FIXTURES / "vpngate_real_sample.csv"
    real_path.write_text(real, encoding="utf-8", newline="")

    edge_path = FIXTURES / "edge_cases.csv"
    edge_path.write_bytes(b"\r\n".join(EDGE_CASES) + b"\r\n")

    manifest = {
        "source_snapshot_sha256": hashlib.sha256(args.snapshot.read_bytes()).hexdigest(),
        "source_snapshot": str(args.snapshot),
        "real_sample": {
            "file": real_path.name,
            "bytes": real_path.stat().st_size,
            "rows": args.rows,
            "sha256": hashlib.sha256(real_path.read_bytes()).hexdigest(),
        },
        "edge_cases": {
            "file": edge_path.name,
            "bytes": edge_path.stat().st_size,
            "sha256": hashlib.sha256(edge_path.read_bytes()).hexdigest(),
        },
    }
    (FIXTURES / "PROVENANCE.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
