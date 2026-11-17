package com.clipnest.core.douyin

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * 抖音 HTTP 客户端（不联网）：只验证非法 URL 的错误收敛，避免误吞成 Success。
 */
class DouyinHttpTest {

    @Test
    fun `非法 URL 的 get 抛 IOException 而不是崩溃`() {
        val http = DouyinHttp()
        assertThrows(IOException::class.java) {
            http.get("这不是一个合法 URL")
        }
    }

    @Test
    fun `非法 URL 的 Range 探测安全返回不可用`() {
        val http = DouyinHttp()
        val probe = http.probeSize("这不是一个合法 URL")
        assertFalse(probe.alive)
        assertNull(probe.size)
    }

    @Test
    fun `空 URL 也安全`() {
        val http = DouyinHttp()
        val probe = http.probeSize("")
        assertFalse(probe.alive)
        assertNull(probe.size)
    }
}