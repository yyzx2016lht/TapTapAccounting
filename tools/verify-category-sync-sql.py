#!/usr/bin/env python3
"""分类改名时「就地替换展示名」SQL 的语义验证。

项目没有 Room / Robolectric 测试，DAO 里的 `UPDATE ... SET categoryName = CASE ...`
无法被 JVM 单测覆盖——这是长期的测试盲区。本脚本用内存 sqlite3 直接跑这些语句，
把边界行为钉住。

**这些 SQL 是从源码抄过来的**，改动 `BillDao.syncCategoryNameByOldName` /
`RecurringPatternDao.syncCategoryNameByOldName` 时必须同步这里。

用法：
    python tools/verify-category-sync-sql.py      # 全部通过退出码 0，否则 1

为什么不能用 REPLACE()：
    它是全局替换。分类名互为子串时会误伤——父级「餐」改名为「新餐」后，
    「餐 - 三餐」会变成「新餐 - 三新餐」（子分类的叶子也被改了）。
    必须按位置精确替换：整串相等 / 路径开头 / 路径末尾，三种形态各处理一次。
"""

import sqlite3
import sys

# --- 与 BillDao.syncCategoryNameByOldName 保持一致 ---
BILLS_SQL = """
UPDATE bills
SET categoryName = CASE
    WHEN categoryName = '退款：' || :oldLeaf
        THEN '退款：' || :newLeaf
    WHEN categoryName LIKE '退款：' || :oldLeaf || ' - %'
        THEN '退款：' || :newLeaf || SUBSTR(categoryName, LENGTH('退款：' || :oldLeaf) + 1)
    WHEN categoryName LIKE '退款：% - ' || :oldLeaf
        THEN SUBSTR(categoryName, 1, LENGTH(categoryName) - LENGTH(:oldLeaf)) || :newLeaf
    WHEN categoryName = :oldLeaf
        THEN :newLeaf
    WHEN categoryName LIKE :oldLeaf || ' - %'
        THEN :newLeaf || SUBSTR(categoryName, LENGTH(:oldLeaf) + 1)
    ELSE SUBSTR(categoryName, 1, LENGTH(categoryName) - LENGTH(:oldLeaf)) || :newLeaf
END
WHERE categoryId IS NULL
  AND (
      categoryName = :oldLeaf
      OR categoryName LIKE '% - ' || :oldLeaf
      OR categoryName LIKE :oldLeaf || ' - %'
      OR categoryName = '退款：' || :oldLeaf
      OR categoryName LIKE '退款：% - ' || :oldLeaf
      OR categoryName LIKE '退款：' || :oldLeaf || ' - %'
  )
"""

# --- 与 RecurringPatternDao.syncCategoryNameByOldName 保持一致 ---
# 周期模式不会是退款（detect / matchNewBill 都过滤了退款），故无「退款：」分支。
PATTERNS_SQL = """
UPDATE recurring_patterns
SET categoryName = CASE
    WHEN categoryName = :oldLeaf
        THEN :newLeaf
    WHEN categoryName LIKE :oldLeaf || ' - %'
        THEN :newLeaf || SUBSTR(categoryName, LENGTH(:oldLeaf) + 1)
    ELSE SUBSTR(categoryName, 1, LENGTH(categoryName) - LENGTH(:oldLeaf)) || :newLeaf
END,
updatedAt = :now
WHERE categoryId IS NULL
  AND categoryName IS NOT NULL
  AND (
      categoryName = :oldLeaf
      OR categoryName LIKE '% - ' || :oldLeaf
      OR categoryName LIKE :oldLeaf || ' - %'
  )
"""

# (说明, SQL, 表名, 初始行 [(id, categoryId, categoryName)], 参数, 期望 [(id, categoryName)])
CASES = [
    (
        "账单：祖先改名（旧叶子在路径开头）",
        BILLS_SQL, "bills",
        [(1, None, "吃的 - 三餐"), (2, None, "吃的 - 三餐加餐"),
         (3, None, "三餐"), (4, None, "喝的 - 咖啡"), (5, 11, "吃的 - 三餐")],
        {"oldLeaf": "吃的", "newLeaf": "餐饮", "now": 1},
        [(1, "餐饮 - 三餐"), (2, "餐饮 - 三餐加餐"),
         (3, "三餐"), (4, "喝的 - 咖啡"), (5, "吃的 - 三餐")],
    ),
    (
        "账单：子分类改名（旧叶子在路径末尾）",
        BILLS_SQL, "bills",
        [(1, None, "吃的 - 三餐"), (2, None, "吃的 - 三餐加餐"), (3, None, "三餐")],
        {"oldLeaf": "三餐", "newLeaf": "早餐", "now": 1},
        [(1, "吃的 - 早餐"), (2, "吃的 - 三餐加餐"), (3, "早餐")],
    ),
    (
        "账单：子串重叠——父级「餐」改名不该动到子分类叶子",
        BILLS_SQL, "bills",
        [(1, None, "餐 - 三餐"), (2, None, "餐 - 早餐"), (3, None, "喝的 - 咖啡")],
        {"oldLeaf": "餐", "newLeaf": "新餐", "now": 1},
        [(1, "新餐 - 三餐"), (2, "新餐 - 早餐"), (3, "喝的 - 咖啡")],
    ),
    (
        "账单：退款前缀要保留，且不能被子串替换误伤",
        BILLS_SQL, "bills",
        [(1, None, "餐 - 三餐"), (2, None, "退款：餐 - 三餐"),
         (3, None, "退款：喝的 - 咖啡"), (4, None, "退款：餐")],
        {"oldLeaf": "餐", "newLeaf": "新餐", "now": 1},
        [(1, "新餐 - 三餐"), (2, "退款：新餐 - 三餐"),
         (3, "退款：喝的 - 咖啡"), (4, "退款：新餐")],
    ),
    (
        "账单：祖先改名 + 退款前缀组合",
        BILLS_SQL, "bills",
        [(1, None, "吃的 - 三餐"), (2, None, "退款：吃的 - 三餐"),
         (3, None, "吃的"), (4, None, "喝的 - 咖啡"), (5, 11, "吃的 - 三餐")],
        {"oldLeaf": "吃的", "newLeaf": "餐饮", "now": 1},
        [(1, "餐饮 - 三餐"), (2, "退款：餐饮 - 三餐"),
         (3, "餐饮"), (4, "喝的 - 咖啡"), (5, "吃的 - 三餐")],
    ),
    (
        "周期模式：祖先改名",
        PATTERNS_SQL, "recurring_patterns",
        [(1, None, "吃的 - 三餐"), (2, None, "吃的 - 三餐加餐"),
         (3, None, "三餐"), (4, None, "喝的 - 咖啡"), (5, 11, "吃的 - 三餐")],
        {"oldLeaf": "吃的", "newLeaf": "餐饮", "now": 1},
        [(1, "餐饮 - 三餐"), (2, "餐饮 - 三餐加餐"),
         (3, "三餐"), (4, "喝的 - 咖啡"), (5, "吃的 - 三餐")],
    ),
    (
        "周期模式：子串重叠",
        PATTERNS_SQL, "recurring_patterns",
        [(1, None, "餐 - 三餐"), (2, None, "餐 - 早餐")],
        {"oldLeaf": "餐", "newLeaf": "新餐", "now": 1},
        [(1, "新餐 - 三餐"), (2, "新餐 - 早餐")],
    ),
    (
        "周期模式：有 categoryId 的记录不动（由按 id 的那条语句负责）",
        PATTERNS_SQL, "recurring_patterns",
        [(1, 11, "吃的 - 三餐")],
        {"oldLeaf": "吃的", "newLeaf": "餐饮", "now": 1},
        [(1, "吃的 - 三餐")],
    ),
]


def run_case(label, sql, table, seed, params, expect):
    conn = sqlite3.connect(":memory:")
    conn.execute(
        f"CREATE TABLE {table} "
        "(id INTEGER PRIMARY KEY, categoryId INTEGER, categoryName TEXT, updatedAt INTEGER)"
    )
    conn.executemany(
        f"INSERT INTO {table} (id, categoryId, categoryName, updatedAt) VALUES (?, ?, ?, 0)",
        seed,
    )
    conn.execute(sql, params)
    got = list(conn.execute(f"SELECT id, categoryName FROM {table} ORDER BY id"))
    conn.close()
    if got == expect:
        print(f"  PASS  {label}")
        return True
    print(f"  FAIL  {label}")
    for g, e in zip(got, expect):
        print(f"          got={g}  expect={e}{'' if g == e else '   <-- 不一致'}")
    return False


def main():
    print("分类改名就地替换 SQL —— 语义验证\n")
    ok = True
    for case in CASES:
        ok &= run_case(*case)
    print()
    print("ALL PASS" if ok else "HAS FAILURES")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
