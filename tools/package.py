#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
离线打包启动器 jar（shaded，含全部依赖）—— 纯 Python 实现。

为什么不用 shell：之前用 bash 逐文件 cp，29 个依赖约 1 万个文件，
Windows 上每个文件 fork 一次 cp，整个打包要 20 分钟（sys 时间就占了 9 分钟）。
zipfile 在进程内流式读写，同样的事只要几秒。

行为对齐 maven-shade-plugin：
  - 主项目产物优先（同名条目保留第一个出现的）
  - 排除 META-INF 下的签名文件与各依赖自带的 MANIFEST.MF
  - 合并 META-INF/services/*（按行去重）
  - 写自定义 Manifest（Main-Class / Implementation-Version）

用法：
    python tools/package.py [版本号] [--skip-compile]
      版本号默认读 pom.xml 的 <version>。
      --skip-compile：复用 target/classes（只重新组装 jar，仍会复制资源）
"""

import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "target"
CP_FILE = TARGET / "cp.txt"

# 与 pom.xml 中 shade 插件 filters 一致
SKIP_PREFIX = ("META-INF/",)
SKIP_NAME_RE = re.compile(r"^META-INF/[^/]+\.(SF|DSA|RSA)$", re.I)
SERVICES_PREFIX = "META-INF/services/"


def find_jdk_bin() -> Path:
    """优先 JAVA_HOME，其次常见安装位置，最后用 PATH 上的 javac。"""
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / "javac.exe").exists():
        return Path(home) / "bin"
    for p in (r"C:\Program Files\Java\jdk-21.0.10",
              r"C:\Program Files\Java\jdk-17"):
        if (Path(p) / "bin" / "javac.exe").exists():
            return Path(p) / "bin"
    which = shutil.which("javac")
    if which:
        return Path(which).parent
    sys.exit("找不到 javac：请设置 JAVA_HOME")


def read_version() -> str:
    m = re.search(r"<version>([^<]+)</version>", (ROOT / "pom.xml").read_text(encoding="utf-8"))
    if not m:
        sys.exit("无法从 pom.xml 读取 <version>")
    return m.group(1).strip()


def read_classpath() -> str:
    if not CP_FILE.exists():
        sys.exit("缺少 target/cp.txt（依赖 classpath）。"
                 "可用 mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt 生成")
    return CP_FILE.read_text(encoding="utf-8").replace("\r", "").replace("\n", "")


def should_skip(name: str) -> bool:
    # 各依赖自带的清单、签名、以及会与主项目冲突的模块描述
    if name == "META-INF/MANIFEST.MF" or name.upper().endswith("MODULE-INFO.CLASS"):
        return True
    if SKIP_NAME_RE.match(name):
        return True
    # 目录条目：由写入时按需创建，跳过更省时间
    if name.endswith("/"):
        return True
    return False


def main() -> int:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    skip_compile = "--skip-compile" in sys.argv
    version = args[0] if args else read_version()

    jdk = find_jdk_bin()
    javac, jar = jdk / "javac", jdk / "jar"
    cp = read_classpath()

    classes = TARGET / "classes-build"
    if not skip_compile:
        print(f"=== 1. 编译（{version}）===")
        if classes.exists():
            shutil.rmtree(classes)
        classes.mkdir(parents=True)
        srcs = [str(p) for p in (ROOT / "src" / "main" / "java").rglob("*.java")]
        cmd = [str(javac), "-encoding", "UTF-8", "--release", "17", "-nowarn",
               "-d", str(classes), "-cp", cp] + srcs
        r = subprocess.run(cmd)
        if r.returncode != 0:
            sys.exit("编译失败")
    elif not classes.exists():
        sys.exit("--skip-compile 但 target/classes-build 不存在")

    print("=== 2. 复制资源 ===")
    res = ROOT / "src" / "main" / "resources"
    for p in res.rglob("*"):
        if p.is_file():
            dst = classes / p.relative_to(res)
            dst.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(p, dst)
    # Maven 会过滤 ${project.version}，这里直接写真实版本号
    (classes / "launcher-version.txt").write_text(version, encoding="utf-8")

    print("=== 3. 组装 shaded jar ===")
    out = TARGET / f"mc-launcher-{version}-shaded.jar"
    tmp_out = TARGET / f".mc-launcher-{version}-shaded.jar.tmp"

    manifest = (
        "Manifest-Version: 1.0\r\n"
        "Created-By: offline javac + zipfile package\r\n"
        "Main-Class: com.mclauncher.Main\r\n"
        "Implementation-Title: mc-launcher\r\n"
        f"Implementation-Version: {version}\r\n"
    )

    # 合并 META-INF/services：先收集，最后统一写入
    services: dict[str, list[str]] = {}
    written: set[str] = set()
    collisions = 0
    deps = [d for d in cp.split(";") if d and Path(d).is_file()]

    with zipfile.ZipFile(tmp_out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        # 3a 主项目产物（优先级最高）
        for p in sorted(classes.rglob("*")):
            if not p.is_file():
                continue
            name = p.relative_to(classes).as_posix()
            if should_skip(name):
                continue
            zout.write(p, name)
            written.add(name)

        # 3b 依赖
        for dep in deps:
            with zipfile.ZipFile(dep) as zin:
                for info in zin.infolist():
                    name = info.filename.replace("\\", "/")
                    if should_skip(name):
                        continue
                    if name.startswith(SERVICES_PREFIX):
                        text = zin.read(info).decode("utf-8", "replace")
                        services.setdefault(name, []).extend(
                            ln.strip() for ln in text.splitlines() if ln.strip())
                        continue
                    if name in written:
                        collisions += 1
                        continue        # shade：保留先出现的（主项目优先）
                    data = zin.read(info)
                    # 所有条目统一用 deflate 重新压缩：与 shade 一致。
                    # 曾经把 dll/png 设成 STORED（以为原生库没必要压缩），
                    # 结果是 jfxwebkit.dll 直接让包从 45 MB 涨到 90 MB。
                    zi = zipfile.ZipInfo(name, date_time=info.date_time)
                    zi.compress_type = zipfile.ZIP_DEFLATED
                    zi.external_attr = info.external_attr
                    zout.writestr(zi, data)
                    written.add(name)

        # 3c services（去重并保持顺序）
        for name, lines in services.items():
            seen, uniq = set(), []
            for ln in lines:
                if ln not in seen:
                    seen.add(ln)
                    uniq.append(ln)
            zout.writestr(name, "\n".join(uniq) + "\n")

        # 3d Manifest
        zout.writestr("META-INF/MANIFEST.MF", manifest)

    tmp_out.replace(out)
    print(f"=== 4. 完成：{out}（{out.stat().st_size:,} 字节，"
          f"{len(written)} 个条目，跳过 {collisions} 个同名条目）===")

    # 校验
    with zipfile.ZipFile(out) as z:
        names = set(z.namelist())
        bad = []
        for need in ("com/mclauncher/Main.class",
                     "com/mclauncher/launcher/LauncherApp.class",
                     "com/mclauncher/ui/window/FeedbackDialog.class",
                     "com/mclauncher/multiplayer/vpn/RelayEndpoints.class",
                     "i18n/messages_zh_CN.properties",
                     "launcher-version.txt"):
            if need not in names:
                bad.append(need)
        if any(n.startswith("com/mclauncher/test/") for n in names):
            bad.append("（测试类混入 jar）")
        if out.stat().st_size < 10_000_000:
            bad.append(f"（文件偏小 {out.stat().st_size}，可能缺依赖）")
    if bad:
        print("校验失败：" + "，".join(bad))
        return 1
    print("校验通过（关键类与资源齐全，无测试类）")
    return 0


if __name__ == "__main__":
    sys.exit(main())