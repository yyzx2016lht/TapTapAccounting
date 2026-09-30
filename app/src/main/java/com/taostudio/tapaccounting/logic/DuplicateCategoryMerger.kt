package com.taostudio.tapaccounting.logic

import com.taostudio.tapaccounting.data.local.entity.Category

/**
 * 合并语义重复的分类。
 *
 * 起因：内置分类库里「其他」和「其它」是**同一个图标的两个条目**（都是 `cateic_other.png`），
 * 而初始种子只给「其它」。于是用户很容易同时拥有两个——语义完全相同，却各自挂着一批账单，
 * 统计页和预算里看起来像两个分类。
 *
 * 合并方向：保留别名组里**靠前**的那个。「其他」是《现代汉语词典》的推荐写法，也是
 * `category.json` 里的正式条目（「其它」是排在很后面的重复条目），所以保留「其他」。
 *
 * 这个类只做**决策**（[plan]），不碰数据库，便于单测；执行在
 * `MigrationManager.mergeDuplicateCategoriesIfNeeded`。
 */
object DuplicateCategoryMerger {

    /** 语义相同的写法。每组内保留第一个，其余并入。 */
    val ALIAS_GROUPS: List<List<String>> = listOf(
        listOf("其他", "其它"),
    )

    /** 一个待执行的合并：把 [sourceId] 并入 [targetId]。 */
    data class MergeAction(
        val sourceId: Long,
        val sourceName: String,
        val targetId: Long,
        val targetName: String,
        val type: Int
    )

    /** 为什么某个分类没有参与合并——这些是必须留痕的，否则用户会以为漏了。 */
    data class Skip(
        val name: String,
        val type: Int,
        val reason: String
    )

    data class Plan(
        val actions: List<MergeAction>,
        val skips: List<Skip>
    ) {
        val isEmpty: Boolean get() = actions.isEmpty()
    }

    /**
     * 算出该合并哪些分类。规则刻意保守：
     *
     * - 只在**同一个 type**（支出/收入）内配对——「其他」支出和「其它」收入是两个不同的分类，
     *   合并会改变账单的类型语义。
     * - 只处理**根分类**（`parentId == null`）。子分类的账单名带路径前缀（"父 - 子"），
     *   按名字迁移的语义完全不同。
     * - 被并入的那个若**有子分类**，跳过：删除它会连带删掉子分类（`CategoryRepository.deleteById`
     *   的行为），那属于用户自己的结构，不能替他决定。
     */
    fun plan(
        categories: List<Category>,
        aliasGroups: List<List<String>> = ALIAS_GROUPS
    ): Plan {
        val actions = mutableListOf<MergeAction>()
        val skips = mutableListOf<Skip>()

        aliasGroups.forEach group@{ group ->
            if (group.size < 2) return@group
            val rootOfType = categories.filter { it.parentId == null }
            val types = rootOfType.map { it.type }.distinct()

            types.forEach type@{ type ->
                // 组内按声明顺序取第一个存在的作为保留目标
                val present = group.mapNotNull { name ->
                    rootOfType.firstOrNull { it.type == type && it.name == name }
                }
                if (present.size < 2) return@type

                val target = present.first()
                present.drop(1).forEach { source ->
                    val hasChildren = categories.any { it.parentId == source.id }
                    if (hasChildren) {
                        skips.add(
                            Skip(
                                name = source.name,
                                type = type,
                                reason = "还有子分类，需要先自行处理"
                            )
                        )
                    } else {
                        actions.add(
                            MergeAction(
                                sourceId = source.id,
                                sourceName = source.name,
                                targetId = target.id,
                                targetName = target.name,
                                type = type
                            )
                        )
                    }
                }
            }
        }

        return Plan(actions = actions, skips = skips)
    }
}
