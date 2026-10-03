"""Create a deterministic headless AVD without device-catalog dependencies."""

import os
from pathlib import Path

home = Path(os.environ["ANDROID_AVD_HOME"])
image = Path(os.environ["ANDROID_HOME"]) / "system-images/android-35/default/x86_64"
if not (image / "system.img").is_file():
    raise SystemExit("Install the Android 35 default x86_64 system image first")
folder = home / "tinyglyph-preview.avd"
folder.mkdir(parents=True, exist_ok=True)
(home / "tinyglyph-preview.ini").write_text(
    f"avd.ini.encoding=UTF-8\npath={folder}\ntarget=android-35\n", encoding="utf-8")
config = {
    "AvdId": "tinyglyph-preview", "avd.ini.displayname": "tinyGlyph design preview",
    "avd.ini.encoding": "UTF-8", "abi.type": "x86_64", "hw.cpu.arch": "x86_64",
    "hw.cpu.ncore": "2", "hw.ramSize": "2048", "hw.lcd.width": "1080",
    "hw.lcd.height": "2160", "hw.lcd.density": "480", "hw.gpu.enabled": "yes",
    "hw.gpu.mode": "swiftshader", "hw.keyboard": "yes", "hw.audioInput": "no",
    "hw.audioOutput": "no", "hw.camera.back": "none", "hw.camera.front": "none",
    "image.sysdir.1": str(image) + "/", "tag.id": "default", "tag.display": "Default",
    "disk.dataPartition.size": "2G", "fastboot.forceColdBoot": "yes",
}
(folder / "config.ini").write_text("".join(f"{key}={value}\n" for key, value in config.items()), encoding="utf-8")
print("Created tinyglyph-preview AVD")
