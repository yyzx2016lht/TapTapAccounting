package com.taostudio.tapaccounting.tap

/**
 * 敲击检测当前处于哪种状态。
 *
 * 存在的意义是让前台服务通知能如实显示"检测现在到底在干什么"——
 * 省电模式下档位会自己来回切，用户不看代码根本不知道当前是哪一档。
 */
enum class TapDetectionState {
    /** 检测器没有运行：息屏、锁屏、横屏屏蔽，或用户关掉了开关。 */
    Off,

    /** 省电待机：只跑启发式（不挂陀螺仪、不做 ML 推理）。敲一下只唤醒，不执行动作。 */
    HeuristicStandby,

    /** 精确检测窗口：启发式刚被敲醒，正在用 ML 识别。此窗口内双击才会真正记账。 */
    PreciseWindow,

    /**
     * 省电档测试模式：锁定启发式待机，敲中只给震动+提示，
     * 既不切精确档也不记账。用于快速试灵敏度。
     */
    HeuristicTest,

    /** 全程 ML：省电开关关闭，一直用 ML 识别。 */
    AlwaysMl,
}
