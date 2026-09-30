package com.taostudio.tapaccounting

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 内置图标资源覆盖度检查。
 *
 * 内置分类/资产图标由 `tools/sync-bundled-icons.py` 从 `res/raw` 下的 JSON 与源码里的
 * 图标 URL 下载到 `assets/icons/`，并生成 `icons/icon_map.json`。
 * 只要有人改了图标集却没重跑脚本，运行时就只会有部分图标能显示，
 * 因此这里在单元测试层面把漂移拦下来。
 */
class BundledIconCoverageTest {

    private val moduleRoot: File by lazy {
        listOf(File("."), File("app"), File(".."))
            .map { it.absoluteFile }
            .firstOrNull { File(it, "src/main/res/raw").isDirectory }
            ?: error("找不到 app 模块根目录，当前目录=${File(".").absolutePath}")
    }

    private val rawDir: File get() = File(moduleRoot, "src/main/res/raw")
    private val sourceDir: File get() = File(moduleRoot, "src/main/java")
    private val iconDir: File get() = File(moduleRoot, "src/main/assets/icons")
    private val mapFile: File get() = File(iconDir, "icon_map.json")

    // URL 里不可能出现空白、引号、反引号、圆括号、反斜杠或非 ASCII 字符。
    // 不排除它们，正则会把紧跟 URL 的中文注释一起吞进来——例如 CategoryIcons.kt 的 KDoc
    // 里写着「（`http://res3.qianjiapp.com/cateic_other.png`）。用户自建分类时……」，
    // 贪婪匹配会得到「…cateic_other.png`）。用户自建分类时…」这种查不到映射表的假 URL。
    private val urlCharClass = """[^\s"'`()\\\u0080-\uFFFF]"""
    private val urlPattern = Regex("""https?://$urlCharClass+""")
    private val iconPattern = Regex("""https?://res\d*\.qianjiapp\.com/$urlCharClass+""")

    /** res/raw 里所有以图片扩展名结尾的图标 URL。 */
    private fun rawIconUrls(): Set<String> {
        val urls = mutableSetOf<String>()
        rawDir.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { file ->
            urlPattern.findAll(file.readText(Charsets.UTF_8)).forEach { match ->
                val url = match.value
                if (url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".webp")) urls += url
            }
        }
        return urls
    }

    /** 源码里硬编码的图标 URL（例如 AssetIconDefaults 的默认图标）。 */
    private fun hardCodedIconUrls(): Set<String> {
        val urls = mutableSetOf<String>()
        sourceDir.walkTopDown()
            .filter { it.isFile && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
            .forEach { file ->
                iconPattern.findAll(file.readText(Charsets.UTF_8)).forEach { urls += it.value }
            }
        return urls
    }

    private fun iconMap(): Map<String, String> {
        val json = JSONObject(mapFile.readText(Charsets.UTF_8))
        val result = mutableMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result[key] = json.getString(key)
        }
        return result
    }

    @Test
    fun `图标映射表存在且非空`() {
        assertTrue("缺少 ${mapFile.path}，请运行 python tools/sync-bundled-icons.py", mapFile.isFile)
        assertTrue("图标映射表为空", iconMap().isNotEmpty())
    }

    @Test
    fun `raw json 与源码中的图标 URL 全部已打包`() {
        val map = iconMap()
        val expected = rawIconUrls() + hardCodedIconUrls()
        assertTrue("未解析到任何图标 URL，检查测试的工作目录", expected.size > 500)

        val missing = expected.filterNot { map.containsKey(it) }.sorted()
        assertTrue(
            "有 ${missing.size} 个图标未打包，请重跑 python tools/sync-bundled-icons.py\n" +
                missing.take(10).joinToString("\n"),
            missing.isEmpty()
        )
    }

    @Test
    fun `映射表指向的图标文件都存在且非空`() {
        val broken = iconMap().filter { (_, path) ->
            val file = File(moduleRoot, "src/main/assets/$path")
            !file.isFile || file.length() == 0L
        }
        assertTrue(
            "有 ${broken.size} 条映射指向缺失文件，请重跑 python tools/sync-bundled-icons.py\n" +
                broken.entries.take(10).joinToString("\n") { "${it.key} -> ${it.value}" },
            broken.isEmpty()
        )
    }
}
