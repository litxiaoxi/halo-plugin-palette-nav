#!/usr/bin/env python3
"""End-to-end API tests for the palette-nav plugin.

Runs against a dedicated Halo instance (default http://localhost:8096) with the
plugin STARTED. Every resource it creates is prefixed with a per-run tag and
deleted in a final cleanup pass; demo data on a shared instance is only read.

    python tools/test_integration.py
    PN_BASE=http://localhost:8095 python tools/test_integration.py
"""

from __future__ import annotations

import datetime as dt
import json
import os
import sys
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

import requests

BASE = os.environ.get("PN_BASE", "http://localhost:8096").rstrip("/")
USER = os.environ.get("PN_USER", "admin")
PASSWORD = os.environ.get("PN_PASSWORD", "admin123")
TAG = uuid.uuid4().hex[:8]
RUN_ID = f"pntest-{TAG}"

MODEL_API = "/apis/palette-nav.halo.run/v1alpha1"
CONSOLE_API = "/apis/console.api.palette-nav.halo.run/v1alpha1"
# Settings are business data in a standalone ConfigMap, not the plugin's
# spec.configMapName (see plugin.yaml): read/write the ConfigMap directly.
CONFIG_API = "/api/v1alpha1/configmaps/palette-nav-config"
CONFIG_MAP_BODY = {
    "apiVersion": "v1alpha1",
    "kind": "ConfigMap",
    "metadata": {"name": "palette-nav-config"},
}

PLURALS = {
    "NavCategory": "navcategories",
    "NavLink": "navlinks",
    "NavSearchEngine": "navsearchengines",
    "NavQuickLink": "navquicklinks",
    "NavVisitStat": "navvisitstats",
    "NavVisitVisitor": "navvisitvisitors",
}

session = requests.Session()
session.auth = (USER, PASSWORD)
session.headers.update({"Accept": "application/json"})
created: list[tuple[str, str]] = []
settings_backup: dict[str, Any] | None = None


@dataclass
class Result:
    passed: int = 0
    failed: int = 0
    failures: list[str] = field(default_factory=list)

    def check(self, name: str, condition: bool, detail: str = "") -> bool:
        if condition:
            self.passed += 1
            print(f"  PASS  {name}")
            return True
        self.failed += 1
        self.failures.append(f"{name}: {detail}")
        print(f"  FAIL  {name}  {detail}")
        return False

    def equal(self, name: str, actual: Any, expected: Any) -> bool:
        return self.check(name, actual == expected, f"expected={expected!r} actual={actual!r}")


R = Result()


def api(path: str) -> str:
    return f"{BASE}{path}"


def request(method: str, path: str, *, auth: bool = True, **kwargs) -> requests.Response:
    kwargs.setdefault("timeout", 45)
    # Drop undefined query params so requests does not send "None".
    params = kwargs.get("params")
    if isinstance(params, dict):
        kwargs["params"] = {k: v for k, v in params.items() if v is not None}
    if auth:
        return session.request(method, api(path), **kwargs)
    return requests.request(method, api(path), **kwargs)


def model_path(kind: str, name: str | None = None) -> str:
    plural = PLURALS[kind]
    return f"{MODEL_API}/{plural}" + (f"/{name}" if name else "")


def read_config() -> dict[str, Any]:
    """Returns the flat settings groups stored as JSON strings in the ConfigMap."""
    r = request("GET", CONFIG_API)
    if r.status_code == 404:
        return {}
    if r.status_code >= 400:
        raise AssertionError(f"read config -> {r.status_code}: {r.text[:200]}")
    data = (r.json() or {}).get("data") or {}
    return {key: json.loads(value) for key, value in data.items()
            if isinstance(value, str) and value.strip()}


def write_config(raw: dict[str, Any]) -> requests.Response:
    """Replaces the ConfigMap data with the given flat settings groups."""
    r = request("GET", CONFIG_API)
    body = r.json() if r.status_code == 200 else dict(CONFIG_MAP_BODY)
    body["data"] = {key: json.dumps(value, ensure_ascii=False)
                    for key, value in raw.items()}
    return request("PUT", CONFIG_API, json=body)


def entity(kind: str, name: str, spec: dict[str, Any],
           labels: dict[str, str] | None = None) -> dict[str, Any]:
    metadata: dict[str, Any] = {"name": name}
    if labels:
        metadata["labels"] = labels
    return {
        "apiVersion": "palette-nav.halo.run/v1alpha1",
        "kind": kind,
        "metadata": metadata,
        "spec": spec,
    }


def create(kind: str, name: str, spec: dict[str, Any],
           labels: dict[str, str] | None = None) -> dict[str, Any]:
    payload = entity(kind, name, spec, labels)
    r = request("POST", model_path(kind), json=payload)
    if r.status_code != 201:
        raise AssertionError(f"create {kind}/{name} -> {r.status_code}: {r.text[:300]}")
    created.append((PLURALS[kind], name))
    return r.json()


def get(kind: str, name: str) -> requests.Response:
    return request("GET", model_path(kind, name))


def delete(kind: str, name: str, *, via_console: bool = False) -> requests.Response:
    if via_console:
        return request("DELETE", f"{CONSOLE_API}/category/{name}")
    return request("DELETE", model_path(kind, name))


def list_models(kind: str, **params) -> dict[str, Any]:
    r = request("GET", model_path(kind), params=params)
    r.raise_for_status()
    return r.json()


def list_all(kind: str) -> list[dict[str, Any]]:
    items: list[dict[str, Any]] = []
    page_number = 1
    while True:
        body = list_models(kind, page=page_number, size=100)
        items.extend(body.get("items") or [])
        if not body.get("hasNext"):
            return items
        page_number += 1


def wait_until(predicate: Callable[[], bool], timeout: float = 10.0,
               interval: float = 0.2) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if predicate():
                return True
        except (requests.RequestException, ValueError):
            pass
        time.sleep(interval)
    return False


def stats() -> dict[str, Any]:
    r = request("GET", f"{CONSOLE_API}/stats")
    r.raise_for_status()
    return r.json()


def today() -> str:
    return dt.date.today().isoformat()


def visit(user_agent: str, headers: dict[str, str] | None = None,
          path: str = "/nav") -> requests.Response:
    merged = {"User-Agent": user_agent}
    if headers:
        merged.update(headers)
    return requests.get(api(path), headers=merged, timeout=45)


def nav_html(user_agent: str = f"pn-test/{TAG}") -> str:
    r = visit(user_agent)
    if r.status_code != 200:
        raise AssertionError(f"GET /nav -> {r.status_code}: {r.text[:200]}")
    return r.text


# --------------------------------------------------------------------------- #
# Setup / cleanup
# --------------------------------------------------------------------------- #

def ensure_admin() -> None:
    r = request("GET", "/apis/plugin.halo.run/v1alpha1/plugins/palette-nav")
    if r.status_code in (401, 403):
        raise SystemExit(
            f"Cannot authenticate as {USER}. Set PN_USER/PN_PASSWORD or initialize "
            f"the instance at {BASE}."
        )
    if r.status_code == 404:
        raise SystemExit(f"Plugin palette-nav is not installed at {BASE}")
    if r.status_code != 200 or "json" not in (r.headers.get("Content-Type") or ""):
        raise SystemExit(
            f"Unexpected response for {USER} at {BASE}: {r.status_code} "
            f"{r.headers.get('Content-Type')} {r.text[:120]!r}"
        )
    phase = (r.json().get("status") or {}).get("phase")
    if phase != "STARTED":
        raise SystemExit(f"Plugin is {phase}, expected STARTED")


def set_plugin_enabled(enabled: bool) -> requests.Response:
    return request(
        "PATCH",
        "/apis/plugin.halo.run/v1alpha1/plugins/palette-nav",
        data=json.dumps([{"op": "add", "path": "/spec/enabled", "value": enabled}]),
        headers={"Content-Type": "application/json-patch+json"},
    )


def cleanup() -> None:
    seen: set[tuple[str, str]] = set()
    for plural, name in reversed(created):
        if (plural, name) in seen:
            continue
        seen.add((plural, name))
        try:
            request("DELETE", f"{MODEL_API}/{plural}/{name}", timeout=20)
        except requests.RequestException:
            pass


def restore_settings() -> None:
    if settings_backup is None:
        return
    r = write_config(settings_backup)
    if r.status_code >= 400:
        print(f"WARNING: failed to restore settings: {r.status_code} {r.text[:200]}")


# --------------------------------------------------------------------------- #
# Tests
# --------------------------------------------------------------------------- #

def test_model_crud() -> None:
    print("\n[model CRUD]")
    cat = f"{RUN_ID}-crud-cat"
    body = create("NavCategory", cat, {"displayName": f"CRUD {TAG}", "sort": 0,
                                       "visible": True})
    R.equal("create category returns stored spec", body["spec"]["displayName"],
            f"CRUD {TAG}")
    R.equal("create category starts at version 0", body["metadata"].get("version"), 0)

    duplicate = request("POST", model_path("NavCategory"),
                        json=entity("NavCategory", cat, {"displayName": "dup"}))
    R.equal("duplicate name rejected", duplicate.status_code, 400)

    stale = body
    updated = request("PUT", model_path("NavCategory", cat), json={
        **stale, "spec": {**stale["spec"], "displayName": f"CRUD updated {TAG}"}})
    R.equal("update category", updated.status_code, 200)
    R.equal("update bumps version", updated.json()["metadata"].get("version"), 1)

    conflict = request("PUT", model_path("NavCategory", cat), json={
        **stale, "spec": {**stale["spec"], "displayName": "stale write"}})
    R.equal("stale version is rejected with 409", conflict.status_code, 409)

    fetched = get("NavCategory", cat)
    R.equal("get category", fetched.status_code, 200)
    R.equal("update persisted", fetched.json()["spec"]["displayName"],
            f"CRUD updated {TAG}")

    invalid = request("POST", model_path("NavCategory"),
                      json=entity("NavCategory", f"{RUN_ID}-bad-cat", {"sort": 1}))
    R.equal("missing required displayName rejected", invalid.status_code, 400)

    for kind, name, spec in [
        ("NavLink", f"{RUN_ID}-crud-link", {"categoryName": cat, "displayName": "L",
                                           "url": "https://example.com", "sort": 0,
                                           "visible": True}),
        ("NavSearchEngine", f"{RUN_ID}-crud-engine",
         {"alias": f"e{TAG}", "displayName": "E", "urlTemplate":
          "https://example.com/?q=", "sort": 0, "visible": True}),
        ("NavQuickLink", f"{RUN_ID}-crud-quick",
         {"displayName": "Q", "url": "/about", "sort": 0, "visible": True}),
    ]:
        created_entity = create(kind, name, spec)
        R.equal(f"create {kind}", created_entity["metadata"]["name"], name)
        deleted = delete(kind, name)
        # Halo's generic extension DELETE answers 200; the plugin's cascade
        # endpoint answers 204.
        R.check(f"delete {kind}", deleted.status_code in (200, 204),
                str(deleted.status_code))
        R.check(f"{kind} actually disappears",
                wait_until(lambda k=kind, n=name: get(k, n).status_code == 404))

    deleted_cat = delete("NavCategory", cat)
    R.check("delete category", deleted_cat.status_code in (200, 204),
            str(deleted_cat.status_code))
    R.check("category actually disappears",
            wait_until(lambda: get("NavCategory", cat).status_code == 404))

    # The console's delete helper relies on fieldSelector name lookup.
    listed = list_models("NavCategory", fieldSelector="metadata.name=does-not-exist")
    R.equal("fieldSelector returns no rows for a missing name", len(listed["items"]), 0)


def test_pagination_and_validation() -> None:
    print("\n[pagination and validation]")
    for index in range(3):
        create("NavCategory", f"{RUN_ID}-page-{index}",
               {"displayName": f"Page {index} {TAG}", "sort": index * 10,
                "visible": True})
    first = list_models("NavCategory", page=1, size=2)
    R.check("list reports hasNext on page 1", first.get("hasNext") is True,
            str(first.get("hasNext")))
    R.equal("list reports the requested page size", len(first["items"]), 2)
    second = list_models("NavCategory", page=2, size=2)
    R.check("page 2 exists", len(second["items"]) >= 1)

    too_long = request("POST", model_path("NavCategory"),
                       json=entity("NavCategory", f"{RUN_ID}-long",
                                   {"displayName": "x" * 51}))
    R.equal("maxLength is enforced", too_long.status_code, 400)


def test_reorder() -> None:
    print("\n[reorder]")
    names = [f"{RUN_ID}-sort-{i}" for i in range(3)]
    for index, name in enumerate(names):
        create("NavCategory", name, {"displayName": f"Sort {index} {TAG}", "sort": 0,
                                     "visible": True})

    payload = {"kind": "NavCategory", "items": [
        {"name": names[0], "sort": 30},
        {"name": names[1], "sort": 10},
        {"name": names[2], "sort": 20},
    ]}
    r = request("POST", f"{CONSOLE_API}/reorder", json=payload)
    R.equal("reorder succeeds", r.status_code, 200)
    R.equal("reorder responds with no-store",
            r.headers.get("Cache-Control"), "no-store")
    sorts = {name: get("NavCategory", name).json()["spec"]["sort"] for name in names}
    R.equal("sort values persisted", sorts,
            {names[0]: 30, names[1]: 10, names[2]: 20})
    versions = {name: get("NavCategory", name).json()["metadata"]["version"]
                for name in names}
    R.check("reorder advanced metadata.version (console syncs it back)",
            all(v >= 1 for v in versions.values()), str(versions))

    # A null entry must not crash the flux; a valid entry beside it still applies.
    mixed = request("POST", f"{CONSOLE_API}/reorder",
                    json={"kind": "NavCategory",
                          "items": [None, {"name": names[0], "sort": 40}]})
    R.equal("null items are skipped, not a 500", mixed.status_code, 200)
    R.equal("valid sibling item still applied",
            get("NavCategory", names[0]).json()["spec"]["sort"], 40)

    all_null = request("POST", f"{CONSOLE_API}/reorder",
                       json={"kind": "NavCategory", "items": [None]})
    R.equal("all-null item list is a 400", all_null.status_code, 400)

    for label, body in [
        ("empty items", {"kind": "NavCategory", "items": []}),
        ("blank kind", {"kind": "", "items": [{"name": names[0], "sort": 1}]}),
        ("unknown kind", {"kind": "NavPost", "items": [{"name": names[0],
                                                        "sort": 1}]}),
    ]:
        r = request("POST", f"{CONSOLE_API}/reorder", json=body)
        R.equal(f"reorder rejects {label}", r.status_code, 400)

    bad_json = request("POST", f"{CONSOLE_API}/reorder",
                       data="{not json", headers={"Content-Type": "application/json"})
    R.equal("malformed JSON is rejected", bad_json.status_code, 400)

    oversized = request("POST", f"{CONSOLE_API}/reorder",
                        data=json.dumps({"kind": "NavCategory",
                                         "items": [{"name": "x" * 1024 * 1024}]}),
                        headers={"Content-Type": "application/json"})
    R.equal("oversized reorder body is 413", oversized.status_code, 413)

    missing = request("POST", f"{CONSOLE_API}/reorder",
                      json={"kind": "NavCategory",
                            "items": [{"name": f"{RUN_ID}-gone", "sort": 10}]})
    R.equal("missing entity is ignored", missing.status_code, 200)

    link_cat = f"{RUN_ID}-sort-links-cat"
    create("NavCategory", link_cat, {"displayName": f"Sort links {TAG}", "sort": 0,
                                     "visible": True})
    links = [f"{RUN_ID}-sort-link-{i}" for i in range(2)]
    for index, name in enumerate(links):
        create("NavLink", name, {"categoryName": link_cat, "displayName": name,
                                 "url": f"https://example.com/{index}", "sort": 0,
                                 "visible": True})
    r = request("POST", f"{CONSOLE_API}/reorder",
                json={"kind": "NavLink",
                      "items": [{"name": links[1], "sort": 10},
                                {"name": links[0], "sort": 20}]})
    R.equal("reorder links", r.status_code, 200)
    R.equal("link sort values persisted",
            [get("NavLink", n).json()["spec"]["sort"] for n in links], [20, 10])


def test_cascade_delete() -> None:
    print("\n[cascade delete]")
    cat = f"{RUN_ID}-cascade-cat"
    create("NavCategory", cat, {"displayName": f"Cascade {TAG}", "sort": 0,
                                "visible": True})
    own = [f"{RUN_ID}-cascade-link-{i}" for i in range(2)]
    for index, name in enumerate(own):
        create("NavLink", name, {"categoryName": cat, "displayName": str(index),
                                 "url": f"https://example.com/c{index}", "sort": 0,
                                 "visible": True})
    survivor = f"{RUN_ID}-survivor-link"
    other_cat = f"{RUN_ID}-survivor-cat"
    create("NavCategory", other_cat, {"displayName": f"Survivor {TAG}", "sort": 0,
                                     "visible": True})
    create("NavLink", survivor, {"categoryName": other_cat, "displayName": "keep me",
                                 "url": "https://example.com/keep", "sort": 0,
                                 "visible": True})

    r = request("DELETE", f"{CONSOLE_API}/category/{cat}")
    R.equal("cascade delete responds 204", r.status_code, 204)
    R.check("category is gone",
            wait_until(lambda: get("NavCategory", cat).status_code == 404))
    for name in own:
        R.check(f"link {name[-1]} removed with the category",
                wait_until(lambda n=name: get("NavLink", n).status_code == 404))
    R.equal("other category untouched", get("NavCategory", other_cat).status_code, 200)
    R.equal("link in another category untouched", get("NavLink", survivor).status_code,
            200)

    again = request("DELETE", f"{CONSOLE_API}/category/{cat}")
    R.equal("deleting a missing category is 404", again.status_code, 404)
    R.equal("missing name is 404",
            request("DELETE", f"{CONSOLE_API}/category/{RUN_ID}-nope").status_code, 404)


def test_link_meta() -> None:
    print("\n[link-meta]")
    blocked = [
        ("loopback IPv4", "http://127.0.0.1:8096/nav"),
        ("loopback name", "http://localhost:8096/"),
        ("IPv6 loopback", "http://[::1]/"),
        ("link-local metadata", "http://169.254.169.254/latest/meta-data/"),
        ("private 10/8", "http://10.0.0.5/"),
        ("private 172.16/12", "http://172.16.0.1/"),
        ("private 192.168/16", "http://192.168.1.1/"),
        ("CGNAT", "http://100.64.0.1/"),
        ("TEST-NET", "http://203.0.113.1/"),
        ("file scheme", "file:///etc/passwd"),
        ("javascript scheme", "javascript:alert(1)"),
        ("site-relative", "/about"),
        ("garbage", "not a url"),
        ("empty", ""),
    ]
    for label, url in blocked:
        r = request("POST", f"{CONSOLE_API}/link-meta", json={"url": url})
        R.equal(f"link-meta rejects {label}", r.status_code, 400)

    bad_json = request("POST", f"{CONSOLE_API}/link-meta", data="{",
                       headers={"Content-Type": "application/json"})
    R.equal("link-meta rejects malformed JSON", bad_json.status_code, 400)

    oversized = request("POST", f"{CONSOLE_API}/link-meta",
                        data=json.dumps({"url": "https://example.com/" + "a" * 20000}),
                        headers={"Content-Type": "application/json"})
    R.equal("link-meta rejects oversized body", oversized.status_code, 413)

    r = request("POST", f"{CONSOLE_API}/link-meta",
                json={"url": "https://www.example.com/"})
    R.equal("link-meta accepts a public URL", r.status_code, 200)
    if r.status_code == 200:
        body = r.json()
        R.check("link-meta returns name", bool(body.get("name")), str(body))
        R.check("link-meta returns an icon or a fallback",
                "icon" in body and isinstance(body["icon"], str), str(body))
        R.check("link-meta reports fetched as a boolean",
                isinstance(body.get("fetched"), bool), str(body))
        R.equal("link-meta responds with no-store",
                r.headers.get("Cache-Control"), "no-store")

    # A resolvable host with a closed port: the page is unreadable, but the form
    # still gets the host-based fallback instead of an error.
    r = request("POST", f"{CONSOLE_API}/link-meta",
                json={"url": "https://www.baidu.com:81/"}, timeout=60)
    R.equal("unreachable page still answers 200", r.status_code, 200)
    if r.status_code == 200:
        body = r.json()
        R.equal("unreachable page reports fetched=false", body.get("fetched"), False)
        R.equal("unreachable page falls back to the host name", body.get("name"),
                "baidu.com")

    # A redirect into loopback must be dropped, not followed.
    redirect_url = ("https://httpbin.org/redirect-to?url="
                    "http%3A%2F%2F127.0.0.1%3A8096%2Fnav")
    r = request("POST", f"{CONSOLE_API}/link-meta", json={"url": redirect_url},
                timeout=60)
    R.equal("redirect to loopback answers 200", r.status_code, 200)
    if r.status_code == 200:
        body = r.json()
        R.equal("redirect to loopback is not read", body.get("fetched"), False)
        # The requested URL is echoed back by design; the derived name/icon must
        # not come from the blocked hop.
        R.equal("blocked redirect falls back to the original host",
                body.get("name"), "httpbin.org")
        R.check("blocked redirect never leaks the loopback address",
                "127.0.0.1" not in body.get("icon", "")
                and ":8096" not in body.get("icon", ""), body.get("icon"))

    # A nonexistent DNS name is a network failure, not an invalid target: the
    # documented behaviour is a 200 response with fetched=false and a host fallback.
    invalid_host = request("POST", f"{CONSOLE_API}/link-meta",
                           json={"url": f"https://missing-{TAG}.invalid/"}, timeout=60)
    R.equal("unresolvable host still answers 200", invalid_host.status_code, 200)
    if invalid_host.status_code == 200:
        body = invalid_host.json()
        R.equal("unresolvable host reports fetched=false", body.get("fetched"), False)
        R.check("unresolvable host falls back to the host name",
                f"missing-{TAG}.invalid" in body.get("name", ""), body.get("name"))

    # Icons of reachable sites are copied into Halo attachments.
    r = request("POST", f"{CONSOLE_API}/link-meta",
                json={"url": "https://www.baidu.com/"}, timeout=60)
    R.equal("link-meta succeeds for baidu", r.status_code, 200)
    if r.status_code == 200:
        icon = r.json().get("icon", "")
        R.check("fetched icon is stored as a Halo attachment",
                icon.startswith("/upload/"), icon)


def test_visit_stats() -> None:
    print("\n[visit stats]")
    body = stats()
    R.check("stats has an integer linkCount", isinstance(body.get("linkCount"), int),
            str(body.get("linkCount")))
    R.equal("stats returns seven days", len(body.get("daily") or []), 7)
    daily = body.get("daily") or []
    R.equal("last day is today", daily[-1]["date"] if daily else None, today())
    dates = [dt.date.fromisoformat(item["date"]) for item in daily]
    R.check("days are consecutive",
            all((dates[i + 1] - dates[i]).days == 1 for i in range(len(dates) - 1)),
            str(dates))
    R.check("totalPv covers the seven-day window",
            body["totalPv"] >= sum(item["pv"] for item in daily),
            f"total={body['totalPv']}")
    R.check("todayPv <= totalPv", body["todayPv"] <= body["totalPv"], str(body))
    live_links = list_models("NavLink", page=1, size=1)
    R.equal("linkCount matches live links", body["linkCount"], live_links["total"])

    ua = f"pn-stats-{TAG}/single"
    before = stats()
    visit(ua)
    R.check("one new visitor increments PV",
            wait_until(lambda: stats()["todayPv"] >= before["todayPv"] + 1),
            f"before={before}")
    after_first = stats()
    R.check("one new visitor increments UV",
            after_first["todayUv"] >= before["todayUv"] + 1,
            f"before={before} after={after_first}")

    visit(ua)
    R.check("repeat visit increments PV but not UV",
            wait_until(lambda: stats()["todayPv"] >= after_first["todayPv"] + 1),
            "PV did not move")
    after_repeat = stats()
    R.equal("repeat visit leaves UV unchanged", after_repeat["todayUv"],
            after_first["todayUv"])

    other_ua = f"pn-stats-{TAG}/other"
    visit(other_ua)
    R.check("a different User-Agent is a new visitor",
            wait_until(lambda: stats()["todayUv"] >= after_repeat["todayUv"] + 1),
            f"before={after_repeat}")

    # Concurrency: the visitor resource name is the dedup primitive.
    concurrent_ua = f"pn-stats-{TAG}/concurrent"
    baseline = stats()
    threads = [threading.Thread(target=visit, args=(concurrent_ua,)) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    R.check("12 concurrent visits all counted as PV",
            wait_until(lambda: stats()["todayPv"] >= baseline["todayPv"] + 12,
                       timeout=25),
            f"before={baseline}")
    R.check("12 concurrent visits counted as one UV",
            stats()["todayUv"] - baseline["todayUv"] == 1,
            f"before={baseline} after={stats()}")

    # Halo redirects unauthenticated API calls to /login instead of answering 401.
    unauth = requests.get(api(f"{CONSOLE_API}/stats"),
                          headers={"Accept": "application/json"}, timeout=20,
                          allow_redirects=False)
    R.check("stats redirects unauthenticated callers to login",
            unauth.status_code in (302, 401), str(unauth.status_code))

    # The dashboard counts every live link, including hidden ones.
    cat = f"{RUN_ID}-stats-cat"
    create("NavCategory", cat, {"displayName": f"StatsCat {TAG}", "sort": 0,
                                "visible": True})
    before_hidden = stats()["linkCount"]
    create("NavLink", f"{RUN_ID}-stats-hidden", {
        "categoryName": cat, "displayName": "hidden", "url": "https://example.com/h",
        "sort": 0, "visible": False})
    R.equal("linkCount includes hidden links", stats()["linkCount"], before_hidden + 1)

    # Visitors are stored only as salted hashes; raw IPs and UA strings never appear.
    visitors = list_all("NavVisitVisitor")
    R.check("visitor records exist", len(visitors) >= 3, str(len(visitors)))
    raw = json.dumps(visitors)
    R.check("raw IP never stored", "127.0.0.1" not in raw)
    R.check("raw User-Agent never stored", f"pn-stats-{TAG}" not in raw)
    # Only today's labelled records belong to this run; a shared dev instance can
    # contain unlabelled leftovers from earlier scripts that have no hash.
    today_key = today()
    hashes = [
        (item.get("spec") or {}).get("hash", "")
        for item in visitors
        if (item["metadata"].get("labels") or {}).get(
            "palette-nav.halo.run/visit-date") == today_key
    ]
    R.check("visitor hashes are 16 lowercase hex chars",
            hashes and all(len(h) == 16 and all(c in "0123456789abcdef" for c in h)
                           for h in hashes), str(hashes[:3]))
    R.check("visitor names embed the hash",
            all(item["metadata"]["name"].startswith("nav-visitors-")
                for item in visitors))
    daily = {(item.get("spec") or {}).get("date"): item
             for item in list_all("NavVisitStat")}
    today_stat = (daily.get(today()) or {}).get("spec") or {}
    salt = today_stat.get("salt", "")
    R.check("daily salt is 32 hex chars",
            len(salt) == 32 and all(c in "0123456789abcdef" for c in salt), salt)
    R.check("daily counters are numeric",
            isinstance(today_stat.get("pv"), int)
            and isinstance(today_stat.get("uv"), int), str(today_stat))


def test_nav_render_security() -> None:
    print("\n[nav render and safety]")
    cat = f"{RUN_ID}-nav-cat"
    create("NavCategory", cat, {
        "displayName": f"NAVCAT {TAG}",
        "icon": "<svg class=\"icon\"><use xlink:href=\"#pn-code\"></use></svg>",
        "sort": 10, "visible": True,
    })
    create("NavCategory", f"{RUN_ID}-hidden-cat", {
        "displayName": f"HIDDENCAT {TAG}", "sort": 20, "visible": False})
    create("NavCategory", f"{RUN_ID}-empty-cat", {
        "displayName": f"EMPTYCAT {TAG}", "sort": 30, "visible": True})
    create("NavCategory", f"{RUN_ID}-unsafe-cat", {
        "displayName": f"UNSAFECAT {TAG}",
        "icon": "<svg><script>alert(9)</script></svg>", "sort": 40, "visible": True})
    create("NavLink", f"{RUN_ID}-unsafe-cat-link", {
        "categoryName": f"{RUN_ID}-unsafe-cat", "displayName": f"UNSAFECATLINK {TAG}",
        "url": "https://unsafecat.example.com", "sort": 10, "visible": True})

    links = {
        "safe": f"{RUN_ID}-nav-safe",
        "hidden": f"{RUN_ID}-nav-hidden",
        "js": f"{RUN_ID}-nav-js",
        "backslash": f"{RUN_ID}-nav-backslash",
        "sprite": f"{RUN_ID}-nav-sprite",
        "inline": f"{RUN_ID}-nav-inline",
        "script": f"{RUN_ID}-nav-script",
        "text": f"{RUN_ID}-nav-text",
    }
    create("NavLink", links["safe"], {
        "categoryName": cat, "displayName": f"SAFELINK {TAG}",
        "url": "https://safe.example.com/page?a=1&b=2",
        "icon": "https://cdn.example.com/icon.png?a=1&b=2", "sort": 10,
        "visible": True})
    create("NavLink", links["hidden"], {
        "categoryName": cat, "displayName": f"HIDDENLINK {TAG}",
        "url": "https://hidden.example.com", "sort": 20, "visible": False})
    create("NavLink", links["js"], {
        "categoryName": cat, "displayName": f"JSLINK {TAG}",
        "url": "javascript:alert(1)", "sort": 30, "visible": True})
    create("NavLink", links["backslash"], {
        "categoryName": cat, "displayName": f"BACKSLASHLINK {TAG}",
        "url": "/\\evil.example.com", "sort": 40, "visible": True})
    create("NavLink", links["sprite"], {
        "categoryName": cat, "displayName": f"SPRITELINK {TAG}",
        "url": "https://sprite.example.com",
        "icon": "<svg class=\"icon\"><use xlink:href=\"#pn-code\"></use></svg>",
        "sort": 50, "visible": True})
    create("NavLink", links["inline"], {
        "categoryName": cat, "displayName": f"INLINELINK {TAG}",
        "url": "https://inline.example.com",
        "icon": "<svg viewBox=\"0 0 10 10\"><circle r=\"5\"/></svg>", "sort": 60,
        "visible": True})
    create("NavLink", links["script"], {
        "categoryName": cat, "displayName": f"SCRIPTLINK {TAG}",
        "url": "https://script.example.com",
        "icon": "<svg><script>alert(2)</script></svg>", "sort": 70, "visible": True})
    create("NavLink", links["text"], {
        "categoryName": cat, "displayName": f"TEXTLINK {TAG}",
        "url": "https://text.example.com", "icon": "🚀", "sort": 80, "visible": True})
    create("NavLink", f"{RUN_ID}-nav-xssname", {
        "categoryName": cat, "displayName": "<img src=x onerror=alert(3)>",
        "url": "https://xss.example.com", "sort": 90, "visible": True})
    create("NavLink", f"{RUN_ID}-nav-orphan", {
        "categoryName": f"{RUN_ID}-missing-cat", "displayName": f"ORPHAN {TAG}",
        "url": "https://orphan.example.com", "sort": 100, "visible": True})
    create("NavLink", f"{RUN_ID}-nav-hiddencat-link", {
        "categoryName": f"{RUN_ID}-hidden-cat", "displayName": f"HIDDENCATLINK {TAG}",
        "url": "https://hiddencat.example.com", "sort": 110, "visible": True})

    create("NavSearchEngine", f"{RUN_ID}-engine-visible", {
        "alias": f"desk{TAG}", "displayName": f"ENGINE {TAG}",
        "urlTemplate": "https://desk.example.com/s?q=",
        "mobileUrlTemplate": "https://mob.example.com/s?q=",
        "sort": 10, "visible": True})
    create("NavSearchEngine", f"{RUN_ID}-engine-hidden", {
        "alias": f"hid{TAG}", "displayName": f"HIDDENENGINE {TAG}",
        "urlTemplate": "https://hidden-engine.example.com/?q=", "sort": 20,
        "visible": False})
    create("NavSearchEngine", f"{RUN_ID}-engine-js", {
        "alias": f"js{TAG}", "displayName": f"JSENGINE {TAG}",
        "urlTemplate": "javascript:alert(4)", "sort": 30, "visible": True})

    create("NavQuickLink", f"{RUN_ID}-quick-visible", {
        "displayName": f"QUICK {TAG}", "url": "/about", "openInNewTab": True,
        "sort": 10, "visible": True})
    create("NavQuickLink", f"{RUN_ID}-quick-same-tab", {
        "displayName": f"QUICKSAME {TAG}", "url": "/same-tab", "openInNewTab": False,
        "sort": 20, "visible": True})
    create("NavQuickLink", f"{RUN_ID}-quick-js", {
        "displayName": f"QUICKJS {TAG}", "url": "javascript:alert(5)",
        "openInNewTab": True, "sort": 30, "visible": True})
    create("NavQuickLink", f"{RUN_ID}-quick-hidden", {
        "displayName": f"QUICKHIDDEN {TAG}", "url": "/hidden", "openInNewTab": True,
        "sort": 40, "visible": False})

    r = visit(f"pn-nav-{TAG}")
    html = r.text
    R.equal("GET /nav is 200", r.status_code, 200)
    R.check("GET /nav is HTML",
            (r.headers.get("Content-Type") or "").startswith("text/html"),
            r.headers.get("Content-Type"))
    R.equal("GET /nav is not cached", r.headers.get("Cache-Control"), "no-store")
    R.equal("GET /nav sets nosniff", r.headers.get("X-Content-Type-Options"),
            "nosniff")

    R.check("visible category rendered", f"NAVCAT {TAG}" in html)
    R.check("visible link rendered", f"SAFELINK {TAG}" in html)
    R.check("link URL rendered", "safe.example.com/page" in html)
    R.check("hidden category hidden",
            f"HIDDENCAT {TAG}" not in html and f"HIDDENCATLINK {TAG}" not in html)
    R.check("empty category hidden", f"EMPTYCAT {TAG}" not in html)
    R.check("category with an unsafe icon still renders its links",
            f"UNSAFECAT {TAG}" in html and f"UNSAFECATLINK {TAG}" in html)
    R.check("unsafe category icon is dropped", "alert(9)" not in html)
    R.check("hidden link hidden", f"HIDDENLINK {TAG}" not in html)
    R.check("javascript link hidden", f"JSLINK {TAG}" not in html)
    R.check("backslash link hidden", "BACKSLASHLINK" not in html and
            "evil.example.com" not in html)
    R.check("orphan link hidden", f"ORPHAN {TAG}" not in html)
    R.check("hidden engine hidden", f"HIDDENENGINE {TAG}" not in html)
    R.check("unsafe engine hidden", f"JSENGINE {TAG}" not in html)
    R.check("hidden quick link hidden", f"QUICKHIDDEN {TAG}" not in html)
    R.check("javascript quick link hidden", f"QUICKJS {TAG}" not in html)
    R.check("safe quick link rendered", f"QUICK {TAG}" in html and "/about" in html)
    R.check("quick link without new tab rendered", f"QUICKSAME {TAG}" in html)

    # Icons go through the sanitizer before th:utext.
    R.check("sprite icon rendered as a reference", 'href="#pn-code"' in html)
    R.check("inline SVG rendered as a data URL", "data:image/svg+xml;base64," in html)
    R.check("script-bearing SVG falls back to the default icon",
            "default-icon.png" in html)
    R.check("emoji rendered as text", "icon-text" in html and "🚀" in html)
    R.check("icon URL ampersand escaped", "a=1&amp;b=2" in html)
    R.check("no icon script made it into the page", "<script>alert(2)" not in html)
    R.check("displayName is escaped", "&lt;img src=x onerror=alert(3)&gt;" in html)
    R.check("no raw XSS markup in the page", "onerror=alert(3)>" not in html)

    # Search templates: desktop vs mobile.
    desktop = nav_html(f"Mozilla/5.0 (Windows NT 10.0; Win64; x64) pn-{TAG}")
    mobile = nav_html(f"Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) pn-{TAG}")
    R.check("desktop uses urlTemplate", "desk.example.com/s?q=" in desktop)
    R.check("mobile uses mobileUrlTemplate", "mob.example.com/s?q=" in mobile)
    R.check("mobile does not use urlTemplate", "desk.example.com/s?q=" not in mobile)

    R.equal("GET /nav/ is 200", visit(f"pn-nav-{TAG}", path="/nav/").status_code, 200)


def test_settings_apply_to_nav() -> None:
    print("\n[settings and nav defaults]")
    global settings_backup
    # A fresh installation may not have the ConfigMap yet; treat 404 as empty.
    r = request("GET", CONFIG_API)
    R.check("read plugin config", r.status_code in (200, 404), str(r.status_code))
    settings_backup = read_config() if r.status_code == 200 else {}

    config = json.loads(json.dumps(settings_backup))
    general = config.setdefault("general", {})
    general.update({
        "siteName": f"SITE {TAG}",
        "brandText": f"BRAND {TAG}",
        "siteDescription": f"DESC {TAG}",
        "siteKeywords": f"KW1, KW2 {TAG}",
        "logoUrl": "javascript:alert(6)",
        "gonganbei": "京公网安备11010502030146号",
    })
    appearance = config.setdefault("appearance", {})
    appearance.update({
        "defaultColor": "NOT-A-COLOR",
        "defaultNightMode": "DARK",
        "backgroundUrl": "/upload/background.mp4",
        "backgroundType": "video",
        "showClock": False,
        "showYiyan": False,
    })
    saved = write_config(config)
    R.check("save plugin config", saved.status_code in (200, 204),
            str(saved.status_code))
    # Give the settings/config readers a moment to observe the new resource.
    time.sleep(0.6)

    html = nav_html(f"pn-settings-{TAG}")
    R.check("brand text applied", f"BRAND {TAG}" in html)
    R.check("site name applied", f"SITE {TAG}" in html)
    R.check("SEO description applied", f"DESC {TAG}" in html)
    R.check("SEO keywords applied", f"KW1, KW2 {TAG}" in html)
    R.check("invalid color normalized to purple", 'data-theme="purple"' in html)
    R.check("night mode lowercased", 'data-night-mode="dark"' in html)
    R.check("unsafe logo falls back to the default asset", "logo-default.png" in html)
    R.check("gonganbei digits extracted", "11010502030146" in html)
    R.check("video background wired", "/upload/background.mp4" in html and
            "data-background-video-url" in html)
    R.check("clock disabled", 'id="clockTime"' not in html)
    R.check("yiyan disabled", 'class="yiyan-text"' not in html)

    # Invalid background type falls back to an image and the default asset.
    config["appearance"]["backgroundType"] = "nonsense"
    config["appearance"]["backgroundUrl"] = "javascript:alert(7)"
    write_config(config)
    time.sleep(0.6)
    html = nav_html(f"pn-settings-{TAG}")
    R.check("unsafe background falls back", "background.jpg" in html)
    R.check("unsafe background never rendered",
            "javascript:alert(7)" not in html)

    # Disabling visit stats stops new counts without hiding old data.
    stats_config = json.loads(json.dumps(settings_backup))
    stats_config.setdefault("general", {})["enableVisitStats"] = False
    write_config(stats_config)
    # Visits rendered before the toggle are still in the 5s in-memory buffer, so
    # wait until two consecutive reads are stable before taking the baseline.
    deadline = time.monotonic() + 30
    stable = None
    while time.monotonic() < deadline:
        current = stats()["todayPv"]
        time.sleep(6)
        if stats()["todayPv"] == current:
            stable = current
            break
    R.check("stats settle after disabling", stable is not None, "still moving")
    before = stats()
    disabled_ua = f"pn-stats-disabled-{TAG}"
    visit(disabled_ua)
    time.sleep(7)
    R.equal("disabled stats do not increment PV", stats()["todayPv"], before["todayPv"])
    stats_config["general"]["enableVisitStats"] = True
    write_config(stats_config)
    time.sleep(0.6)
    visit(disabled_ua)
    R.check("re-enabled stats count again",
            wait_until(lambda: stats()["todayPv"] >= before["todayPv"] + 1),
            f"before={before}")

    # An empty stored config must fall back to the built-in defaults.
    write_config({})
    time.sleep(0.6)
    html = nav_html(f"pn-reset-{TAG}")
    R.check("empty config falls back to defaults",
            "background.jpg" in html and 'id="clockTime"' in html,
            html[:200])
    R.check("empty config drops the previous brand", f"BRAND {TAG}" not in html)

    # A configured policy that no longer exists falls back to the automatic one
    # instead of failing the icon storage.
    write_config({
        "icons": {"policyName": "missing-policy", "groupName": ""}})
    time.sleep(0.6)
    r = request("POST", f"{CONSOLE_API}/link-meta",
                json={"url": "https://www.bing.com/"}, timeout=60)
    R.equal("icon storage falls back when the policy is missing",
            r.status_code, 200)
    if r.status_code == 200:
        R.check("fallback still stores the icon",
                r.json().get("icon", "").startswith("/upload/"),
                r.json().get("icon"))

    restored = settings_backup or {}
    restore_settings()
    settings_backup = None
    time.sleep(0.6)
    html = nav_html(f"pn-restore-{TAG}")
    # Compare against snapshot instead of a hard-coded default background: on a
    # dev instance the original config usually has its own media and site name.
    conditions = [f"BRAND {TAG}" not in html]
    original_site = (restored.get("general") or {}).get("siteName")
    if original_site:
        conditions.append(original_site in html)
    original_background = (restored.get("appearance") or {}).get("backgroundUrl")
    conditions.append(original_background in html if original_background
                      else "background.jpg" in html)
    R.check("settings restored", all(conditions))


def test_authentication() -> None:
    """The public page stays public; every management API requires auth."""
    print("\n[authentication]")
    R.equal("/nav is public", requests.get(api("/nav"), timeout=20).status_code, 200)

    unauth = requests.get(api(model_path("NavCategory")),
                          headers={"Accept": "application/json"}, timeout=20,
                          allow_redirects=False)
    R.check("model API requires authentication",
            unauth.status_code in (302, 401), str(unauth.status_code))

    wrong = requests.get(api(f"{CONSOLE_API}/stats"), auth=(USER, "wrong-password"),
                         headers={"Accept": "application/json"}, timeout=20)
    R.equal("wrong password is rejected", wrong.status_code, 401)

    for label, path, kwargs in [
        ("stats", f"{CONSOLE_API}/stats", {}),
        ("reorder", f"{CONSOLE_API}/reorder", {"json": {"kind": "NavCategory",
                                                        "items": []}}),
        ("link-meta", f"{CONSOLE_API}/link-meta",
         {"json": {"url": "https://www.example.com/"}}),
    ]:
        r = requests.request("GET" if label == "stats" else "POST", api(path),
                             headers={"Accept": "application/json"}, timeout=20,
                             allow_redirects=False, **kwargs)
        R.check(f"unauthenticated {label} is rejected",
                r.status_code in (302, 401), str(r.status_code))


def test_plugin_lifecycle() -> None:
    print("\n[plugin lifecycle]")
    marker = f"{RUN_ID}-lifecycle-cat"
    create("NavCategory", marker, {"displayName": f"LIFECYCLE {TAG}", "sort": 0,
                                   "visible": True})

    # Expired visitor records are deleted on the first visit after a restart
    # (the cleanup guard is once per day per service instance).
    old_date = (dt.date.today() - dt.timedelta(days=3)).isoformat()
    stale_visitor = f"nav-visitors-{old_date}-deadbeefdeadbeef"
    create("NavVisitVisitor", stale_visitor,
           {"date": old_date, "hash": "deadbeefdeadbeef"},
           labels={"palette-nav.halo.run/visit-date": old_date})
    R.check("seeded an expired visitor record",
            get("NavVisitVisitor", stale_visitor).status_code == 200)

    r = set_plugin_enabled(False)
    R.equal("disable plugin", r.status_code, 200)
    stopped = wait_until(lambda: get_phase() in ("STOPPED", "DISABLED"), timeout=60)
    R.check("plugin reaches STOPPED/DISABLED", stopped, f"phase={get_phase()}")
    R.check("model route is gone while stopped",
            get("NavCategory", marker).status_code in (404, 403, 503),
            str(get("NavCategory", marker).status_code))
    R.equal("/nav is gone while stopped", visit(f"pn-life-{TAG}").status_code, 404)

    r = set_plugin_enabled(True)
    R.equal("enable plugin", r.status_code, 200)
    R.check("plugin returns to STARTED", wait_until(lambda: get_phase() == "STARTED",
                                                    timeout=90),
            f"phase={get_phase()}")
    R.equal("/nav works after restart", visit(f"pn-life-{TAG}").status_code, 200)
    R.check("model data survived the restart",
            get("NavCategory", marker).status_code == 200,
            str(get("NavCategory", marker).status_code))
    R.equal("stats still works after restart",
            request("GET", f"{CONSOLE_API}/stats").status_code, 200)
    R.check("expired visitor records are cleaned up after a restart",
            wait_until(lambda: get("NavVisitVisitor", stale_visitor).status_code
                       == 404, timeout=20),
            str(get("NavVisitVisitor", stale_visitor).status_code))
    delete("NavCategory", marker)


def get_phase() -> str | None:
    r = request("GET", "/apis/plugin.halo.run/v1alpha1/plugins/palette-nav")
    if r.status_code != 200:
        return None
    return (r.json().get("status") or {}).get("phase")


# --------------------------------------------------------------------------- #

def main() -> int:
    print(f"base={BASE} run={RUN_ID}")
    try:
        ensure_admin()
        test_model_crud()
        test_pagination_and_validation()
        test_reorder()
        test_cascade_delete()
        test_link_meta()
        test_visit_stats()
        test_nav_render_security()
        test_settings_apply_to_nav()
        test_authentication()
        test_plugin_lifecycle()
    finally:
        cleanup()
        restore_settings()

    print(f"\n{R.passed} passed, {R.failed} failed")
    if R.failures:
        print("Failures:")
        for failure in R.failures:
            print(f"  - {failure}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())