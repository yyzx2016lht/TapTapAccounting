#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成回收站弹窗所需的背景 drawable。"""

import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "app", "src", "main", "res", "drawable")


def write(name, body):
    with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8", newline="\n") as f:
        f.write(body)
    print("wrote", name)


def ripple(name, solid, radius, stroke=None, ripple_color="#1A000000"):
    stroke_xml = ""
    if stroke:
        stroke_xml = '\n            <stroke android:width="%s" android:color="%s" />' % stroke
    write(name, '''<?xml version="1.0" encoding="utf-8"?>
<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="%s">
    <item>
        <shape android:shape="rectangle">
            <solid android:color="%s" />%s
            <corners android:radius="%s" />
        </shape>
    </item>
</ripple>
''' % (ripple_color, solid, stroke_xml, radius))


def circle(name, color):
    write(name, '''<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="oval">
    <solid android:color="%s" />
</shape>
''' % color)


# 弹窗顶部图标底圈
circle("bg_dialog_icon_badge_brand", "#EDEFFA")
circle("bg_dialog_icon_badge_danger", "#FFF1F0")

# 弹窗主按钮（品牌靛蓝）
ripple("bg_dialog_action_brand", "#5C6BC0", "14dp", ripple_color="#33FFFFFF")

# 弹窗危险按钮（描边式，不用实心红）
ripple("bg_dialog_action_danger_outline", "#FFFFFF", "14dp", stroke=("1.2dp", "#F0C6CB"))

# 弹窗取消按钮（浅灰，带描边保证可见）
ripple("bg_dialog_action_cancel", "#F2F4F8", "14dp")

# 账单摘要卡
ripple("bg_trash_action_bill_card", "#F6F7F9", "14dp", ripple_color="#00000000")
