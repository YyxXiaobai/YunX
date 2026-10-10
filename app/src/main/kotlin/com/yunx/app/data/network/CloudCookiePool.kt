/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 云端 Cookie 池（Cloudflare Pages + KV）：
 *  - [fetchValidCookie]：GET 拉取按负载轮换的候选池（{ck, name, used, last_used}），
 *    与 PC 版《赛博网盘下载器》的 _cloud_pick_from_pool 对齐：used 最少、最近活跃优先；
 *    选中的 Cookie 经 [QuarkApi.fetchNickname] 校验有效后才返回（失效静默跳过）。
 *  - [uploadCookie]：用户登录成功后众包上报入池（失败静默，不影响本地）。
 *  - [reportInvalid]：发现失效 Cookie 时上报剔除（防坏 Cookie 传染全池）。
 *  全部请求用浏览器 UA（[QuarkConstants.CLOUD_UA]），否则 Cloudflare Bot Fight 返回 1010。
 */
object CloudCookiePool {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private data class PoolItem(val ck: String, val used: Long, val lastUsed: Long)

    /** 拉取并按负载选一个有效 Cookie；池空/全失效/网络失败返回 null（调用方静默处理） */
    suspend fun fetchValidCookie(
        api: QuarkApi
    ): String? = withContext(Dispatchers.IO) {
        val client = HttpClients.apiClient()
        val request = Request.Builder()
            .url(QuarkConstants.CLOUD_FETCH_URL)
            .header("User-Agent", QuarkConstants.CLOUD_UA)
            .get()
            .build()
        val pool: List<PoolItem> = runCatching<List<PoolItem>> {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string() ?: return@use emptyList()
                val json = JSONObject(body)
                val arr = json.optJSONArray("pool") ?: return@use emptyList()
                buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val ck = item.optString("ck").trim()
                        if (ck.isBlank()) continue
                        add(
                            PoolItem(
                                ck = ck,
                                used = item.optLong("used", 0L),
                                lastUsed = item.optLong("last_used", 0L)
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (pool.isEmpty()) return@withContext null
        // 负载轮换：used 最少优先；同 used 时最近活跃优先
        val sorted = pool.sortedWith(compareBy({ it.used }, { -it.lastUsed }))
        for (item in sorted) {
            if (api.fetchNickname(item.ck) != null) return@withContext item.ck
        }
        null
    }

    /** 用户登录成功后众包上报入池（失败静默，不影响本地登录） */
    suspend fun uploadCookie(cookie: String, name: String) = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject()
                .put("cookie", cookie)
                .put("name", name.ifBlank { "Android" })
                .toString()
            val request = Request.Builder()
                .url(QuarkConstants.CLOUD_UPLOAD_URL)
                .header("User-Agent", QuarkConstants.CLOUD_UA)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }
    }

    /** 发现失效 Cookie → 上报云端剔除（失败静默） */
    suspend fun reportInvalid(cookie: String) = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("cookie", cookie).toString()
            val request = Request.Builder()
                .url(QuarkConstants.CLOUD_REPORT_URL)
                .header("User-Agent", QuarkConstants.CLOUD_UA)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(jsonMediaType))
                .build()
            HttpClients.apiClient().newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        }
    }
}
