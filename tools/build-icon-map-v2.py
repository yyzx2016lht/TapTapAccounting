#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""分类图标映射 v2：Material Symbols 主库 + Tabler 兜底 + 首字图标兜底。

输入：docs/图标替换清单.csv 中的 A 类条目
输出：tools/icon-map-v2.json
    { 原文件名: {"type": "msymbols"|"tabler"|"text", "icon": "<图标名>", "char": "<首字>"} }

三级策略：
  1. MAP 精确命中 → 用指定库的指定图标
  2. TEXT 集合（乐器等无合适图标的）→ 透明底品牌色首字图标
  3. BRAND_SKIP（第三方品牌）→ 跳过不处理
"""

import csv
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CSV_PATH = os.path.join(ROOT, "docs", "图标替换清单.csv")
OUT_JSON = os.path.join(ROOT, "tools", "icon-map-v2.json")

M = "msymbols"
T = "tabler"
X = "text"

# ---------------------------------------------------------------------------
# 中文语义 -> (库, 图标名)。原则：一词条一图标，同义词才复用。
# ---------------------------------------------------------------------------
MAP = {
    # ---- 餐饮食品 ----
    "餐饮": (M, "dinner-dining"), "饮食": (M, "dinner-dining"),
    "早餐": (M, "breakfast-dining"), "午餐": (M, "lunch-dining"),
    "晚餐": (M, "dinner-dining"), "夜宵": (M, "ramen-dining"),
    "外卖": (M, "takeout-dining"), "外卖平台": (M, "takeout-dining"),
    "买菜": (M, "shopping-basket"), "蔬菜": (M, "grocery"),
    "水果": (M, "nutrition"), "海鲜": (M, "set-meal"),
    "肉类": (M, "kebab-dining"), "鸡蛋": (M, "egg"),
    "牛奶": (M, "local-drink"), "奶粉": (T, "baby-bottle"),
    "面包": (M, "bakery-dining"), "面食": (M, "ramen-dining"),
    "面粉": (M, "bakery-dining"), "柴米油盐": (M, "kitchen"),
    "豆制品": (T, "seedling"), "食品": (M, "fastfood"),
    "零食": (M, "cookie"), "糖果": (T, "candy"),
    "巧克力": (T, "candy"), "甜点": (M, "cake"),
    "蛋糕": (M, "cake"), "雪糕": (M, "icecream"),
    "汤圆": (M, "rice-bowl"), "饺子": (M, "rice-bowl"),
    "馒头": (M, "bakery-dining"), "汉堡": (M, "lunch-dining"),
    "火锅": (M, "soup-kitchen"), "烧烤": (M, "outdoor-grill"),
    "咖啡": (M, "local-cafe"), "奶茶": (M, "local-drink"),
    "茶": (M, "emoji-food-beverage"), "饮料": (M, "local-drink"),
    "矿泉水": (T, "bottle"), "酒类": (M, "liquor"),
    "酒吧": (M, "local-bar"),
    "零花钱": (M, "savings"), "生活费": (M, "currency-yuan"),

    # ---- 交通 ----
    "交通": (M, "commute"), "打车": (M, "local-taxi"),
    "拼车": (M, "airport-shuttle"), "公交": (M, "directions-bus"),
    "地铁": (M, "subway"), "磁悬浮": (M, "tram"),
    "火车": (M, "train"), "飞机": (M, "flight"),
    "轮船": (M, "directions-boat"), "自驾": (M, "directions-car"),
    "代驾": (T, "steering-wheel"), "汽车": (M, "directions-car"),
    "汽车加油": (M, "local-gas-station"), "汽车停车费": (M, "local-parking"),
    "汽车充电": (M, "ev-station"), "汽车罚款": (M, "receipt-long"),
    "共享单车": (M, "pedal-bike"), "自行车": (M, "pedal-bike"),
    "摩托车": (M, "two-wheeler"), "骑行": (M, "pedal-bike"),
    "养车": (M, "car-repair"), "汽车保险": (M, "car-crash"),

    # ---- 居住 ----
    "住房": (M, "home"), "房租": (M, "real-estate-agent"),
    "租金": (M, "real-estate-agent"), "房贷": (M, "house"),
    "物业费": (M, "apartment"), "水电": (M, "electric-bolt"),
    "水费": (M, "water-drop"), "电费": (M, "electric-bolt"),
    "水电煤": (M, "electric-bolt"), "燃气": (M, "gas-meter"),
    "取暖": (M, "fireplace"), "居家": (M, "weekend"),
    "家具": (M, "chair"), "家电": (M, "electrical-services"),
    "电器": (T, "plug"), "家居": (M, "chair"),
    "清洁": (M, "cleaning-services"), "清洁剂": (M, "soap"),
    "洗浴用品": (M, "shower"),

    # ---- 通讯网络 ----
    "网费": (M, "wifi"), "网络": (M, "router"),
    "电话": (M, "call"), "手机": (M, "smartphone"),
    "手机配件": (M, "cable"), "网络加速": (M, "speed"),
    "网络电视": (M, "live-tv"),

    # ---- 日用服饰 ----
    "日用": (M, "shopping-bag"), "日用品": (M, "shopping-bag"),
    "纸巾": (T, "toilet-paper"), "洗衣液": (M, "local-laundry-service"),
    "牙膏": (T, "dental"), "服饰": (M, "checkroom"),
    "服装": (M, "checkroom"), "服饰鞋包": (M, "checkroom"),
    "女装": (M, "checkroom"), "内衣": (M, "checkroom"),
    "文胸": (M, "checkroom"), "情趣内衣": (M, "checkroom"),
    "袜子": (T, "sock"), "秋裤": (M, "checkroom"),
    "羽绒服": (T, "jacket"), "外套": (T, "jacket"),
    "户外夹克": (T, "jacket"), "汉服": (M, "checkroom"),
    "球衣": (T, "shirt"), "裙子": (M, "checkroom"),
    "裤子": (M, "checkroom"), "鞋类": (T, "shoe"),
    "运动鞋": (T, "shoe"), "球鞋": (T, "shoe"),
    "平底鞋": (T, "shoe"), "高跟鞋": (T, "shoe"),
    "凉鞋": (T, "shoe"), "靴子": (T, "shoe"),
    "高筒靴": (T, "shoe"), "箱包": (M, "luggage"),
    "包包": (M, "business-center"), "手包": (T, "briefcase"),
    "帽子": (T, "cap-rounded"), "围巾": (T, "scarf"),
    "眼镜": (M, "eyeglasses"), "手表": (M, "watch"),
    "首饰": (M, "diamond"), "珠宝": (M, "diamond"),

    # ---- 美妆 ----
    "美妆": (M, "self-care"), "化妆品": (M, "self-care"),
    "美容": (M, "face-retouching-natural"), "护肤": (M, "sanitizer"),
    "护肤素": (M, "sanitizer"), "护手霜": (M, "sanitizer"),
    "身体乳": (T, "bottle"), "乳液": (T, "bottle"),
    "面霜": (T, "bottle"), "精华": (T, "bottle"),
    "眼霜": (T, "bottle"), "洗面奶": (T, "wash"),
    "卸妆油": (T, "bottle"), "防晒": (M, "wb-sunny"),
    "面膜": (T, "face-mask"), "洗脸巾": (T, "wash"),
    "化妆棉": (T, "brush"), "化妆水": (T, "bottle"),
    "化妆刷": (T, "brush"), "口红": (T, "color-swatch"),
    "唇彩": (T, "color-swatch"), "唇膏": (T, "color-swatch"),
    "眉笔": (T, "pencil"), "眼影": (M, "palette"),
    "眼线笔": (T, "pencil"), "睫毛膏": (M, "visibility"),
    "腮红": (M, "palette"), "粉底": (M, "sanitizer"),
    "粉底液": (M, "sanitizer"), "高光": (T, "sparkles"),
    "指甲油": (M, "front-hand"), "香水": (T, "spray"),
    "美甲": (M, "front-hand"), "美睫": (M, "visibility"),
    "美发": (M, "content-cut"), "理发": (M, "content-cut"),
    "桑拿": (M, "hot-tub"), "医美": (M, "syringe"),
    "水光针": (M, "syringe"), "粉刺针": (T, "needle"),

    # ---- 医疗健康 ----
    "医疗": (M, "medical-services"), "医保": (M, "health-and-safety"),
    "就诊": (M, "local-hospital"), "就诊卡": (M, "badge"),
    "挂号": (M, "local-hospital"), "药品": (M, "medication"),
    "保健": (M, "monitor-heart"), "保健品": (T, "pill"),
    "住院护理": (M, "king-bed"), "产检": (M, "pregnant-woman"),
    "核酸检测": (M, "coronavirus"), "疫苗": (M, "vaccines"),
    "健身": (M, "fitness-center"), "健身房": (M, "fitness-center"),
    "健身器材": (M, "fitness-center"), "瑜伽": (M, "self-improvement"),
    "游泳": (M, "pool"), "跑步": (M, "directions-run"),
    "篮球": (M, "sports-basketball"), "足球": (M, "sports-soccer"),
    "羽毛球": (M, "sports-tennis"), "网球": (M, "sports-tennis"),
    "乒乓球": (M, "sports-tennis"), "台球": (T, "ball-bowling"),
    "棒球": (M, "sports-baseball"), "高尔夫": (M, "golf-course"),
    "飞盘": (T, "disc"), "搏击": (M, "sports-mma"),
    "散打": (T, "karate"), "空手道": (T, "karate"),
    "跆拳道": (T, "karate"), "射箭": (T, "archery-arrow"),
    "攀岩": (T, "mountain"), "滑雪": (M, "downhill-skiing"),
    "滑冰": (M, "ice-skating"), "滑板": (M, "skateboarding"),
    "轮滑": (M, "roller-skating"), "冲浪": (M, "surfing"),
    "潜水": (M, "scuba-diving"), "漂流": (M, "kayaking"),
    "徒步": (M, "hiking"), "露营": (T, "tent"),
    "户外装备": (M, "backpack"),

    # ---- 教育办公 ----
    "教育": (M, "school"), "培训": (M, "school"),
    "早教": (M, "child-friendly"), "学习": (M, "menu-book"),
    "课程": (M, "play-lesson"), "考试": (M, "quiz"),
    "报名费": (M, "how-to-reg"), "书籍": (M, "book"),
    "文具": (M, "edit"), "复印": (M, "print"),
    "办公软件": (M, "apps"), "办公": (M, "work"),
    "工作": (M, "work"), "兼职": (M, "work"),
    "小程序": (M, "widgets"), "关键词": (M, "sell"),
    "工具": (M, "build"), "维修": (M, "handyman"),
    "中介": (M, "real-estate-agent"),

    # ---- 娱乐 ----
    "娱乐": (M, "nightlife"), "游戏": (M, "sports-esports"),
    "游戏主机": (M, "gamepad"), "游戏公司": (M, "sports-esports"),
    "游戏直播": (M, "sports-esports"), "电影": (M, "movie"),
    "影视": (M, "movie"), "影视平台": (M, "video-library"),
    "演出": (M, "theater-comedy"), "展览": (M, "museum"),
    "门票": (M, "confirmation-number"), "音乐": (M, "music-note"),
    "音乐平台": (M, "library-music"), "流媒体平台": (M, "video-library"),
    "视频平台": (M, "play-circle"), "直播平台": (M, "podcasts"),
    "K歌": (M, "mic"), "麻将": (M, "casino"),
    "扑克": (T, "cards"), "彩票": (M, "local-activity"),
    "盲盒": (M, "inventory-2"), "玩具": (M, "toys"),
    "手办": (M, "toys"), "乐器": (M, "piano"),
    "吉他": (T, "guitar-pick"), "钢琴": (M, "piano"),
    "书法": (M, "ink-pen"), "美术": (M, "palette"),
    "绘画": (M, "draw"), "舞蹈": (M, "accessibility-new"),
    "摄影": (M, "photo-camera"), "相机": (M, "photo-camera"),

    # ---- 育儿 ----
    "儿童": (M, "child-care"), "孩子": (M, "child-care"),
    "亲子游": (M, "family-restroom"), "育儿": (M, "child-care"),
    "奶瓶": (T, "baby-bottle"), "纸尿裤": (M, "baby-changing-station"),
    "月嫂": (T, "baby-carriage"),

    # ---- 宠物植物 ----
    "宠物": (M, "pets"), "狗粮": (T, "bone"),
    "猫粮": (T, "fish"), "狗玩具": (T, "bone"),
    "猫玩具": (T, "fish"), "猫砂": (T, "cat"),
    "宠物寄养": (M, "pets"), "宠物床": (M, "pets"),
    "宠物疫苗": (T, "vaccine"), "宠物罐头": (T, "bone"),
    "宠物药品": (M, "pets"), "宠物诊所": (T, "stethoscope"),
    "宠物饮水": (M, "pets"), "养鱼": (T, "fish"),
    "养鸟": (T, "feather"), "花卉": (M, "local-florist"),
    "绿植": (M, "eco"), "植物": (M, "eco"),

    # ---- 金融收支 ----
    "工资": (M, "payments"), "奖金": (M, "trophy"),
    "提成": (M, "percent"), "奖学金": (M, "workspace-premium"),
    "补贴": (M, "request-quote"), "红包": (M, "redeem"),
    "礼金": (M, "featured-seasonal-and-gifts"), "压岁钱": (M, "redeem"),
    "打赏": (M, "thumb-up"), "利息": (M, "trending-up"),
    "手续费": (M, "receipt-long"), "服务费": (M, "room-service"),
    "年费": (M, "event-repeat"), "税费": (M, "account-balance"),
    "关税": (M, "public"), "退税": (T, "receipt-refund"),
    "发票": (M, "receipt"), "投资": (M, "candlestick-chart"),
    "理财": (M, "pie-chart"), "基金": (M, "show-chart"),
    "股票": (M, "insert-chart"), "债券": (T, "certificate"),
    "黄金": (M, "toll"), "保险": (M, "verified-user"),
    "公积金": (M, "savings"), "积存": (M, "savings"),
    "现金": (T, "cash"), "外汇": (M, "currency-exchange"),
    "外币": (M, "currency-exchange"), "加密货币": (M, "currency-bitcoin"),
    "比特币": (M, "currency-bitcoin"), "纪念币": (T, "coin"),
    "纪念钞": (T, "coin"), "置换": (M, "swap-horiz"),
    "二手": (M, "recycling"), "转让": (M, "swap-horiz"),
    "退货": (M, "remove-shopping-cart"), "退款": (M, "money-off"),
    "报销": (M, "receipt"), "借入": (M, "call-received"),
    "借出": (M, "call-made"), "应收": (M, "download"),
    "应付": (M, "upload"), "押金": (M, "lock"),
    "贷款": (M, "credit-score"), "积分": (M, "stars"),
    "优惠券": (M, "loyalty"), "购物卡": (M, "credit-card"),
    "储值卡": (M, "credit-card"), "礼品卡": (M, "featured-seasonal-and-gifts"),
    "代金券": (M, "loyalty"), "会员卡": (M, "card-membership"),
    "银行卡": (M, "credit-card"),

    # ---- 人情社交 ----
    "人情": (M, "handshake"), "礼物": (M, "featured-seasonal-and-gifts"),
    "捐赠": (M, "volunteer-activism"), "慈善": (M, "volunteer-activism"),
    "祈福": (M, "temple-buddhist"), "聚会": (M, "celebration"),
    "朋友": (M, "group"), "家人": (M, "family-restroom"),
    "父母": (M, "elderly"), "恋爱": (M, "favorite"),
    "社交": (M, "groups"), "党费": (M, "flag"),
    "团费": (M, "flag"), "社保": (M, "contact-page"),

    # ---- 出行服务 ----
    "旅行": (M, "luggage"), "旅游": (M, "beach-access"),
    "酒店": (M, "hotel"), "签证": (M, "badge"),
    "护照": (M, "badge"), "快递": (M, "local-shipping"),
    "快递物流": (M, "local-shipping"), "货拉拉": (M, "moving"),
    "邮寄": (M, "mail"), "搬运": (M, "moving"),
    "人工费": (M, "engineering"), "律师费": (M, "gavel"),
    "中介费": (M, "real-estate-agent"), "会员": (M, "card-membership"),
    "订阅": (M, "autorenew"), "年费会员": (M, "card-membership"),

    # ---- 其他 ----
    "其他": (M, "more-horiz"), "其它": (M, "more-horiz"),
    "经营": (M, "storefront"), "烟具": (M, "smoking-rooms"),
    "烟草": (M, "smoking-rooms"), "香烟": (M, "smoking-rooms"),
    "卫生巾": (M, "female"), "卫生棉条": (M, "female"),
    "避孕套": (M, "shield"), "安全套": (M, "shield"),
    "防疫": (M, "medical-mask"), "口罩": (M, "medical-mask"),
    "收藏": (M, "bookmark"), "纹身": (T, "needle"),
    "殡葬": (M, "candle"), "纪念品": (M, "stars"),
    "营养品": (T, "pill"), "购物": (M, "shopping-cart"),
    "电商": (M, "storefront"), "便利店": (M, "local-convenience-store"),
    "超市": (M, "grocery"), "药店": (M, "local-pharmacy"),
    "搬家": (M, "moving"), "按摩": (M, "spa"),
    "足疗": (M, "spa"), "应酬": (M, "liquor"),
    "赡养": (M, "elderly"), "退休金": (M, "savings"),
    "养老金": (M, "savings"), "婚礼": (M, "celebration"),
    "份子钱": (M, "redeem"), "学费": (M, "school"),
    "话费": (M, "call"), "物业费": (M, "apartment"),
    "房租": (M, "real-estate-agent"), "维修": (M, "handyman"),
    "家政": (M, "cleaning-services"), "足疗": (M, "spa"),
    "KTV": (M, "mic"), "夜店": (M, "nightlife"),
    "桌游": (M, "casino"), "电竞": (M, "sports-esports"),
    "网游": (M, "sports-esports"), "潮玩": (M, "toys"),
    "文玩": (M, "diamond"), "茶叶": (M, "emoji-food-beverage"),
    "烟酒": (M, "liquor"), "礼品": (M, "featured-seasonal-and-gifts"),
    "鲜花": (M, "local-florist"), "节日": (M, "celebration"),
    "生日": (M, "cake"), "纪念日": (M, "event"),
    "年终奖": (M, "trophy"), "副业": (M, "work"),
    "数码配件": (M, "cable"), "软件": (M, "apps"),
    "云服务": (M, "cloud"), "存储": (M, "cloud"),
    "服务器": (M, "cloud"), "域名": (M, "public"),
    "VPN": (M, "vpn-key"), "加速器": (M, "speed"),
    "视频会员": (M, "subscriptions"), "音乐会员": (M, "library-music"),
    "阅读": (M, "auto-stories"), "小说": (M, "auto-stories"),
    "漫画": (M, "auto-stories"), "听书": (M, "headphones"),
    "知识付费": (M, "school"), "问答": (M, "quiz"),
    "演唱会": (M, "mic"), "国外": (M, "flight-takeoff"),
    "留学": (M, "school"), "出国留学": (M, "flight-takeoff"),
    "出国": (M, "flight-takeoff"),
    "医院": (M, "local-hospital"), "献血": (M, "bloodtype"),
    "体检": (M, "monitor-heart"), "药店": (M, "local-pharmacy"),
    "洗车": (M, "local-car-wash"), "高铁": (M, "train"),
    "数码": (M, "devices"), "电脑": (M, "computer"),
    "耳机": (M, "headphones"), "键盘": (M, "keyboard"),
    "充电宝": (M, "battery-charging-full"), "镜子": (T, "glass"),
    "梳妆台": (T, "glass"), "美容仪": (M, "face-retouching-natural"),
    "水": (M, "water-drop"), "泳衣": (M, "pool"),
    "洗发露": (T, "bottle"), "日常": (M, "more-horiz"),
    "轻客": (M, "airport-shuttle"), "运动": (M, "directions-run"),
    "钓鱼": (T, "fish-hook"), "餐具": (M, "dinner-dining"),
    "厨房": (M, "kitchen"), "冰箱": (M, "kitchen"),
    "取暖器": (M, "fireplace"), "宽带": (M, "router"),
    "流量": (M, "cable"),
    "丝袜": (T, "sock"), "电商平台": (M, "storefront"),
}

# ---- 乐器类 & 无合适图标 → 首字图标 ----
TEXT_ONLY = {
    "小提琴", "大提琴", "琵琶", "古筝", "二胡", "笛子", "长笛",
    "萨克斯", "小号", "长号", "手风琴", "口琴", "竖琴", "阮", "打鼓",
}

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


def first_char(name):
    """取中文名第一个有效字符做文字图标。"""
    for ch in name:
        if "一" <= ch <= "鿿":
            return ch
    return name[0] if name else "?"


def main():
    rows = list(csv.DictReader(io.open(CSV_PATH, encoding="utf-8-sig")))
    a_rows = [r for r in rows if r["类别"] == "A"]

    out = {}
    stats = {M: 0, T: 0, X: 0, "brand": 0, "missing": 0}
    missing = []

    for r in a_rows:
        fname = r["文件名"]
        first = r["中文名"].split(" / ")[0].strip()
        if first in BRAND_SKIP:
            stats["brand"] += 1
            continue
        if first in TEXT_ONLY:
            out[fname] = {"type": X, "icon": None, "char": first_char(first), "name": first}
            stats[X] += 1
            continue
        hit = MAP.get(first)
        if hit:
            lib, icon = hit
            out[fname] = {"type": lib, "icon": icon, "char": first_char(first), "name": first}
            stats[lib] += 1
        else:
            missing.append((fname, first))
            stats["missing"] += 1

    io.open(OUT_JSON, "w", encoding="utf-8").write(
        json.dumps(out, ensure_ascii=False, indent=1))

    print("A 类总计 %d" % len(a_rows))
    print("  MS 图标   %d" % stats[M])
    print("  Tabler    %d" % stats[T])
    print("  首字图标  %d" % stats[X])
    print("  品牌跳过  %d" % stats["brand"])
    print("  未映射    %d" % stats["missing"])
    for f, n in missing:
        print("    %-32s %s" % (f, n))
    return 0


if __name__ == "__main__":
    sys.exit(main())
