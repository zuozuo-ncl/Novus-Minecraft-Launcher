#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把源码里过长的注释块压缩成 1-2 行要点。

规则（保守，只删不写新内容）：
  - 只处理连续注释行组成的块；单行 / 2-3 行的小块原样保留
  - 超过阈值的块：保留首行要点，若块内有「为什么/注意/坑/必须/否则」等关键词行，再留一行
  - 保留文件原有换行符（CRLF/LF）与编码，不引入 diff 噪声
  - 不动文档类文件（.md / .properties / .example）

用法：
  python tools/compress_comments.py <目录> --dry-run
  python tools/compress_comments.py <目录> [--min-block 5] [--only 相对路径 ...]
"""
import argparse
import io
import re
import sys
from pathlib import Path

KEYWORDS = ('为什么', '注意', '坑', '必须', '否则', '不要', '切记', '关键',
            '因为', '不能', '务必', '安全', '历史', '曾', 'NOTE', 'WARN', 'IMPORTANT')

LINE_RX = {
    '.java': re.compile(r'^(\s*)//(.*)$'),
    '.js':   re.compile(r'^(\s*)//(.*)$'),
    '.go':   re.compile(r'^(\s*)//(.*)$'),
    '.cpp':  re.compile(r'^(\s*)//(.*)$'),
    '.h':    re.compile(r'^(\s*)//(.*)$'),
    '.py':   re.compile(r'^(\s*)#(.*)$'),
}


def compress_text(text, ext, min_block):
    """返回 (新文本, 块数, 删除行数)。text 已统一为 \\n 分隔。"""
    rx = LINE_RX[ext]
    lines = text.split('\n')
    out, i, blocks, removed = [], 0, 0, 0

    while i < len(lines):
        line = lines[i]

        # 块注释 /* ... */：收集后按同样规则压缩
        stripped = line.strip()
        if stripped.startswith('/*'):
            j = i
            block = []
            while j < len(lines):
                block.append(lines[j])
                if '*/' in lines[j]:
                    break
                j += 1
            if len(block) > min_block:
                blocks += 1
                removed += len(block) - _keep_count_block(block)
                out.extend(_compress_block_comment(block))
            else:
                out.extend(block)
            i = j + 1
            continue

        m = rx.match(line)
        if not m:
            out.append(line)
            i += 1
            continue

        indent = m.group(1)
        start = i
        block = []
        while i < len(lines):
            mm = rx.match(lines[i])
            if mm and mm.group(1) == indent:
                block.append(mm.group(2).rstrip())
                i += 1
            else:
                break

        if len(block) <= min_block:
            out.extend(lines[start:i])
            continue

        real = [b.strip() for b in block if b.strip()]
        keep = real[:1]
        for b in real[1:]:
            if any(k in b for k in KEYWORDS):
                keep.append(b)
                break
        keep = keep[:2]
        prefix = f'{indent}//' if ext != '.py' else f'{indent}#'
        for k in keep:
            out.append(f'{prefix} {k}' if k else prefix)
        blocks += 1
        removed += len(block) - len(keep)

    return '\n'.join(out), blocks, removed


def _keep_count_block(lines):
    body = [l for l in lines if not l.strip().startswith(('/*', '*', '*/')) and l.strip()]
    keep = body[:1]
    for b in body[1:]:
        if any(k in b for k in KEYWORDS):
            keep.append(b)
            break
    return len(keep) + 2       # 首尾的 /* 与 */


def _compress_block_comment(lines):
    """把 /* ... */ 压成 1-2 行。Javadoc（/**）保持 /** 形式，否则 IDE 悬停会失效。"""
    indent = re.match(r'^(\s*)', lines[0]).group(1)
    is_javadoc = lines[0].strip().startswith('/**')
    body = []
    for l in lines:
        s = l.strip()
        if s.startswith('/**'):
            s = s[3:]
        elif s.startswith('/*'):
            s = s[2:]
        if s.endswith('*/'):
            s = s[:-2]
        s = s.lstrip('*').strip()
        if s:
            body.append(s)

    # 首行要点 + 含关键词的一行 + 用量/示例行（对 API 文档很关键）
    keep = body[:1]
    for b in body[1:]:
        if any(k in b for k in KEYWORDS):
            keep.append(b)
            break
    for b in body:
        if b.startswith(('用法', '示例', 'Example', 'Usage', '调用', '如：', '例：')) and b not in keep:
            keep.append(b)
            break
    keep = keep[:3] if is_javadoc else keep[:2]
    if not keep:
        return [f'{indent}/** */' if is_javadoc else f'{indent}/* */']

    star = '/**' if is_javadoc else '/*'
    if len(keep) == 1:
        return [f'{indent}{star} {keep[0]} */']
    return [f'{indent}{star}', *[f'{indent} * {k}' for k in keep], f'{indent} */']


def process(path: Path, min_block: int, dry: bool):
    ext = path.suffix
    if ext not in LINE_RX:
        return None
    raw = path.read_bytes()
    newline = '\r\n' if b'\r\n' in raw else '\n'
    text = raw.decode('utf-8', errors='replace').replace('\r\n', '\n')
    new, blocks, removed = compress_text(text, ext, min_block)
    if blocks == 0:
        return None
    if not dry:
        data = new.replace('\n', newline).encode('utf-8')
        path.write_bytes(data)
    return blocks, removed


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('root')
    ap.add_argument('--dry-run', action='store_true')
    ap.add_argument('--min-block', type=int, default=5)
    ap.add_argument('--only', nargs='*')
    args = ap.parse_args()

    root = Path(args.root)
    targets = ([root / p for p in args.only] if args.only
               else [p for ext in LINE_RX for p in root.rglob('*' + ext)])
    targets = [p for p in targets
               if 'node_modules' not in p.parts and '.git' not in p.parts and 'target' not in p.parts]

    tb = tr = 0
    for p in sorted(set(targets)):
        if not p.is_file():
            continue
        res = process(p, args.min_block, args.dry_run)
        if res:
            tb += res[0]
            tr += res[1]
            print(f'{p.relative_to(root)}: {res[0]} 块，删 {res[1]} 行')
    print(f'\n合计 {tb} 块，删除 {tr} 行' + ('（dry-run）' if args.dry_run else ''))


if __name__ == '__main__':
    sys.exit(main())