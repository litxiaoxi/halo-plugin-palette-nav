#!/usr/bin/env python3
"""Export a running Palette Nav instance as the plugin's bundled default data.

The generated document only contains user-editable content (categories, links,
search engines and quick links). Visit statistics are deliberately excluded, and
settings are kept in code (`NavSettings.java` / `ui/src/api/settings.ts`) so the
plugin does not need a third copy of the settings schema.

Usage:
    python tools/export-nav-defaults.py
    python tools/export-nav-defaults.py --base-url http://localhost:8095 \
        --username admin --password admin123

The defaults are imported by `NavDefaultDataService` only once: when the plugin
starts on an instance that has no navigation data and no initialization marker.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

from nav_dev import basic_auth_header

API_PREFIX = "/apis/palette-nav.halo.run/v1alpha1"
RESOURCES = {
    "categories": "navcategories",
    "links": "navlinks",
    "engines": "navsearchengines",
    "quickLinks": "navquicklinks",
}


def fetch_all(base_url: str, resource: str, username: str, password: str) -> list[dict]:
    url = f"{base_url.rstrip('/')}{API_PREFIX}/{resource}?size=1000"
    request = urllib.request.Request(url)
    request.add_header("Authorization", basic_auth_header(username, password))
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = json.load(response)
    except urllib.error.HTTPError as error:
        raise SystemExit(
            f"Failed to fetch {url}: HTTP {error.code}. "
            "Check the base URL and credentials."
        ) from error
    except urllib.error.URLError as error:
        raise SystemExit(f"Failed to reach {url}: {error.reason}") from error
    return payload.get("items") or []


def name_of(item: dict) -> str:
    return (item.get("metadata") or {}).get("name") or ""


def spec_of(item: dict) -> dict:
    return item.get("spec") or {}


def sort_key(item: dict) -> tuple[int, str]:
    sort = spec_of(item).get("sort")
    return (0 if sort is None else int(sort), name_of(item))


def export_data(base_url: str, username: str, password: str) -> dict:
    categories = fetch_all(base_url, RESOURCES["categories"], username, password)
    links = fetch_all(base_url, RESOURCES["links"], username, password)
    engines = fetch_all(base_url, RESOURCES["engines"], username, password)
    quick_links = fetch_all(base_url, RESOURCES["quickLinks"], username, password)

    category_names = {name_of(item) for item in categories}
    links_by_category: dict[str, list[dict]] = {}
    orphans = 0
    for item in sorted(links, key=sort_key):
        spec = spec_of(item)
        category_name = spec.get("categoryName")
        if category_name not in category_names:
            orphans += 1
            continue
        links_by_category.setdefault(category_name, []).append({
            "name": name_of(item),
            "displayName": spec.get("displayName"),
            "url": spec.get("url"),
            "icon": spec.get("icon"),
            "sort": spec.get("sort"),
            "visible": spec.get("visible"),
        })
    if orphans:
        print(f"Skipped {orphans} orphan link(s) whose category does not exist.",
              file=sys.stderr)

    exported_categories = []
    for category in sorted(categories, key=sort_key):
        spec = spec_of(category)
        exported_categories.append({
            "name": name_of(category),
            "displayName": spec.get("displayName"),
            "icon": spec.get("icon"),
            "sort": spec.get("sort"),
            "visible": spec.get("visible"),
            "links": links_by_category.get(name_of(category), []),
        })

    exported_engines = []
    for engine in sorted(engines, key=sort_key):
        spec = spec_of(engine)
        exported_engines.append({
            "name": name_of(engine),
            "alias": spec.get("alias"),
            "displayName": spec.get("displayName"),
            "hint": spec.get("hint"),
            "urlTemplate": spec.get("urlTemplate"),
            "mobileUrlTemplate": spec.get("mobileUrlTemplate"),
            "icon": spec.get("icon"),
            "sort": spec.get("sort"),
            "visible": spec.get("visible"),
        })

    exported_quick_links = []
    for link in sorted(quick_links, key=sort_key):
        spec = spec_of(link)
        exported_quick_links.append({
            "name": name_of(link),
            "displayName": spec.get("displayName"),
            "url": spec.get("url"),
            "openInNewTab": spec.get("openInNewTab"),
            "sort": spec.get("sort"),
            "visible": spec.get("visible"),
        })

    return {
        "version": 1,
        "categories": exported_categories,
        "searchEngines": exported_engines,
        "quickLinks": exported_quick_links,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.environ.get("HALO_URL", "http://localhost:8095"))
    parser.add_argument("--username", default=os.environ.get("HALO_USERNAME", "admin"))
    parser.add_argument("--password", default=os.environ.get("HALO_PASSWORD", "admin123"))
    parser.add_argument(
        "--output",
        default=os.path.join(
            os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
            "src", "main", "resources", "data", "nav-defaults.json",
        ),
    )
    args = parser.parse_args()

    data = export_data(args.base_url, args.username, args.password)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8", newline="\n") as output:
        json.dump(data, output, ensure_ascii=False, indent=2)
        output.write("\n")

    link_count = sum(len(item["links"]) for item in data["categories"])
    print(
        f"Wrote {args.output}: {len(data['categories'])} categories, "
        f"{link_count} links, {len(data['searchEngines'])} search engines, "
        f"{len(data['quickLinks'])} quick links"
    )


if __name__ == "__main__":
    main()