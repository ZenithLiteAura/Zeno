package com.clipnest.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正则超时保护：恶意/病态正则不能把调用方卡死。
 * 实现是 DeadlineCharSequence（正则每读一个字符检查截止时间），因此不会残留跑飞的匹配线程。
 */
class RegexTimeoutTest {

    @Test
    fun `灾难性回溯的正则不会把调用方卡死`() {
        val engine = RuleEngine(
            listOf(
                CaptureRule(
                    id = "slow",
                    name = "病态正则（自测用）",
                    platform = "generic",
                    platformName = "链接",
                    pattern = "^(a+)+$",
                    enabled = true,
                ),
            ),
        )
        // 26 个 a + 一个 b：经典灾难性回溯输入
        val text = "a".repeat(26) + "b"

        val startedAt = System.currentTimeMillis()
        val result = engine.tryCapture(text)
        val elapsed = System.currentTimeMillis() - startedAt

        assertFalse("超时/栈溢出后应当作未命中", result.matched)
        assertTrue("必须快速返回，实际 ${elapsed}ms", elapsed < 3_000)
    }

    @Test
    fun `截止时间哨兵能中止匹配`() {
        // 截止时间已过：第一次读字符就应该抛超时
        val expired = DeadlineCharSequence("a".repeat(1000), System.nanoTime() - 1_000_000L)
        assertThrows(RegexTimeoutException::class.java) {
            Regex("a+").find(expired)
        }
    }

    @Test
    fun `正常规则不受超时机制影响`() {
        val engine = RuleEngine()
        val result = engine.tryCapture(TestData.SAMPLE_1)
        assertTrue(result.matched)
        assertEqualsTimeoutWindowPositive()
    }

    private fun assertEqualsTimeoutWindowPositive() {
        assertTrue(RuleEngine.REGEX_TIMEOUT_MS in 100..1000)
    }
}