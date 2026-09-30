#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 A 类（分类库）图标的中文语义映射到 Tabler 图标名。

输入：docs/图标替换清单.csv 中的 A 类条目
输出：tools/icon-map-tabler.json  { 原文件名: tabler图标名 }
      docs/图标映射结果.md       人工复核用

未命中或需人工确认的条目不写入映射，留待后续处理。
"""

import csv
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "docs", "图标替换清单.csv")
OUT_JSON = os.path.join(ROOT, "tools", "icon-map-tabler.json")

# ---------------------------------------------------------------------------
# 中文语义 -> Tabler 图标名
# 优先精确语义；一个中文词只映射到一个 Tabler 图标（同义词复用同一图标）
# ---------------------------------------------------------------------------
MAP = {
    # ---- 餐饮 / 食品 ----
    "餐饮": "tools-kitchen-2", "饮食": "tools-kitchen-2",
    "早餐": "egg-fried", "午餐": "tools-kitchen-2", "晚餐": "tools-kitchen-2",
    "夜宵": "tools-kitchen-2", "外卖": "tools-kitchen-2", "外卖平台": "tools-kitchen-2",
    "买菜": "carrot", "蔬菜": "carrot", "水果": "apple", "海鲜": "fish",
    "肉类": "meat", "鸡蛋": "egg", "牛奶": "milk", "奶粉": "baby-bottle",
    "面包": "bread", "面食": "bread", "面粉": "bread",
    "柴米油盐": "salt", "豆制品": "seedling", "食品": "tools-kitchen-2",
    "零食": "ice-cream", "糖果": "candy", "巧克力": "candy",
    "甜点": "cake", "蛋糕": "cake", "雪糕": "ice-cream", "汤圆": "circle-dot",
    "饺子": "circle-dot", "馒头": "bread", "汉堡": "burger", "火锅": "flame",
    "烧烤": "grill", "咖啡": "coffee", "奶茶": "cup", "茶": "cup",
    "饮料": "bottle", "矿泉水": "bottle", "酒类": "beer",
    "零花钱": "coin", "生活费": "coin",

    # ---- 交通 ----
    "交通": "car", "打车": "steering-wheel", "拼车": "users-group",
    "公交": "bus", "地铁": "train", "磁悬浮": "train", "火车": "train",
    "飞机": "plane", "轮船": "ship", "自驾": "car", "代驾": "steering-wheel",
    "汽车": "car", "汽车加油": "gas-station", "汽车停车费": "parking",
    "汽车充电": "charging-pile", "汽车罚款": "ticket",
    "共享单车": "bike", "自行车": "bike", "摩托车": "motorbike",
    "骑行": "bike", "养车": "car", "汽车保险": "shield-check",

    # ---- 居住 ----
    "住房": "home", "房租": "home", "租金": "home",
    "房贷": "building-bank", "物业费": "building-community",
    "水电": "bolt", "水费": "droplet", "电费": "bolt",
    "水电煤": "bolt", "燃气": "flame", "取暖": "flame",
    "居家": "home", "家具": "sofa", "家电": "device-tv",
    "电器": "plug", "家居": "sofa", "清洁": "wash",
    "清洁剂": "spray", "洗浴用品": "bath",

    # ---- 通讯 / 网络 ----
    "网费": "wifi", "网络": "wifi", "电话": "phone",
    "手机": "device-mobile", "手机配件": "device-mobile",
    "网络加速": "network", "网络电视": "device-tv",
    "云存储": "cloud",

    # ---- 日用 / 服饰 ----
    "日用": "shopping-bag", "日用品": "shopping-bag",
    "纸巾": "toilet-paper", "洗衣液": "wash-machine",
    "牙膏": "dental", "牙刷": "dental",
    "服饰": "shirt", "服装": "shirt", "服饰鞋包": "shirt",
    "女装": "shirt", "男装": "shirt", "内衣": "shirt",
    "文胸": "shirt", "情趣内衣": "shirt", "袜子": "sock",
    "秋裤": "shirt", "羽绒服": "jacket", "外套": "jacket",
    "户外夹克": "jacket", "汉服": "shirt", "球衣": "shirt",
    "裙子": "shirt", "裤子": "shirt",
    "鞋类": "shoe", "运动鞋": "shoe", "球鞋": "shoe",
    "平底鞋": "shoe", "高跟鞋": "shoe", "凉鞋": "shoe",
    "靴子": "shoe", "高筒靴": "shoe",
    "箱包": "briefcase", "包包": "briefcase", "手包": "briefcase",
    "帽子": "cap-rounded", "围巾": "scarf",
    "眼镜": "eyeglass", "手表": "clock",
    "首饰": "diamond", "珠宝": "diamond",

    # ---- 美妆 ----
    "美妆": "brush", "化妆品": "brush", "美容": "sparkles",
    "护肤": "bottle", "护肤素": "bottle", "护手霜": "bottle",
    "身体乳": "bottle", "乳液": "bottle", "面霜": "bottle",
    "精华": "bottle", "眼霜": "bottle",
    "洗面奶": "wash", "卸妆油": "bottle", "防晒": "sun-high",
    "面膜": "face-mask", "洗脸巾": "wash", "化妆棉": "brush",
    "化妆水": "bottle", "化妆刷": "brush", "美妆蛋": "brush",
    "口红": "color-swatch", "唇彩": "color-swatch", "唇膏": "color-swatch",
    "眉笔": "pencil", "眼影": "palette", "眼线笔": "pencil",
    "睫毛膏": "brush", "腮红": "palette", "粉底": "brush",
    "粉底液": "bottle", "高光": "sparkles", "指甲油": "bottle",
    "香水": "spray", "美甲": "hand-finger", "美睫": "eye",
    "美发": "scissors", "理发": "scissors", "桑拿": "steam",
    "医美": "sparkles", "水光针": "needle", "粉刺针": "needle",

    # ---- 医疗健康 ----
    "医疗": "stethoscope", "医保": "stethoscope", "就诊": "stethoscope",
    "就诊卡": "id", "挂号": "clipboard-plus", "药品": "pill",
    "保健": "heart-rate-monitor", "保健品": "pill",
    "住院护理": "bed", "产检": "baby-carriage",
    "核酸检测": "test-pipe", "疫苗": "vaccine",
    "健身": "barbell", "健身房": "barbell", "健身器材": "barbell",
    "瑜伽": "yoga", "游泳": "swimming", "跑步": "run",
    "球类": "ball-football", "篮球": "ball-basketball",
    "足球": "ball-football", "羽毛球": "ball-tennis",
    "网球": "ball-tennis", "乒乓球": "ball-tennis",
    "台球": "ball-bowling", "棒球": "ball-baseball",
    "高尔夫": "golf", "飞盘": "disc",
    "搏击": "karate", "散打": "karate", "空手道": "karate",
    "跆拳道": "karate", "射箭": "archery-arrow",
    "攀岩": "mountain", "滑雪": "snowboarding", "滑冰": "skateboard",
    "滑板": "skateboard", "轮滑": "skateboard", "冲浪": "wave-sine",
    "潜水": "swimming", "漂流": "sailboat", "徒步": "walk",
    "露营": "tent", "户外装备": "backpack",

    # ---- 教育 / 办公 ----
    "教育": "school", "培训": "school", "早教": "baby-carriage",
    "学习": "book", "课程": "book", "考试": "certificate",
    "报名费": "certificate", "书籍": "book", "书籍杂志": "book",
    "文具": "pencil", "复印": "printer", "办公软件": "apps",
    "办公": "briefcase", "工作": "briefcase", "兼职": "briefcase",
    "小程序": "apps", "关键词": "key", "工具": "tool",
    "维修": "tool", "中介": "users-group",

    # ---- 娱乐 ----
    "娱乐": "device-gamepad-2", "游戏": "device-gamepad-2",
    "游戏主机": "device-gamepad-2", "游戏公司": "device-gamepad-2",
    "游戏直播": "device-gamepad-2", "电影": "movie",
    "影视": "movie", "影视平台": "movie", "演出": "ticket",
    "展览": "building-store", "门票": "ticket",
    "音乐": "music", "音乐平台": "music", "流媒体平台": "device-tv",
    "视频平台": "device-tv", "直播平台": "device-tv",
    "K歌": "microphone-2", "麻将": "dice", "扑克": "cards",
    "彩票": "ticket", "盲盒": "gift", "盲盒手办": "gift",
    "手办": "gift", "玩具": "horse-toy", "宠物玩具": "horse-toy",
    "乐器": "music", "吉他": "guitar-pick", "钢琴": "piano",
    "小提琴": "violin", "大提琴": "violin", "琵琶": "music",
    "古筝": "music", "二胡": "music", "笛子": "music",
    "长笛": "music", "萨克斯": "music", "小号": "music",
    "长号": "music", "手风琴": "music", "口琴": "music",
    "竖琴": "music", "阮": "music", "打鼓": "music",
    "小提琴": "guitar-pick", "大提琴": "guitar-pick",
    "书法": "brush", "美术": "palette", "绘画": "palette",
    "舞蹈": "walk", "摄影": "camera", "相机": "camera",

    # ---- 育儿 ----
    "儿童": "baby-carriage", "孩子": "baby-carriage",
    "亲子游": "baby-carriage", "早教": "baby-carriage",
    "奶瓶": "baby-bottle", "纸尿裤": "baby-carriage",
    "月嫂": "baby-carriage", "育儿": "baby-carriage",

    # ---- 宠物 ----
    "宠物": "paw", "狗粮": "bone", "猫粮": "fish",
    "狗玩具": "bone", "猫玩具": "fish", "猫砂": "cat",
    "宠物寄养": "paw", "宠物床": "paw", "宠物疫苗": "vaccine",
    "宠物罐头": "bone", "宠物药品": "pill", "宠物诊所": "stethoscope",
    "宠物饮水": "droplet", "养鱼": "fish", "养鸟": "feather",
    "花卉": "plant-2", "绿植": "plant-2", "植物": "plant-2",

    # ---- 金融 / 收支 ----
    "工资": "cash", "奖金": "award", "提成": "cash",
    "奖学金": "award", "补贴": "cash-plus", "红包": "gift",
    "礼金": "gift", "压岁钱": "gift", "打赏": "heart",
    "利息": "percentage", "手续费": "receipt", "服务费": "receipt",
    "年费": "receipt", "税费": "receipt", "关税": "receipt",
    "退税": "receipt-refund", "发票": "receipt",
    "投资": "chart-line", "理财": "chart-line",
    "基金": "chart-line", "股票": "chart-line",
    "债券": "certificate", "黄金": "coin",
    "保险": "shield-check", "房贷保险": "shield-check",
    "公积金": "pig-money", "积存": "pig-money",
    "现金": "cash", "外汇": "currency-dollar",
    "外币": "currency-dollar", "加密货币": "currency-bitcoin",
    "比特币": "currency-bitcoin", "纪念币": "coin",
    "纪念钞": "cash", "置换": "arrows-exchange",
    "退货": "receipt-refund", "退款": "receipt-refund",
    "报销": "receipt",
    "借入": "arrow-down-left", "借出": "arrow-up-right",
    "应收": "arrow-down-left", "应付": "arrow-up-right",
    "押金": "lock", "贷款": "building-bank",

    # ---- 人情 / 社交 ----
    "人情": "gift", "礼物": "gift", "捐赠": "heart-handshake",
    "祈福": "flame", "聚会": "users-group", "朋友": "users-group",
    "家人": "users-group", "父母": "users-group", "恋爱": "heart",
    "宠物社交": "paw", "慈善": "heart-handshake",

    # ---- 出行 / 服务 ----
    "旅行": "plane", "旅游": "plane", "酒店": "bed",
    "签证": "id-badge", "快递": "package",
    "快递物流": "package", "货拉拉": "truck",
    "邮寄": "mail", "搬运": "truck",
    "服务费": "receipt", "人工费": "users-group",
    "律师费": "gavel", "中介费": "users-group",
    "会员": "crown", "会员服务": "crown",
    "订阅": "repeat", "年费会员": "crown",

    # ---- 其他 ----
    "其他": "dots", "其它": "dots", "经营": "briefcase",
    "烟具": "smoking", "烟草": "smoking", "香烟": "smoking",
    "卫生巾": "heart", "卫生棉条": "heart",
    "避孕套": "heart", "安全套": "heart",
    "防疫": "face-mask", "口罩": "face-mask",
    "发票报销": "receipt", "党费": "flag",
    "团费": "flag", "社保": "id",
    "宠物": "paw", "购物": "shopping-cart",
    "电商": "shopping-cart", "购物卡": "credit-card",
    "积分": "stars", "优惠券": "ticket",
    "纪念品": "gift", "收藏": "diamond",
    "纹身": "needle", "殡葬": "flame",
    "保健品": "pill", "营养品": "pill",

    # ---- 补充（第一轮未映射） ----
    "丝袜": "shoe", "二手": "arrows-exchange", "转让": "arrows-exchange",
    "充电宝": "battery-charging", "数码": "devices",
    "日常": "dots", "梳妆台": "glass",
    "水": "droplet", "泳衣": "swimming", "洗发露": "bottle",
    "电商平台": "shopping-cart",
    "电脑": "device-laptop", "美容仪": "sparkles",
    "耳机": "headphones", "轻客": "bus",
    "运动": "run", "钓鱼": "fish-hook",
    "键盘": "keyboard", "镜子": "glass",
    "问答": "message-question", "餐具": "tools-kitchen-2",
    "宠物": "paw", "购物": "shopping-cart",
}

# 属于第三方品牌、不应替换的（虽然被归入 A 类，但实为品牌）
BRAND_SKIP = {
    "华伦天奴", "卡尔文克莱恩", "古驰", "巴宝莉", "普拉达", "李宁", "安踏",
    "斐乐", "纪梵希", "耐克", "纽巴伦", "蔻驰", "路易威登", "阿玛尼",
    "阿迪达斯", "香奈儿", "奢侈品品牌", "天猫", "唯品会", "当当",
    "掌阅", "晋江文学城", "快看漫画", "懒人听书", "酷我音乐",
    "百度网盘", "阿里云盘", "盒马鲜生", "网易严选", "网易考拉",
    "腾讯云", "腾讯体育", "知乎会员", "微博会员", "起点读书",
    "阿里巴巴", "京东", "淘宝", "拼多多", "美团", "饿了么",
    "微信", "支付宝", "抖音", "快手", "哔哩哔哩", "爱奇艺",
    "优酷", "腾讯视频", "网易云音乐", "QQ音乐", "喜马拉雅",
    "小红书", "闲鱼", "滴滴", "高德", "百度", "谷歌", "微软",
    "会员服务", "云存储", "小程序",
}


def main():
    rows = list(csv.DictReader(io.open(CSV_PATH, encoding="utf-8-sig")))
    a_rows = [r for r in rows if r["类别"] == "A"]

    mapping = {}
    hit, brand, missing = [], [], []

    for r in a_rows:
        fname = r["文件名"]
        first = r["中文名"].split(" / ")[0].strip()
        if first in BRAND_SKIP:
            brand.append((fname, first))
            continue
        tabler = MAP.get(first)
        if tabler:
            mapping[fname] = tabler
            hit.append((fname, first, tabler))
        else:
            missing.append((fname, first))

    io.open(OUT_JSON, "w", encoding="utf-8").write(
        json.dumps(mapping, ensure_ascii=False, indent=1))

    print("A 类总计 %d" % len(a_rows))
    print("  已映射   %d" % len(hit))
    print("  品牌跳过 %d" % len(brand))
    print("  未映射   %d" % len(missing))
    print()
    if missing:
        print("未映射条目（需补充规则）：")
        for f, n in missing:
            print("   %-34s %s" % (f, n))

    return 0


if __name__ == "__main__":
    sys.exit(main())
