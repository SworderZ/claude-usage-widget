"""Regression checks against the real Android login lifecycle with a debug-only fake API."""

import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from capture_previews import adb, capture, ui_nodes, tap_node, PACKAGE


def verify_login_lifecycle():
    activity = f"{PACKAGE}/.ui.OpenAiLoginLifecyclePreviewActivity"
    text = lambda value: lambda n: n.get("text") == value

    def open_login(reset=False):
        args = ["shell", "am", "start", "-W", "-n", activity]
        if reset:
            args += ["--ez", "reset", "true"]
        adb(*args)
        time.sleep(2)

    def assert_code(code):
        if not any(n.get("text") == code for n in ui_nodes()):
            capture("lifecycle-unexpected-code")
            raise RuntimeError("Pending login was lost or replaced")
        prefs = ET.fromstring(adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/login_lifecycle_fixture.xml"))
        starts = next(n.get("value") for n in prefs if n.get("name") == "starts")
        if starts != code[-1]:
            raise RuntimeError("Returning to the app requested a replacement device code")

    try:
        adb("shell", "am", "force-stop", PACKAGE)
        open_login(reset=True)
        tap_node(text("Другие способы входа"))
        tap_node(text("Войти по коду"))
        time.sleep(2)
        assert_code("LIFE-0001")
        if not any("Вход сохранён" in n.get("text", "") for n in ui_nodes()):
            raise RuntimeError("The fake network interruption was not exercised")
        capture("lifecycle-network-retry")

        # Force Activity and ViewModel destruction when the external app comes forward.
        adb("shell", "settings", "put", "global", "always_finish_activities", "1")
        tap_node(text("Открыть ChatGPT"))
        time.sleep(2)
        open_login()
        assert_code("LIFE-0001")
        capture("lifecycle-after-external-app")

        # A force-stop kills the service and singleton as well. The same attempt must decrypt on restart.
        adb("shell", "am", "force-stop", PACKAGE)
        open_login()
        assert_code("LIFE-0001")
        capture("lifecycle-after-process-restart")
        tap_node(text("Отменить вход"))
        tap_node(text("Другие способы входа"))
        tap_node(text("Войти по коду"))
        assert_code("LIFE-0002")
        tap_node(text("Отменить вход"))

        # The browser listener must survive leaving the app, and a full process restart.
        tap_node(text("Войти через ChatGPT"))
        time.sleep(2)
        adb("shell", "am", "force-stop", PACKAGE)
        open_login()
        if not any(n.get("text") == "Завершите вход в браузере" for n in ui_nodes()):
            raise RuntimeError("Browser sign-in was not restored")
        capture("lifecycle-browser-after-restart")
        forwarded = adb("forward", "tcp:0", "tcp:1455").decode().strip()
        try:
            base = f"http://127.0.0.1:{forwarded}/auth/callback"
            try:
                urllib.request.urlopen(base + "?state=wrong&code=unrelated", timeout=10)
                raise RuntimeError("Unrelated callback was accepted")
            except urllib.error.HTTPError as error:
                if error.code != 400:
                    raise
            with urllib.request.urlopen(base + "?state=fixture-state&code=fixture-approved-code", timeout=10) as response:
                if response.status != 200:
                    raise RuntimeError("The matching browser callback was rejected")
            for _ in range(10):
                prefs = ET.fromstring(adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/login_lifecycle_fixture.xml"))
                if any(n.get("name") == "connected" and n.get("value") == "true" for n in prefs):
                    break
                time.sleep(1)
            else:
                raise RuntimeError("Browser callback did not finish the pending login")
        finally:
            adb("forward", "--remove", "tcp:" + forwarded)
        log = adb("logcat", "-d", "-s", "AndroidRuntime:E").decode(errors="replace")
        if "FATAL EXCEPTION" in log and PACKAGE in log:
            raise RuntimeError("Login lifecycle test crashed")
        print("Verified network retries, Activity destruction, encrypted process restore, cancellation and loopback browser completion.", flush=True)
    finally:
        adb("shell", "settings", "put", "global", "always_finish_activities", "0")
