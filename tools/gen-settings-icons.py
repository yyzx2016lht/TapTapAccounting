#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成设置页统一风格的线性图标：24dp 网格 / 1.8dp 描边 / 圆角端点。

图标颜色固定为白色，由外层色块（badge）提供底色。
"""

import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app", "src", "main", "res", "drawable")

HEAD = '''<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
'''
TAIL = "</vector>\n"

STROKE = ('android:fillColor="#00000000"\n'
          '        android:strokeColor="#FFFFFF"\n'
          '        android:strokeWidth="1.8"\n'
          '        android:strokeLineCap="round"\n'
          '        android:strokeLineJoin="round"\n')


def stroke_path(d):
    return '    <path\n        %s        android:pathData="%s" />\n' % (STROKE, d)


def fill_path(d):
    return '    <path\n        android:fillColor="#FFFFFF"\n        android:pathData="%s" />\n' % d


def dot(cx, cy, r):
    return fill_path("M%g,%g m-%g,0 a%g,%g 0 1 0 %g,0 a%g,%g 0 1 0 -%g,0"
                     % (cx, cy, r, r, r, r * 2, r, r, r * 2))


def write(name, paths):
    body = HEAD + "".join(paths) + TAIL
    p = os.path.join(OUT, name + ".xml")
    with open(p, "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    print("wrote", name)


# 分类管理：标签
write("ic_set_category", [
    stroke_path("M4.2,11.4 V5.2 A1,1 0 0 1 5.2,4.2 H11.4 A1.2,1.2 0 0 1 12.25,4.55 "
                "L19.8,12.1 A1.2,1.2 0 0 1 19.8,13.8 L13.8,19.8 A1.2,1.2 0 0 1 12.1,19.8 "
                "L4.55,12.25 A1.2,1.2 0 0 1 4.2,11.4 Z"),
    dot(7.7, 7.7, 1.05),
])

# 账单显示效果：单条账单卡片
write("ic_set_bill_display", [
    stroke_path("M6,3.4 H18 A2.6,2.6 0 0 1 20.6,6 V18 A2.6,2.6 0 0 1 18,20.6 "
                "H6 A2.6,2.6 0 0 1 3.4,18 V6 A2.6,2.6 0 0 1 6,3.4 Z"),
    stroke_path("M7.4,8.6 H16.6"),
    stroke_path("M7.4,12.2 H13.6"),
    stroke_path("M7.4,15.8 H10.6"),
])

# 多币种与汇率：货币 + 兑换双向箭头
write("ic_set_currency", [
    stroke_path("M2.4,12 A6.4,6.4 0 1 1 15.2,12 A6.4,6.4 0 1 1 2.4,12 Z"),
    stroke_path("M6.2,9.4 L8.8,11.9 L11.4,9.4"),
    stroke_path("M8.8,11.9 V14.9"),
    stroke_path("M7,12.8 H10.6"),
    stroke_path("M16.4,9 H20.4"),
    stroke_path("M18.5,7.2 L20.5,9 L18.5,10.8"),
    stroke_path("M20.4,15 H16.4"),
    stroke_path("M18.3,13.2 L16.3,15 L18.3,16.8"),
])

# 快捷记账：闪电
write("ic_set_quick_entry", [
    stroke_path("M13.3,2.8 L5.5,13.5 H11.2 L10.4,21.2 L18.3,10.5 H12.6 Z"),
])

# 备份与恢复：归档箱 + 上传箭头
write("ic_set_backup", [
    stroke_path("M3.6,4.4 H20.4 V7.9 H3.6 Z"),
    stroke_path("M5.2,7.9 V19.5 A1.3,1.3 0 0 0 6.5,20.8 H17.5 A1.3,1.3 0 0 0 18.8,19.5 V7.9"),
    stroke_path("M12,18.1 V11.6"),
    stroke_path("M9.4,14.2 L12,11.6 L14.6,14.2"),
])

# 回收站：垃圾桶
write("ic_set_trash", [
    stroke_path("M3.8,6.9 H20.2"),
    stroke_path("M9.2,6.9 V5.3 A1.4,1.4 0 0 1 10.6,3.9 H13.4 A1.4,1.4 0 0 1 14.8,5.3 V6.9"),
    stroke_path("M6.2,6.9 L7.1,19.1 A1.7,1.7 0 0 0 8.8,20.7 H15.2 A1.7,1.7 0 0 0 16.9,19.1 L17.8,6.9"),
    stroke_path("M10.6,10.7 V16.8"),
    stroke_path("M13.4,10.7 V16.8"),
])

# 同步远程配置：循环箭头
write("ic_set_sync", [
    stroke_path("M4.4,12 A7.6,7.6 0 0 1 17.2,7.4"),
    stroke_path("M17.6,3.9 L17.2,7.4 L13.7,7.0"),
    stroke_path("M19.6,12 A7.6,7.6 0 0 1 6.8,16.6"),
    stroke_path("M6.4,20.1 L6.8,16.6 L10.3,17.0"),
])

# AI：芯片
write("ic_set_ai", [
    stroke_path("M6.2,4.4 H17.8 A1.8,1.8 0 0 1 19.6,6.2 V17.8 A1.8,1.8 0 0 1 17.8,19.6 "
                "H6.2 A1.8,1.8 0 0 1 4.4,17.8 V6.2 A1.8,1.8 0 0 1 6.2,4.4 Z"),
    stroke_path("M9,8.2 H15 A0.8,0.8 0 0 1 15.8,9 V15 A0.8,0.8 0 0 1 15,15.8 "
                "H9 A0.8,0.8 0 0 1 8.2,15 V9 A0.8,0.8 0 0 1 9,8.2 Z"),
    stroke_path("M10.6,2.6 V4.4"),
    stroke_path("M13.4,2.6 V4.4"),
    stroke_path("M10.6,19.6 V21.4"),
    stroke_path("M13.4,19.6 V21.4"),
    stroke_path("M2.6,10.6 H4.4"),
    stroke_path("M2.6,13.4 H4.4"),
    stroke_path("M19.6,10.6 H21.4"),
    stroke_path("M19.6,13.4 H21.4"),
])

# 存储清理：硬盘
write("ic_set_storage", [
    stroke_path("M5.6,3.6 H18.4 A2,2 0 0 1 20.4,5.6 V18.4 A2,2 0 0 1 18.4,20.4 "
                "H5.6 A2,2 0 0 1 3.6,18.4 V5.6 A2,2 0 0 1 5.6,3.6 Z"),
    stroke_path("M3.6,13.6 H20.4"),
    stroke_path("M6.8,17 H12.6"),
    dot(16.9, 17, 1),
])

# 桌面小组件：手机 + 网格
write("ic_set_widget", [
    stroke_path("M7.8,2.6 H16.2 A2.2,2.2 0 0 1 18.4,4.8 V19.2 A2.2,2.2 0 0 1 16.2,21.4 "
                "H7.8 A2.2,2.2 0 0 1 5.6,19.2 V4.8 A2.2,2.2 0 0 1 7.8,2.6 Z"),
    stroke_path("M8.6,7.4 H11.1 V9.9 H8.6 Z"),
    stroke_path("M12.9,7.4 H15.4 V9.9 H12.9 Z"),
    stroke_path("M8.6,11.4 H11.1 V13.9 H8.6 Z"),
    stroke_path("M12.9,11.4 H15.4 V13.9 H12.9 Z"),
    stroke_path("M10.3,18.2 H13.7"),
])

# 快捷手势：点击波纹
write("ic_set_gesture", [
    dot(12, 12, 2),
    stroke_path("M8.5,8.5 A4.95,4.95 0 0 0 8.5,15.5"),
    stroke_path("M15.5,8.5 A4.95,4.95 0 0 1 15.5,15.5"),
    stroke_path("M5.3,5.3 A9.4,9.4 0 0 0 5.3,18.7"),
    stroke_path("M18.7,5.3 A9.4,9.4 0 0 1 18.7,18.7"),
])

# 显示账本入口：翻开的书
write("ic_set_book", [
    stroke_path("M12,7.2 V20.6"),
    stroke_path("M12,7.2 C10.5,5.9 7.9,5.3 4.6,5.9 V19.3 C7.9,18.7 10.5,19.3 12,20.6"),
    stroke_path("M12,7.2 C13.5,5.9 16.1,5.3 19.4,5.9 V19.3 C16.1,18.7 13.5,19.3 12,20.6"),
])

# 资产功能：钱包
write("ic_set_asset", [
    stroke_path("M4.2,8.6 A2.2,2.2 0 0 1 6.4,6.4 H17.6 A2.2,2.2 0 0 1 19.8,8.6 "
                "V17.4 A2.2,2.2 0 0 1 17.6,19.6 H6.4 A2.2,2.2 0 0 1 4.2,17.4 Z"),
    stroke_path("M4.2,10 H19.8"),
    stroke_path("M19.8,12.6 H16.4 A1.9,1.9 0 0 0 16.4,16.4 H19.8"),
    dot(17.5, 14.5, 0.85),
])

# 趋势卡片：上升折线
write("ic_set_trend", [
    stroke_path("M4.4,3.8 V20.2 H20.2"),
    stroke_path("M7.2,16.2 L11,11.6 L14,14.2 L19,8.6"),
    stroke_path("M15.9,8.6 H19 V11.7"),
])

# Shizuku 模式：无线调试授权（手机 + 电波）
write("ic_set_shizuku_mode", [
    stroke_path("M6.4,3.2 H12.8 A1.6,1.6 0 0 1 14.4,4.8 V19.2 A1.6,1.6 0 0 1 12.8,20.8 "
                "H6.4 A1.6,1.6 0 0 1 4.8,19.2 V4.8 A1.6,1.6 0 0 1 6.4,3.2 Z"),
    stroke_path("M7.6,18 H11.6"),
    stroke_path("M17,9.4 A4.6,4.6 0 0 1 17,14.6"),
    stroke_path("M19.8,6.8 A8.4,8.4 0 0 1 19.8,17.2"),
])

# Shizuku 保活：盾牌 + 对勾
write("ic_set_shizuku_guard", [
    stroke_path("M12,2.8 L4.4,5.8 V11.4 C4.4,16.2 7.6,20.4 12,21.6 C16.4,20.4 19.6,16.2 19.6,11.4 V5.8 Z"),
    stroke_path("M8.8,11.7 L11.2,14.1 L15.3,9.9"),
])

# 开发者日志：终端窗口
write("ic_set_devlog", [
    stroke_path("M4.2,4.6 H19.8 A1.6,1.6 0 0 1 21.4,6.2 V17.8 A1.6,1.6 0 0 1 19.8,19.4 "
                "H4.2 A1.6,1.6 0 0 1 2.6,17.8 V6.2 A1.6,1.6 0 0 1 4.2,4.6 Z"),
    stroke_path("M2.6,8.3 H21.4"),
    stroke_path("M6.5,11.9 L8.9,14.1 L6.5,16.3"),
    stroke_path("M10.7,16.3 H14.5"),
])

# 弹窗通用：恢复（逆时针回转箭头）
# 圆弧圆心 (12,12) r=7，缺口开在正上方；箭头两翼按端点切线方向反推，保证对齐。
write("ic_dialog_restore", [
    stroke_path("M9.61,5.42 A7,7 0 1 0 14.39,5.42"),
    stroke_path("M16.56,8.23 L14.39,5.42 L17.86,4.66"),
])

# 顶部提醒：铃铛
write("ic_set_bell", [
    stroke_path("M5.4,17.4 H18.6 C17.2,16.1 16.5,14.3 16.5,12.1 V10.6 A4.5,4.5 0 0 0 7.5,10.6 "
                "V12.1 C7.5,14.3 6.8,16.1 5.4,17.4 Z"),
    stroke_path("M9.8,19.9 A2.3,2.3 0 0 0 14.2,19.9"),
])
