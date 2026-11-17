package com.clipnest.core.douyin

import org.junit.Ignore
import org.junit.Test

/**
 * 真实联网解析测试（默认 @Ignore）。
 *
 * 需要能访问 douyin.com 的环境手动执行，例如：
 * ```
 * gradlew :core:test --tests "com.clipnest.core.douyin.DouyinResolverLiveTest" -i
 * ```
 * 三条样例来自 `I:\Download\类似.txt`。抖音有风控，间隔 2s 由解析器内部保证；请勿高频连跑。
 */
class DouyinResolverLiveTest {

    private val samples = listOf(
        "4.89 复制打开抖音，看看【想成为摄影糕手的图文作品】下次还要来陪我呀。出镜：@祈枝枝o.o # 王者荣... https://v.douyin.com/depmmgQEbkU/ h@O.kP 03/16 :4pm lcA:/",
        "5.38 复制打开抖音，看看【圈圈圆圆（求关注版）的图文作品】周一 大肥鱼亲我都不想醒。# deepseek #... https://v.douyin.com/QXrJTCe21i0/ CuF:/ 08/14 b@N.JV :6pm",
        "0.28 复制打开抖音，看看【红思📸的图文作品】超级可爱的小羽毛球子。# 原神cos # 纳西妲c... https://v.douyin.com/mDJPmVyPUqA/ DUL:/ 08/14 :2pm g@o.da",
    )

    @Ignore("真机/联网环境手动跑：需要能访问 douyin.com")
    @Test
    fun `真实解析三条抖音样例`() {
        val resolver = DouyinResolver()
        val results = samples.map { text ->
            println("解析：${text.take(60)}…")
            resolver.resolve(text, budgetMs = 60_000).also { work ->
                println(
                    "  → ok=${work.ok} type=${work.type} pics=${work.pics.size} " +
                        "video=${work.videoUrl.take(80)} quality=${work.videoQuality} " +
                        "code=${work.code} message=${work.message}",
                )
            }
        }

        // 真机网络可能偶发风控，只要不是「全军覆没且都是解析异常」即可
        val okCount = results.count { it.ok }
        println("成功 $okCount / ${samples.size}")
        if (okCount == 0) {
            val codes = results.joinToString(",") { it.code }
            error("三条样例全部解析失败，code=$codes")
        }
        results.filter { it.ok }.forEach { work ->
            check(work.awemeId.isNotEmpty()) { "成功结果缺少 awemeId" }
            check(work.type == "image" || work.type == "video") { "类型非法：${work.type}" }
            if (work.type == "image") {
                check(work.pics.isNotEmpty()) { "图文作品没有图片" }
                check(work.pics.all { it.startsWith("https://") }) { "图片不是 https" }
            } else {
                check(work.videoUrl.startsWith("https://")) { "视频地址不是 https" }
            }
        }
    }
}