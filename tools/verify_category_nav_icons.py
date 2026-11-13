"""Verify the 「导航栏显示分类图标」 setting end to end against the local dev instance."""

import json
from pathlib import Path

from playwright.sync_api import sync_playwright

from nav_dev import HaloClient

client = HaloClient()
BASE = client.base_url
OUT = Path(".tools/halo/screenshots")
OUT.mkdir(parents=True, exist_ok=True)


def set_show_icons(cm, visible):
    appearance = json.loads(cm["data"]["appearance"])
    appearance["showCategoryIconsInNav"] = visible
    cm["data"]["appearance"] = json.dumps(appearance, ensure_ascii=False)
    client.put_configmap(cm)


def nav_state(page):
    return page.evaluate(
        """() => {
          const items = [...document.querySelectorAll('.category-item')];
          return items.map((el) => ({
            cat: el.getAttribute('data-cat'),
            label: el.querySelector('.category-item-label')?.textContent ?? null,
            hasIcon: !!el.querySelector('.category-item-icon'),
            iconSvg: !!el.querySelector('.category-item-icon svg'),
            iconImg: !!el.querySelector('.category-item-icon img'),
          }));
        }"""
    )


def categories_with_icon():
    """Names of categories that actually have an icon configured."""
    payload = json.loads(
        client.call("GET", "/apis/palette-nav.halo.run/v1alpha1/navcategories?size=1000")
    )
    return {
        (item.get("metadata") or {}).get("name")
        for item in payload.get("items") or []
        if ((item.get("spec") or {}).get("icon") or "").strip()
    }


def main():
    original = client.get_configmap()
    original_appearance = json.loads(original["data"]["appearance"])

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)

            # 先显式关闭开关再断言关闭态，避免上一次手工验证的残留配置让脚本必然失败。
            set_show_icons(client.get_configmap(), False)
            page = browser.new_page(viewport={"width": 1440, "height": 900})
            page.goto(f"{BASE}/nav", wait_until="networkidle")
            page.wait_for_timeout(600)
            off = nav_state(page)
            print("default off:", off)
            assert off and not any(item["hasIcon"] for item in off)
            # 「全部」按钮仍是纯文本节点，没有 label 包裹。
            assert all(item["label"] for item in off[1:])
            page.screenshot(path=str(OUT / "category-nav-default.png"))

            set_show_icons(client.get_configmap(), True)
            page.reload(wait_until="networkidle")
            page.wait_for_timeout(600)
            on = nav_state(page)
            print("switch on:", on)
            assert any(item["hasIcon"] for item in on)
            # 仅「全部」没有图标；配置了图标的分类显示图标，没配置的分类不占位。
            assert not on[0]["hasIcon"]
            icon_cats = categories_with_icon()
            for item in on[1:]:
                if item["cat"] in icon_cats:
                    assert item["hasIcon"] and (item["iconSvg"] or item["iconImg"]), item
                else:
                    assert not item["hasIcon"], f"无图标分类不应占位: {item}"
            page.screenshot(path=str(OUT / "category-nav-icons.png"))

            browser.close()
    finally:
        restored = client.get_configmap()
        restored["data"] = original["data"]
        client.put_configmap(restored)

    now = json.loads(client.get_configmap()["data"]["appearance"])
    assert now == original_appearance
    print("all checks passed; settings restored")


if __name__ == "__main__":
    main()