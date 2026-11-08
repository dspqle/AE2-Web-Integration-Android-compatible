#!/bin/bash
# 构建 ae2androidcompat-1.0.0.jar
#
# 依赖：JDK 21+、neoforge 的 modlauncher-11.0.5.jar 和 loader-4.0.44.jar
# 用法：把这两个 jar 放到 ./lib/ 下，然后执行 bash build.sh

set -u

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"

ML="lib/modlauncher-11.0.5.jar"
LD="lib/loader-4.0.44.jar"
OUT="out"
STAGE="stage"

# 1. 检查依赖
for j in "$ML" "$LD"; do
    if [ ! -f "$j" ]; then
        echo "[!] 缺少依赖：$j"
        echo "    请从 NeoForge 21.1.x 的 libraries 目录中复制这两个 jar 到 ./lib/"
        exit 1
    fi
done

# 2. 检查原生库
if [ ! -f "res/native/libsqlitejdbc.so" ]; then
    echo "[!] 缺少 res/native/libsqlitejdbc.so"
    echo "    这是 Android(bionic) aarch64 版 sqlite-jdbc 原生库，必须自行提供。"
    exit 1
fi

# 3. 编译
rm -rf "$OUT"
mkdir -p "$OUT"
echo "[*] 编译中…"
javac -nowarn --release 21 -encoding UTF-8 \
    -cp "$ML:$LD" \
    -d "$OUT" \
    src/ae2compat/*.java || exit 1

# 4. 组装
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -r "$OUT/ae2compat" "$STAGE/"
mkdir -p "$STAGE/META-INF/services" "$STAGE/native"
cp res/META-INF/neoforge.mods.toml "$STAGE/META-INF/"
cp res/META-INF/services/* "$STAGE/META-INF/services/"
cp res/native/libsqlitejdbc.so "$STAGE/native/libsqlitejdbc.so"

cat > "$STAGE/META-INF/MANIFEST.MF" <<'EOF'
Manifest-Version: 1.0
Implementation-Title: AE2 Android Compat
Implementation-Version: 1.0.0
Specification-Vendor: ae2compat
EOF

# 5. 打包
JAR="ae2androidcompat-1.0.0.jar"
rm -f "$JAR"
( cd "$STAGE" && jar --create --file "../$JAR" --manifest META-INF/MANIFEST.MF -C . . ) || exit 1

echo "[+] 完成：$DIR/$JAR"
ls -la "$JAR"
