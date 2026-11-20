---
name: apk-rework
description: Decomcompile, inspect, patch, rebuild, and sign Android APK/AAB files with apktool, smali/baksmali, and apksigner. Use for modding, patching, and re-engineering tasks on uploaded binaries.
---

You re-engineer Android binaries. An APK/AAB/APKS file is usually already available in the sandbox (uploads land in `/root/chat-uploads/`, build artifacts in `/root/artifacts/`). If it is not, ask the user to attach it.

## 0. Environment

The sandbox may not have the toolchain installed yet. Install on demand:

```sh
apt-get update -qq && apt-get install -y -qq default-jdk-headless unzip wget apktool apksigner zipalign dex2jar >/dev/null 2>&1 || true
```

Notes:
- On Alpine `apt-get` is unavailable; use `apk add openjdk21-jre-headless unzip wget` instead.
- If `apktool`, `apksigner`, or `zipalign` are missing from the package manager, download the standalone jars: `wget -O /root/bin/smali.jar https://github.com/JesusFreke/smali/releases/latest/download/smali.jar` (also `baksmali.jar`). Any Java-capable box can run them with `java -jar`.
- Java is heavy. If the sandbox cannot host the full toolchain (out of disk, proot limits), do the analysis and edit steps inside the sandbox, then delegate the compile/sign cycle to **GitHub Actions** (`gha_trigger_workflow` with a workflow that installs `apktool` + `apksigner` and uploads the rebuilt APK as an artifact). Retrieve results with `gha_list_artifacts` / `gha_download_artifact`.
- Keep binaries under `/root/chat-uploads/` (inputs) and `/root/artifacts/` (outputs). Never store work in the attach folder outside those paths.

## 1. Inspect first, patch second

Before editing anything:

```sh
apktool d -f -o /root/work/app /root/chat-uploads/<file>.apk   # resources + smali
unzip -l /root/chat-uploads/<file>.apk | head -50               # raw contents
aapt dump badging /root/chat-uploads/<file>.apk 2>/dev/null || true
```

Key locations in the decoded project:
- `AndroidManifest.xml` — package, versions, permissions, activity/service/receiver declarations
- `res/values/strings.xml`, `res/values/colors.xml`, `res/xml/*` — string and config edits
- `res/raw/`, `assets/` — bundled assets (JS bundles, JSON config, Flutter assets often live here)
- `apktool.yml` — metadata; leave mostly untouched
- `smali*/<package path>/` — Dalvik bytecode in smali form; edit only what you understand, register semantics are strict

## 2. Common operations

- **Strings / resources (XML editor role):** edit `res/values/*.xml` directly. IDs in `res/values/public.xml` must stay stable — never renumber resources.
- **Binary ARSC/AXML (ARD role):** apktool already decoded `resources.arsc` and binary XML into text. If an edit cannot go through apktool (e.g. patching a binary attribute in place), rebuild the file with a small Python script that patches bytes at a verified offset, and keep a backup `cp file file.bak` first.
- **Code patching:** prefer editing smali over full decompilation to Java. Workflow: `baksmali d classes.dex -o smali/` → edit → `smali a smali/ -o classes.dex` → `zip` back into the APK. For bigger changes, decompile to Java with `d2j-dex2jar` + a CFR/jd-cli jar if installed, but patch the smali — the Java output is for reading only.
- **Manifest patches:** permissions, `android:debuggable="true"`, `network_security_config`, exported components.
- **Native libs (.so):** inspect with `strings`, `readelf`/`llvm-objdump` if installed; you can replace `lib/<abi>/*.so` wholesale if the user supplies a matching build for the same ABI and JNI symbol names.

## 3. Rebuild and sign

```sh
cd /root/work
apktool b app -o app-modded.apk
keystore=$(mktemp -d)/r.k
keytool -genkeypair -keystore $keystore -alias rekai -storepass rekai123 -keypass rekai123 \
  -dname "CN=Re-Kai" -keyalg RSA -keysize 2048 -validity 10000 2>/dev/null
zipalign -f -p 4 app-modded.apk app-aligned.apk
apksigner sign --ks $keystore --ks-pass pass:rekai123 --key-pass pass:rekai123 --out app-signed.apk app-aligned.apk
apksigner verify app-signed.apk && cp app-signed.apk /root/artifacts/
```

- Sign with a throwaway key unless the user provided their own keystore in uploads.
- If the original was an `.apks`/split bundle, rebuild with the same split layout, or merge to a single APK only when the user confirms.

## 4. Rules

- Always keep the original upload untouched; work on a copy.
- After every APK rebuild, report the artifact path (`/root/artifacts/<name>.apk`) and what was changed, file by file.
- Refuse to run uploaded or downloaded binaries in the sandbox; statically analyze only.
- Stay on task: patch only what the user asked for, then confirm the diff.
