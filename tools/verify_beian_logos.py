from pathlib import Path

from playwright.sync_api import sync_playwright

BASE = "http://localhost:8095"
OUT = Path(".tools/halo/screenshots")
OUT.mkdir(parents=True, exist_ok=True)

errors = []


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        page = browser.new_page(viewport={"width": 1440, "height": 1000})
        page.on("console", lambda m: errors.append(f"[{m.type}] {m.text}")
                if m.type == "error" else None)
        page.on("pageerror", lambda e: errors.append(f"[pageerror] {e}"))

        page.goto(f"{BASE}/login", wait_until="networkidle")
        page.fill("#username", "admin")
        page.fill("#plainPassword", "admin123")
        page.click("button[type=submit]")
        page.wait_for_load_state("networkidle")

        page.goto(f"{BASE}/console/palette-nav", wait_until="networkidle")
        page.wait_for_timeout(2500)
        page.locator(".pn-page").get_by_text("设置", exact=True).click()
        page.wait_for_timeout(2000)

        body = page.locator("body").inner_text()
        for keyword in ["ICP 备案图标", "公安备案图标", "ICP 备案号", "公安备案号"]:
            print(f"settings contains {keyword}: {keyword in body}")

        media = page.locator(".pn-media-field--logo")
        print("logo MediaPicker count:", media.count())
        page.screenshot(path=str(OUT / "settings-beian-logos.png"), full_page=True)

        browser.close()
        print("console errors:", errors or "none")


if __name__ == "__main__":
    main()