"""Capture production Compose and Glance layouts using debug-only sample data."""

import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

SDK = Path(os.environ["ANDROID_HOME"])
ADB = str(SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
OUTPUT = Path("app/build/design-preview")
OUTPUT.mkdir(parents=True, exist_ok=True)
PACKAGE = "space.megaworld.claudeusage"


def adb(*args):
    return subprocess.check_output([ADB, *args], timeout=45)


def capture(name):
    time.sleep(1.5)
    png = adb("exec-out", "screencap", "-p")
    if not png.startswith(b"\x89PNG\r\n\x1a\n"):
        raise RuntimeError("Invalid Android screenshot")
    (OUTPUT / f"{name}.png").write_bytes(png)
    adb("shell", "uiautomator", "dump", "/sdcard/tinyglyph-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/tinyglyph-ui.xml")
    (OUTPUT / f"{name}.xml").write_bytes(xml)
    log = adb("logcat", "-d", "-s", "AndroidRuntime:E").decode(errors="replace")
    if "FATAL EXCEPTION" in log and PACKAGE in log:
        (OUTPUT / "crash.txt").write_text(log, encoding="utf-8")
        raise RuntimeError("The app crashed while rendering a preview")
    print("Captured", name, flush=True)
    return xml


def open_page(screen, provider="CLAUDE", scenario="data", width=280, height=170):
    adb("shell", "am", "start", "-W", "-S", "-n", f"{PACKAGE}/.ui.DesignPreviewActivity",
        "--es", "screen", screen, "--es", "provider", provider, "--es", "scenario", scenario,
        "--ei", "width", str(width), "--ei", "height", str(height))


def ui_nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/tinyglyph-ui.xml")
    return list(ET.fromstring(adb("exec-out", "cat", "/sdcard/tinyglyph-ui.xml")).iter("node"))


def tap_node(predicate, index=0):
    for attempt in range(5):
        nodes = [node for node in ui_nodes() if predicate(node)]
        if len(nodes) > index:
            break
        sizes = re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size").decode())
        width, height = map(int, sizes[-1])
        # Start above the bottom navigation bar, including its larger-text height.
        adb("shell", "input", "swipe", str(width // 2), str(height - 420), str(width // 2), "450", "450")
    else:
        raise RuntimeError("Widget configuration control is missing after scrolling")
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", nodes[index].attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
    time.sleep(1)


def assert_sources(first, second):
    expected = f"widget-providers:{first},{second};app:CLAUDE"
    for attempt in range(10):
        if any(n.get("content-desc") == expected for n in ui_nodes()):
            return
        time.sleep(1)
    raise RuntimeError(f"Per-widget selection changed another widget or the app: expected {expected}")


def capture_widget_configuration(prefix):
    adb("shell", "appwidget", "grantbind", "--package", PACKAGE, "--user", "0")
    host = f"{PACKAGE}/.ui.WidgetHostPreviewActivity"
    adb("shell", "am", "start", "-W", "-S", "-n", host, "--ez", "reset", "true")
    assert_sources("CLAUDE", "CODEX")
    time.sleep(3)
    capture(f"{prefix}-widgets-independent-initial")
    gear = lambda n: n.get("content-desc") == "Настроить этот виджет"
    text = lambda value: lambda n: n.get("text", "").split("\n")[0] == value
    # Open each widget's own PendingIntent; their destinations must stay distinct.
    tap_node(gear, 0)
    capture(f"{prefix}-widget-config-claude")
    tap_node(text("GPT"))
    tap_node(text("Сохранить"))
    assert_sources("CODEX", "CODEX")
    capture(f"{prefix}-widgets-first-changed")
    tap_node(gear, 1)
    capture(f"{prefix}-widget-config-gpt")
    tap_node(text("Claude"))
    tap_node(text("Сохранить"))
    assert_sources("CODEX", "CLAUDE")
    capture(f"{prefix}-widgets-second-changed")
    tap_node(gear, 0)
    tap_node(text("Claude"))
    adb("shell", "input", "keyevent", "4")
    assert_sources("CODEX", "CLAUDE")
    # A draft choice must not survive cancellation, and saved choices survive a restart.
    adb("shell", "am", "start", "-W", "-S", "-n", host)
    assert_sources("CODEX", "CLAUDE")
    capture(f"{prefix}-widgets-after-restart")
    print("Verified independent configuration, cancellation, and process restart.", flush=True)


def main():
    adb("logcat", "-c")
    adb("shell", "settings", "put", "global", "window_animation_scale", "0")
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0")
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
    for font, width, height, prefix in [("1.0", 1080, 2160, "regular"), ("1.5", 960, 1920, "large-text")]:
        adb("shell", "wm", "size", f"{width}x{height}")
        adb("shell", "wm", "density", "480")
        adb("shell", "settings", "put", "system", "font_scale", font)
        cases = [("MAIN", "CLAUDE", "data"), ("MAIN", "GPT", "empty"),
                 ("MAIN", "GPT", "error"), ("SETTINGS", "CLAUDE", "data"), ("GLYPH", "GPT", "data"),
                 ("LOGIN_GPT", "GPT", "data"), ("LOGIN_MANUAL", "CLAUDE", "data"), ("ABOUT", "GPT", "data")]
        if os.environ.get("PREVIEW_SCOPE") == "layout-fixes":
            cases = [("SETTINGS", "CLAUDE", "data"), ("GLYPH", "GPT", "data"), ("LOGIN_MANUAL", "CLAUDE", "data")]
        elif os.environ.get("PREVIEW_SCOPE") == "glyph":
            cases = [("GLYPH", "GPT", "data")]
        elif os.environ.get("PREVIEW_SCOPE") == "widgets":
            cases = []
            capture_widget_configuration(prefix)
        elif os.environ.get("PREVIEW_SCOPE") == "auth":
            cases = [("LOGIN_GPT", "GPT", "data"), ("LOGIN_GPT", "GPT", "pending"), ("LOGIN_GPT", "GPT", "browser"), ("LOGIN_GPT", "GPT", "error")]
        elif os.environ.get("PREVIEW_SCOPE") == "about":
            cases = [("SETTINGS", "CLAUDE", "data"), ("SETTINGS", "GPT", "data"), ("ABOUT", "GPT", "data")]
        for screen, provider, scenario in cases:
            open_page(screen, provider, scenario)
            name = f"{prefix}-{screen.lower()}-{provider.lower()}-{scenario}"
            xml = capture(name)
            if screen == "LOGIN_GPT" and os.environ.get("PREVIEW_SCOPE") == "auth":
                text = lambda value: lambda n: n.get("text") == value
                if scenario == "pending":
                    tap_node(text("Скопировать код"))
                    if not any(n.get("text") == "Код скопирован" for n in ui_nodes()):
                        raise RuntimeError("Device-code copy confirmation is missing")
                    capture(f"{name}-copied")
                    tap_node(text("Отменить вход"))
                    tap_node(text("Другие способы входа"))
                    tap_node(text("Войти по коду"))
                    if not any(n.get("text") == "ABCD-1234" for n in ui_nodes()):
                        raise RuntimeError("Device-code sign-in cannot restart after cancellation")
                for index in range(1, 3):
                    adb("shell", "input", "swipe", str(width // 2), str(height - 420), str(width // 2), "450", "450")
                    capture(f"{name}-scroll-{index}")
            if screen in ["GLYPH", "SETTINGS", "LOGIN_MANUAL"]:
                for index in range(1, (9 if font == "1.5" else 5) if screen == "GLYPH" else 3):
                    adb("shell", "input", "swipe", str(width // 2), str(height - 420), str(width // 2), "450", "450")
                    xml = capture(f"{name}-scroll-{index}")
            if os.environ.get("PREVIEW_SCOPE") == "about":
                if screen == "SETTINGS":
                    tap_node(lambda n: n.get("text") == "О приложении")
                    capture(f"{name}-opened-about")
                version = re.search(r'versionName\s*=\s*"([^"]+)"', Path("app/build.gradle.kts").read_text()).group(1)
                if not any(n.get("text") == "Версия " + version for n in ui_nodes()):
                    raise RuntimeError("About page did not open or shows a different installed version")
                for index in range(1, 3):
                    adb("shell", "input", "swipe", str(width // 2), str(height - 420), str(width // 2), "450", "450")
                    capture(f"{name}-about-scroll-{index}")
                if not any(n.get("text") == "Релизы и обновления" for n in ui_nodes()):
                    raise RuntimeError("About page links are inaccessible")
                if screen == "SETTINGS":
                    adb("shell", "input", "keyevent", "4")
                    if not any(n.get("text") == "Настройки" for n in ui_nodes()):
                        raise RuntimeError("Back from About did not return to Settings")
            if screen == "LOGIN_MANUAL":
                open_page(screen, provider, scenario)
                time.sleep(1)
                xml = capture(f"{name}-before-keyboard")
                target = None
                for attempt in range(4):
                    target = next((n for n in ET.fromstring(xml).iter("node")
                                   if n.get("class") == "android.widget.EditText"), None)
                    if target is not None:
                        break
                    adb("shell", "input", "swipe", str(width // 2), str(height - 420), str(width // 2), "450", "450")
                    xml = capture(f"{name}-find-field-{attempt}")
                if target is None:
                    raise RuntimeError("Session-key field is missing after scrolling")
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.attrib["bounds"]))
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                capture(f"{name}-keyboard")
                adb("shell", "input", "keyevent", "4")
        for widget_height in ([] if os.environ.get("PREVIEW_SCOPE") in ("glyph", "auth", "about") else [60, 130, 170, 230, 260]):
            for provider in (["GPT"] if os.environ.get("PREVIEW_SCOPE") == "layout-fixes" else ["CLAUDE", "GPT"]):
                open_page("WIDGET", provider, width=280, height=widget_height)
                capture(f"{prefix}-widget-{provider.lower()}-{widget_height}")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    if os.environ.get("PREVIEW_SCOPE") == "auth":
        from verify_login_lifecycle import verify_login_lifecycle
        verify_login_lifecycle()
    print("All preview layouts rendered without a runtime crash.")


if __name__ == "__main__":
    main()
