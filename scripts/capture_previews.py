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


def main():
    adb("logcat", "-c")
    adb("shell", "settings", "put", "global", "window_animation_scale", "0")
    adb("shell", "settings", "put", "global", "transition_animation_scale", "0")
    adb("shell", "settings", "put", "global", "animator_duration_scale", "0")
    for font, width, height, prefix in [("1.0", 1080, 2160, "regular"), ("1.5", 960, 1920, "large-text")]:
        adb("shell", "wm", "size", f"{width}x{height}")
        adb("shell", "wm", "density", "480")
        adb("shell", "settings", "put", "system", "font_scale", font)
        for screen, provider, scenario in [("MAIN", "CLAUDE", "data"), ("MAIN", "GPT", "empty"),
                ("MAIN", "GPT", "error"), ("SETTINGS", "CLAUDE", "data"), ("GLYPH", "GPT", "data"),
                ("LOGIN_GPT", "GPT", "data"), ("LOGIN_MANUAL", "CLAUDE", "data")]:
            open_page(screen, provider, scenario)
            name = f"{prefix}-{screen.lower()}-{provider.lower()}-{scenario}"
            xml = capture(name)
            if screen in ["GLYPH", "SETTINGS", "LOGIN_MANUAL"]:
                for index in range(1, 5 if screen == "GLYPH" else 3):
                    adb("shell", "input", "swipe", str(width // 2), str(height - 420), str(width // 2), "450", "450")
                    xml = capture(f"{name}-scroll-{index}")
            if screen == "LOGIN_MANUAL":
                open_page(screen, provider, scenario)
                time.sleep(1)
                xml = capture(f"{name}-before-keyboard")
                nodes = ET.fromstring(xml).iter("node")
                target = next((n for n in nodes if n.get("class") == "android.widget.EditText"), None)
                if target is None:
                    raise RuntimeError("Session-key field is missing")
                x1, y1, x2, y2 = map(int, re.findall(r"\d+", target.attrib["bounds"]))
                adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                capture(f"{name}-keyboard")
                adb("shell", "input", "keyevent", "4")
        for widget_height in [60, 130, 170, 230]:
            for provider in ["CLAUDE", "GPT"]:
                open_page("WIDGET", provider, width=280, height=widget_height)
                capture(f"{prefix}-widget-{provider.lower()}-{widget_height}")
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    print("All preview layouts rendered without a runtime crash.")


if __name__ == "__main__":
    main()
