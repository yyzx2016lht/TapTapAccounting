package com.taostudio.tapaccounting.data.local

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * 用 JVM SQLite 执行 [CAPTURE_MIGRATION_41_42_SQL]，再和 Room 导出的 42.json 逐列/逐索引比对。
 *
 * 之所以不用 `MigrationTestHelper`：设备不一定常连；这个测试不需要 Android，
 * 能直接在 `:app:testDebugUnitTest` 里拦住“SQL 少列/类型错/索引名错/唯一性错”的问题。
 *
 * 真正的 v41 -> v42 整库迁移仍建议在真机上用旧 APK 造数据覆盖安装验证一次。
 */
class CaptureSchemaMigrationTest {

    private val captureTables = listOf(
        "payment_captures",
        "capture_events",
        "asset_mapping_rules",
        "category_rules",
        "rule_learning_tasks",
    )

    @Test
    fun migrationSqlMatchesRoomSchema() {
        val schemaFile = File(
            "schemas/com.taostudio.tapaccounting.data.local.AppDatabase/42.json",
        )
        assertTrue("找不到 Room 导出的 42.json：${schemaFile.absolutePath}", schemaFile.isFile)

        val database = JSONObject(schemaFile.readText()).getJSONObject("database")
        val entities = database.getJSONArray("entities")
        val entitiesByTable = buildMap {
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                put(entity.getString("tableName"), entity)
            }
        }

        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            connection.createStatement().use { statement ->
                CAPTURE_MIGRATION_41_42_SQL.forEach { statement.execute(it) }
            }

            captureTables.forEach { table ->
                val entity = entitiesByTable[table]
                assertNotNull("42.json 里没有 $table 的 Entity", entity)
                assertColumns(connection, table, entity!!)
                assertPrimaryKey(connection, table, entity)
                assertIndices(connection, table, entity)
            }
        }
    }

    private fun assertColumns(connection: Connection, table: String, entity: JSONObject) {
        val expected = linkedMapOf<String, JSONObject>()
        val fields = entity.getJSONArray("fields")
        for (index in 0 until fields.length()) {
            val field = fields.getJSONObject(index)
            expected[field.getString("columnName")] = field
        }

        val actual = linkedMapOf<String, PragmaColumn>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.next()) {
                    val name = cursor.getString("name")
                    actual[name] = PragmaColumn(
                        type = cursor.getString("type").orEmpty().uppercase(),
                        notNull = cursor.getInt("notnull") != 0,
                        defaultValue = cursor.getString("dflt_value"),
                        primaryKey = cursor.getInt("pk") != 0,
                    )
                }
            }
        }

        assertEquals(
            "$table 列集合不一致（缺少/多出字段）",
            expected.keys.toSet(),
            actual.keys.toSet(),
        )

        expected.forEach { (column, field) ->
            val actualColumn = actual.getValue(column)
            assertEquals(
                "$table.$column 类型不一致",
                field.getString("affinity").uppercase(),
                actualColumn.type,
            )
            assertEquals(
                "$table.$column notNull 不一致",
                field.optBoolean("notNull", false),
                actualColumn.notNull,
            )
            val expectedDefault = if (field.has("defaultValue")) {
                field.get("defaultValue").toString()
            } else {
                null
            }
            assertEquals(
                "$table.$column 默认值不一致",
                expectedDefault,
                actualColumn.defaultValue,
            )
            val expectedPrimary = entity.getJSONObject("primaryKey")
                .getJSONArray("columnNames")
                .toString()
                .contains("\"$column\"")
            assertEquals(
                "$table.$column 主键标记不一致",
                expectedPrimary,
                actualColumn.primaryKey,
            )
        }

        // autoGenerate 不是 PRAGMA 字段，用建表 SQL 里的 AUTOINCREMENT 兜底校验。
        val autoGenerate = entity.getJSONObject("primaryKey").optBoolean("autoGenerate", false)
        val createSql = tableCreateSql(connection, table)
        assertEquals(
            "$table autoGenerate 不一致",
            autoGenerate,
            createSql.contains("AUTOINCREMENT", ignoreCase = true),
        )
    }

    private fun assertPrimaryKey(connection: Connection, table: String, entity: JSONObject) {
        val expected = entity.getJSONObject("primaryKey").getJSONArray("columnNames")
        val actual = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info(`$table`)").use { cursor ->
                while (cursor.next()) {
                    if (cursor.getInt("pk") > 0) actual.add(cursor.getString("name"))
                }
            }
        }
        assertEquals("$table 主键列不一致", jsonArrayToStringList(expected), actual)
    }

    private fun assertIndices(connection: Connection, table: String, entity: JSONObject) {
        val expected = linkedMapOf<String, Pair<Boolean, List<String>>>()
        val indices = entity.optJSONArray("indices") ?: JSONArray()
        for (index in 0 until indices.length()) {
            val item = indices.getJSONObject(index)
            expected[item.getString("name")] = item.getBoolean("unique") to
                jsonArrayToStringList(item.getJSONArray("columnNames"))
        }

        val actual = linkedMapOf<String, Pair<Boolean, List<String>>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list(`$table`)").use { cursor ->
                while (cursor.next()) {
                    val name = cursor.getString("name")
                    // 忽略 SQLite 为 UNIQUE 约束自动建的 sqlite_autoindex_*；我们的 Entity 都用显式索引。
                    if (!name.startsWith("index_")) continue
                    val unique = cursor.getInt("unique") != 0
                    val columns = mutableListOf<String>()
                    connection.createStatement().use { indexStatement ->
                        indexStatement.executeQuery("PRAGMA index_info(`$name`)").use { indexCursor ->
                            while (indexCursor.next()) {
                                columns.add(indexCursor.getString("name"))
                            }
                        }
                    }
                    actual[name] = unique to columns
                }
            }
        }

        assertEquals("$table 索引集合不一致", expected.keys, actual.keys)
        expected.forEach { (name, expectedValue) ->
            assertEquals("$table 索引 $name 唯一性不一致", expectedValue.first, actual.getValue(name).first)
            assertEquals(
                "$table 索引 $name 列顺序不一致",
                expectedValue.second,
                actual.getValue(name).second,
            )
        }
    }

    private fun tableCreateSql(connection: Connection, table: String): String {
        connection.prepareStatement(
            "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?",
        ).use { statement ->
            statement.setString(1, table)
            statement.executeQuery().use { cursor ->
                if (cursor.next()) return cursor.getString("sql").orEmpty()
            }
        }
        fail("sqlite_master 里找不到表 $table")
        error("unreachable")
    }

    private fun jsonArrayToStringList(array: JSONArray): List<String> = buildList {
        for (index in 0 until array.length()) {
            add(array.getString(index))
        }
    }

    private data class PragmaColumn(
        val type: String,
        val notNull: Boolean,
        val defaultValue: String?,
        val primaryKey: Boolean,
    )
}
