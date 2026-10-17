#!/usr/bin/env python3
"""汇总 JUnit 测试结果，有失败就退出码 1。CI 和 build.sh 共用。

用法：test-summary.py [结果目录]
默认读 app/build/test-results/testDebugUnitTest/
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main():
    pattern = os.path.join(sys.argv[1] if len(sys.argv) > 1
                           else "app/build/test-results/testDebugUnitTest", "*.xml")
    files = sorted(glob.glob(pattern))
    if not files:
        print("没有测试结果 —— 测试没跑起来", file=sys.stderr)
        return 1

    total = failed = skipped = 0
    for path in files:
        root = ET.parse(path).getroot()
        total += int(root.get("tests"))
        failed += int(root.get("failures")) + int(root.get("errors"))
        skipped += int(root.get("skipped"))
        print(f"  {root.get('name').split('.')[-1]:26} {root.get('tests'):>3} 用例", file=sys.stderr)

    print(f"  合计 {total} 用例，失败 {failed}，跳过 {skipped}", file=sys.stderr)
    if skipped:
        print("  ⚠ 有跳过用例（fixture 不在仓库，属预期；但那部分等于没测）", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
