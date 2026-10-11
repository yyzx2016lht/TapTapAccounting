package com.taostudio.tapaccounting

/**
 * 分类图标解析的统一兜底策略。
 *
 * 背景：钱迹的分类图标里，「其他」是一个三个点的占位图
 * （`http://res3.qianjiapp.com/cateic_other.png`）。用户自建分类时如果没挑图标，
 * App 会把这张占位图写进 `iconId`，于是界面上满屏「···」，既不美观也无法辨识。
 *
 * 这里的规则：凡是解析到 **占位图 / 空值** 的分类，一律改成按**分类名首字**
 * 实时生成的文字图标（见 [TextIconRenderer]）。用户主动选过真实图标的分类不受影响。
 *
 * 放在这里而不是各个界面，是为了让「数据库 → 分类树」这条主链路一次处理到位，
 * 分类管理、排序、记账表单、统计等所有读树的地方自动保持一致。
 */
object CategoryIcons {

    /** 内置的「其他」占位图，界面上表现为三个点。 */
    const val PLACEHOLDER_URL = "http://res3.qianjiapp.com/cateic_other.png"

    /**
     * 分类图标/结构的变更版本号。
     *
     * 各列表页把「分类名+类型 → 图标 URL」缓存在内存里（如 HomeAdapter.iconUrlCache）。
     * 分类改名会改变缓存键、自然失效，但**换图标不会**；这里由 CategoryRepository 在
     * 分类变更时递增版本号，显示层比对后清缓存，保证改完图标能实时刷出来。
     */
    @Volatile
    private var revisionCounter = 0

    val revision: Int get() = revisionCounter

    fun bumpRevision() {
        revisionCounter++
    }

    /**
     * 「其他 / 其它」没选手动图标时使用的四格图标。
     * 图形与添加分类编辑区的默认预览 `ic_profile_category` 相同，
     * 由 [SquareGridIcon] 按同一比例画出来，这样能跟其他分类图标一起染色。
     */
    const val OTHER_ICON = SquareGridIcon.URL

    private val OTHER_NAMES = setOf("其他", "其它")

    /**
     * 换图之后仍然是三个点的文件。
     * `cateic_other.png` 是「其他」，`cateic_richang2.png` 是「日常」，
     * 两者在图标表里都被换成了 Material Symbols 的 more-horiz。
     */
    private val PLACEHOLDER_FILE_NAMES = listOf(
        "cateic_other.png",
        "cateic_richang2.png",
    )

    /** 空图标，或换图后仍是三个点的占位图。文字图标不算占位。 */
    fun isPlaceholder(icon: String?): Boolean {
        if (icon.isNullOrBlank() || TextIconRenderer.isTextIcon(icon)) return icon.isNullOrBlank()
        val path = icon.substringBefore('?').substringBefore('#')
        return PLACEHOLDER_FILE_NAMES.any { name -> path.endsWith("/$name") || path == name }
    }

    /**
     * 空图标、三个点占位图、首字图标，以及系统自动写上的四格图标。
     * 这些都不是用户从图标库里挑的，改名后要重新推导。
     */
    fun isAutoAssigned(icon: String?): Boolean =
        icon.isNullOrBlank() ||
            icon == OTHER_ICON ||
            TextIconRenderer.isTextIcon(icon) ||
            isPlaceholder(icon)

    /**
     * 对单个分类的图标做兜底。
     *
     * @param icon 原始图标 URL/路径，可能为空
     * @param name 分类名，用于取首字
     * @param parentIcon 父分类图标，仅在分类名取不到字时使用
     */
    fun fallback(icon: String?, name: String, parentIcon: String = ""): String {
        val leaf = leafNameOf(name)
        // 四格图标只属于「其他」。分类改名后，把它当成没选图标，改走新名字的首字。
        val stored = if (icon == OTHER_ICON && leaf !in OTHER_NAMES) "" else icon
        if (leaf in OTHER_NAMES && isAutoAssigned(stored)) return OTHER_ICON

        // 已经是文字图标（用户新建时写入的 text://）直接沿用
        if (TextIconRenderer.isTextIcon(stored)) return stored.orEmpty()
        // 有效图标：非空且不是三个点占位图
        if (!isPlaceholder(stored)) return stored.orEmpty()

        // 先按自己名字取首字，不继承父分类图标
        TextIconRenderer.urlFor(leaf)?.let { return it }
        // 名字取不到字（如纯符号）时，退回父分类的真实图标
        if (!isPlaceholder(parentIcon) && !TextIconRenderer.isTextIcon(parentIcon)) return parentIcon
        return ""
    }

    /** 取「父 > 子」这类展示名里的最后一段，更贴合用户看到的分类名。 */
    fun leafNameOf(name: String): String {
        val parts = when {
            name.contains(" > ") -> name.split(" > ")
            name.contains(" - ") -> name.split(" - ")
            name.contains("/::/") -> name.split("/::/")
            name.contains("·") -> name.split("·")
            else -> return name.trim()
        }
        return parts.last().trim()
    }
}
