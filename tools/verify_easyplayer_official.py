from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
main = root / "easyplayer-android" / "src" / "main"
required = [
    "java/org/easydarwin/video/EasyPlayerClient.java",
    "java/org/easydarwin/video/Client.java",
    "java/org/easydarwin/player/EasyPlayer.java",
]
abis = ["arm64-v8a", "armeabi-v7a", "x86"]
libs = ["libEasyRTSPClient.so", "libAudioCodecer.so", "libVideoCodecer.so", "libproffmpeg.so", "libyuv_android.so"]
for abi in abis:
    for lib in libs:
        required.append(f"jniLibs/{abi}/{lib}")
missing = [item for item in required if not (main / item).exists()]
if missing:
    print("EasyPlayer 官方文件缺失：")
    for item in missing:
        print("  -", item)
    sys.exit(1)
print("EasyPlayer 官方源码和 jniLibs 校验通过。")
