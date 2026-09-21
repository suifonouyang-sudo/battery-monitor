@echo off
REM ============================================================
REM  battery-monitor.apk 一键构建（无需 Gradle，纯 SDK 命令行）
REM  前置: Android SDK (C:\Android\Sdk) + JDK 17/21
REM        Shizuku 依赖已解压到 build\szlib\（见 README 注释）
REM  产物: battery-monitor.apk（含 Shizuku root/adb 支持）
REM ============================================================
setlocal enabledelayedexpansion
cd /d %~dp0

set SDK=C:\Android\Sdk
set BT=%SDK%\build-tools\34.0.0
set PLAT=%SDK%\platforms\android-34\android.jar
set JAVAC="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\javac"
set JAR="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\jar"
set KEYTOOL="C:\Program Files\Microsoft\jdk-21.0.12.101-hotspot\bin\keytool"

REM --- 调试签名密钥：不入库（见 .gitignore），缺失时自动生成一个 ---
if not exist debug.keystore (
  echo Generating debug keystore (NOT for release publishing)...
  %KEYTOOL% -genkeypair -v -keystore debug.keystore -alias batterymonitor ^
    -keyalg RSA -keysize 2048 -validity 10000 ^
    -storepass android -keypass android ^
    -dname "CN=BatteryMonitor, OU=Debug, O=BatteryMonitor, C=CN" || exit /b 1
)

REM --- Shizuku 依赖（dev.rikka.shizuku api/aidl/provider 13.1.5 的 classes.jar）---
if not exist build\szlib\shizuku-api (
  echo Extracting Shizuku libs from libs\*.aar ...
  mkdir build\szlib\shizuku-api build\szlib\shizuku-aidl build\szlib\shizuku-provider
  python -c "import zipfile,io,os;z=zipfile.ZipFile('libs/shizuku-api.aar');zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))).extractall('build/szlib/shizuku-api')"
  python -c "import zipfile,io,os;z=zipfile.ZipFile('libs/shizuku-aidl.aar');zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))).extractall('build/szlib/shizuku-aidl')"
  python -c "import zipfile,io,os;z=zipfile.ZipFile('libs/shizuku-provider.aar');zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))).extractall('build/szlib/shizuku-provider')"
)
set CP=%PLAT%;build\gen;build\szlib\shizuku-api;build\szlib\shizuku-aidl;build\szlib\shizuku-provider

if not exist build\obj     mkdir build\obj
if not exist build\gen     mkdir build\gen
if not exist build\dexout  mkdir build\dexout

echo [1/7] aapt2 compile...
%BT%\aapt2.exe compile --dir res -o build\res.zip || exit /b 1

echo [2/7] aapt2 link...
%BT%\aapt2.exe link build\res.zip -I %PLAT% --manifest AndroidManifest.xml --java build\gen -o build\app-unsigned.apk --min-sdk-version 26 --target-sdk-version 34 || exit /b 1

echo [3/7] javac --release 17...
dir /s /b build\gen\*.java src\*.java > build\sources.txt
%JAVAC% --release 17 -nowarn -d build\obj -cp "%CP%" @build\sources.txt || exit /b 1

echo [4/7] merge Shizuku lib classes + jar...
xcopy /e /i /y /q build\szlib\shizuku-api build\obj >nul
xcopy /e /i /y /q build\szlib\shizuku-aidl build\obj >nul
xcopy /e /i /y /q build\szlib\shizuku-provider build\obj >nul
%JAR% cf build\classes.jar -C build\obj .

echo [5/7] d8 dex...
%BT%\d8.bat --output build\dexout build\classes.jar || exit /b 1

echo [6/7] inject dex...
python inject_dex.py build\dexout\classes.dex build\app-unsigned.apk || exit /b 1

echo [7/7] zipalign + sign...
%BT%\zipalign.exe -p 4 build\app-unsigned.apk build\app-aligned.apk || exit /b 1
%BT%\apksigner.bat sign --ks debug.keystore --ks-key-alias batterymonitor --ks-pass pass:android --key-pass pass:android --out battery-monitor.apk build\app-aligned.apk || exit /b 1

echo Done: battery-monitor.apk
endlocal
