// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException

/**
 * 播放信息接口的空响应重试计划（issue #38）。
 *
 * 探针实测（2026-10-31，四轮约 160 次真实请求）：服务端空响应是短暂成簇的异常窗口，与内容、
 * 参数、设备身份无关；窗口内同连接同 host 的重试全部落空。因此每次重试都换 host 并强制新建
 * 连接，退避从 2.2 秒拉长到约 5 秒（含请求时间约 7 秒），让重试有机会落到窗口外的路径上。
 */
internal object PlayRetryPlan {
    val hosts =
        listOf(
            "api5-normal-sinfonlineb.fqnovel.com",
            "api5-normal-hl.fqnovel.com",
            "api5-normal-lf.fqnovel.com",
        )

    /** 第 1 次尝试后各次重试前的退避；三次重试合计 5.2 秒。 */
    val delaysMillis = listOf(700L, 1500L, 3000L)

    val maxAttempts = hosts.size + 1

    fun host(attempt: Int) = hosts[attempt % hosts.size]

    /** 首次尝试走连接池；重试强制新建 TCP+TLS，避免被粘在同一个后端节点上。 */
    fun freshConnection(attempt: Int) = attempt > 0

    /** 诊断行用的短代号；不暴露完整域名以外的任何信息。 */
    fun tag(host: String) =
        when (host) {
            "api5-normal-sinfonlineb.fqnovel.com" -> "b"
            "api5-normal-hl.fqnovel.com" -> "hl"
            "api5-normal-lf.fqnovel.com" -> "lf"
            else -> host
        }

    /** 与 ContentRepository.text() 相同的临时故障判定：连接类故障与 5xx 值得换路重试。 */
    fun temporary(problem: IOException) =
        problem is EmptyPlaybackResponseException ||
            problem is java.net.SocketTimeoutException ||
            problem is java.net.SocketException ||
            problem is java.net.UnknownHostException ||
            (problem is ContentHttpException && problem.statusCode in setOf(500, 502, 503, 504))
}
