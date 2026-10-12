package com.autotraces.app

import org.json.JSONObject

/**
 * 待活跃推荐：三天总赞增长（前天+昨天+今天）≤ 阈值则推荐入队。
 * 计算结果本机缓存 [PrefsStore.PENDING_ACTIVATE_RECOMMEND_CACHE_TTL_MS]（8 小时）内复用。
 */
object PendingActivateRecommend {
    data class Snapshot(
        val username: String,
        val heartGrowth3d: Int,
        val heartCount: Int,
        val banned: Boolean,
        val dayBefore: Int,
        val yesterday: Int,
        val today: Int,
    ) {
        fun isRecommended(threshold: Int): Boolean =
            !banned && heartGrowth3d <= threshold
    }

    fun parseGrowth(raw: String?): Int {
        val text = raw?.trim()?.replace(",", "").orEmpty()
        if (text.isEmpty()) return 0
        return text.toDoubleOrNull()?.toInt() ?: 0
    }

    fun heartGrowth3d(item: DeviceAssignedAccountItem): Int {
        val split = parseGrowth(item.heart_growth_day_before) +
            parseGrowth(item.heart_growth_yesterday) +
            parseGrowth(item.heart_growth_today)
        if (split > 0 ||
            !item.heart_growth_day_before.isNullOrBlank() ||
            !item.heart_growth_yesterday.isNullOrBlank() ||
            !item.heart_growth_today.isNullOrBlank()
        ) {
            return split
        }
        return parseGrowth(item.heart_growth)
    }

    fun snapshotFromItem(item: DeviceAssignedAccountItem): Snapshot? {
        val username = item.username?.trim()?.removePrefix("@").orEmpty()
        if (username.isEmpty()) return null
        val banned = item.looks_banned == true ||
            item.looks_max_login == true ||
            ManualDeviceControl.isBannedMarker(item.password) ||
            ManualDeviceControl.isBannedMarker(item.email) ||
            ManualDeviceControl.isBannedMarker(item.email_password) ||
            ManualDeviceControl.isMaxLoginMarker(item.password) ||
            ManualDeviceControl.isMaxLoginMarker(item.email) ||
            ManualDeviceControl.isMaxLoginMarker(item.email_password)
        return Snapshot(
            username = username,
            heartGrowth3d = heartGrowth3d(item),
            heartCount = parseGrowth(item.heart_count),
            banned = banned,
            dayBefore = parseGrowth(item.heart_growth_day_before),
            yesterday = parseGrowth(item.heart_growth_yesterday),
            today = parseGrowth(item.heart_growth_today),
        )
    }

    /**
     * 拉取账号后写入/合并缓存。
     * 8 小时内：已有账号沿用旧三天总赞增长结果，仅补齐新账号；不重置缓存起点。
     * 过期后：按本次云端数据全量重算。
     */
    fun ingestRoster(items: List<DeviceAssignedAccountItem>) {
        val now = System.currentTimeMillis()
        // 云端空名单：整表清空，禁止沿用已撤销分配的历史推荐
        if (items.isEmpty()) {
            save(now, emptyMap())
            return
        }
        val rosterKeys = items.mapNotNull { item ->
            item.username?.trim()?.removePrefix("@")?.lowercase()?.takeIf { it.isNotEmpty() }
        }.toSet()
        val existing = loadMap()
        val cacheAt = loadComputedAtMs()
        val reuse = cacheAt != null && now - cacheAt < PrefsStore.PENDING_ACTIVATE_RECOMMEND_CACHE_TTL_MS
        // 复用期内也先按当前云端名单裁剪，避免已撤号继续占推荐位
        val next = if (reuse) {
            existing.filterKeys { it in rosterKeys }.toMutableMap()
        } else {
            mutableMapOf()
        }
        for (item in items) {
            val snap = snapshotFromItem(item) ?: continue
            val key = snap.username.lowercase()
            if (reuse && next.containsKey(key)) continue
            next[key] = snap
        }
        // 复用期内保留原计算时间，避免每次拉取把 8 小时窗口往后推
        save(if (reuse) cacheAt!! else now, next)
    }

    fun isRecommended(username: String): Boolean {
        val snap = snapshot(username) ?: return false
        return snap.isRecommended(PrefsStore.getPendingActivateHeartGrowthMax())
    }

    fun snapshot(username: String): Snapshot? {
        val key = username.trim().removePrefix("@").lowercase()
        if (key.isEmpty()) return null
        return loadMap()[key]
    }

    fun recommendedUsernamesNotInQueue(): List<String> {
        val threshold = PrefsStore.getPendingActivateHeartGrowthMax()
        return loadMap().values
            .filter { it.isRecommended(threshold) && !PrefsStore.isInPendingActivateQueue(it.username) }
            .map { it.username }
            .distinctBy { it.lowercase() }
    }

    fun recommendedCount(): Int {
        val threshold = PrefsStore.getPendingActivateHeartGrowthMax()
        return loadMap().values.count { it.isRecommended(threshold) }
    }

    fun cacheAgeLabel(): String {
        val at = loadComputedAtMs() ?: return "无缓存"
        val ageMs = (System.currentTimeMillis() - at).coerceAtLeast(0L)
        val remain = PrefsStore.PENDING_ACTIVATE_RECOMMEND_CACHE_TTL_MS - ageMs
        if (remain <= 0) return "缓存已过期"
        val hours = remain / 3_600_000L
        val minutes = (remain % 3_600_000L) / 60_000L
        return "缓存还可复用 ${hours}小时${minutes}分"
    }

    private fun loadComputedAtMs(): Long? {
        val raw = PrefsStore.getPendingActivateRecommendCacheJson() ?: return null
        return runCatching { JSONObject(raw).optLong("atMs", 0L).takeIf { it > 0L } }.getOrNull()
    }

    private fun loadMap(): Map<String, Snapshot> {
        val raw = PrefsStore.getPendingActivateRecommendCacheJson() ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            val byUser = root.optJSONObject("byUser") ?: return emptyMap()
            buildMap {
                val keys = byUser.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val o = byUser.optJSONObject(k) ?: continue
                    val username = o.optString("u").trim().removePrefix("@").ifBlank { k }
                    put(
                        username.lowercase(),
                        Snapshot(
                            username = username,
                            heartGrowth3d = o.optInt("g3", 0),
                            heartCount = o.optInt("hc", 0),
                            banned = o.optBoolean("ban", false),
                            dayBefore = o.optInt("d0", 0),
                            yesterday = o.optInt("d1", 0),
                            today = o.optInt("d2", 0),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun save(atMs: Long, map: Map<String, Snapshot>) {
        val byUser = JSONObject()
        map.values.forEach { snap ->
            byUser.put(
                snap.username.lowercase(),
                JSONObject()
                    .put("u", snap.username)
                    .put("g3", snap.heartGrowth3d)
                    .put("hc", snap.heartCount)
                    .put("ban", snap.banned)
                    .put("d0", snap.dayBefore)
                    .put("d1", snap.yesterday)
                    .put("d2", snap.today),
            )
        }
        PrefsStore.setPendingActivateRecommendCacheJson(
            JSONObject()
                .put("atMs", atMs)
                .put("byUser", byUser)
                .toString(),
        )
    }
}
