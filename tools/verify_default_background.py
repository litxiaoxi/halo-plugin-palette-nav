"""Verify the 「背景默认显示」 setting end to end against the local dev instance."""

import json

from playwright.sync_api import sync_playwright

from nav_dev import HaloClient

client = HaloClient()
BASE = client.base_url


def set_default_visible(cm, visible):
    appearance = json.loads(cm["data"]["appearance"])
    appearance["showBackgroundByDefault"] = visible
    cm["data"]["appearance"] = json.dumps(appearance, ensure_ascii=False)
    client.put_configmap(cm)


def state(page):
    return page.evaluate(
        """() => ({
          attr: document.documentElement.getAttribute('data-background'),
          layerClass: document.querySelector('.bg-layer').className,
          layerImage: document.querySelector('.bg-layer').style.backgroundImage,
          stored: localStorage.getItem('palette_bgSetting'),
        })"""
    )


def visit(browser, preset=None):
    context = browser.new_context(viewport={"width": 1280, "height": 900})
    page = context.new_page()
    page.goto(f"{BASE}/nav", wait_until="networkidle")
    page.wait_for_timeout(800)
    if preset is not None:
        page.evaluate("v => localStorage.setItem('palette_bgSetting', v)", preset)
        page.reload(wait_until="networkidle")
        page.wait_for_timeout(800)
    return context, page


def main():
    original = client.get_configmap()
    original_appearance = json.loads(original["data"]["appearance"])

    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=True)

            set_default_visible(client.get_configmap(), True)
            ctx, page = visit(browser)
            first = state(page)
            print("default=true, first visit:", first)
            assert first["attr"] == "image", "first visit should show the image background"
            assert "image-bg" in first["layerClass"]
            # 站点默认值不是访客偏好：首访显示图片但不应写入 localStorage。
            assert first["stored"] is None

            # The toggle keeps working: the first click hides the background,
            # even though the default background never wrote a stored value.
            page.click("#bgBtn")
            page.wait_for_timeout(300)
            after = state(page)
            print("after first toggle click:", after)
            assert after["attr"] is None and "image-bg" not in after["layerClass"]
            ctx.close()

            # A visitor who previously chose "none" keeps that choice.
            ctx, page = visit(browser, preset="none")
            saved = state(page)
            print("default=true, stored=none:", saved)
            assert saved["attr"] is None
            ctx.close()

            # Turning the setting off makes a first visit stay on the gradient only,
            # regardless of what the dev instance had configured before.
            set_default_visible(client.get_configmap(), False)

            ctx, page = visit(browser)
            off = state(page)
            print("default=false, first visit:", off)
            assert off["attr"] is None and "image-bg" not in off["layerClass"]
            ctx.close()

            browser.close()
    finally:
        # Any failed assertion above must not leave the dev instance mutated.
        restored = client.get_configmap()
        restored["data"] = original["data"]
        client.put_configmap(restored)

    now = client.get_configmap()
    assert json.loads(now["data"]["appearance"]) == original_appearance
    print("all checks passed; settings restored")


if __name__ == "__main__":
    main()