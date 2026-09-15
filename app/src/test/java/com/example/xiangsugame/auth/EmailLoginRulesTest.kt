package com.example.xiangsugame.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邮箱验证码登录的客户端规则回归:
 * 邮箱格式提示、提交/重发按钮可用性、验证码输入过滤、倒计时递进。
 * 服务端同规则见 server/auth.py 的 normalize_email / login 与 server/smoke_test.py。
 */
class EmailLoginRulesTest {

    // ---------------- 邮箱校验 ----------------

    @Test
    fun `合法邮箱通过校验并规范化为小写去空白`() {
        for (email in listOf(
            "a@b.co", "user.name+tag@example.com", "  Player_1@QQ.COM  ",
            "x-y_z%1@sub.domain.edu.cn",
        )) {
            assertNull("应通过: $email", LoginRules.validateEmail(email))
        }
        assertEquals("player_1@qq.com", LoginRules.normalizeEmail("  Player_1@QQ.COM  "))
    }

    @Test
    fun `非法邮箱给出提示而不是静默提交`() {
        val cases = mapOf(
            "" to "请输入邮箱",
            "   " to "请输入邮箱",
            "abc" to "邮箱格式不正确",
            "a@b" to "邮箱格式不正确",          // 缺顶级域
            "a b@c.com" to "邮箱格式不正确",      // 含空格
            "a@@b.com" to "邮箱格式不正确",
            "a..b@c.com" to "邮箱格式不正确",     // 连续点
            "@c.com" to "邮箱格式不正确",
            "a@c.com." to "邮箱格式不正确",
        )
        cases.forEach { (input, expected) ->
            assertEquals("输入「$input」", expected, LoginRules.validateEmail(input))
        }
        // 超长:本地先挡,避免无谓请求(RFC 上限 254;注意此类输入会先命中"过长")
        assertEquals("邮箱过长", LoginRules.validateEmail("a".repeat(250) + "@b.com"))
        // 本地部分超过 64 也是非法(总长未超限,命中格式分支)
        assertEquals("邮箱格式不正确", LoginRules.validateEmail("a".repeat(65) + "@b.com"))
    }

    // ---------------- 验证码输入与提交 ----------------

    @Test
    fun `验证码输入只保留数字并截断到六位`() {
        assertEquals("123456", LoginRules.formatCode("123456"))
        assertEquals("123456", LoginRules.formatCode("12 34-56"))
        assertEquals("123456", LoginRules.formatCode("1234567890"))
        assertEquals("", LoginRules.formatCode("abcdef"))
        assertEquals("001234", LoginRules.formatCode("001234"))
    }

    @Test
    fun `六位数字才允许提交`() {
        assertTrue(LoginRules.canSubmit("000000"))
        assertTrue(LoginRules.canSubmit("123456"))
        assertFalse(LoginRules.canSubmit("12345"))
        assertFalse(LoginRules.canSubmit("1234567"))
        assertFalse(LoginRules.canSubmit(""))
        assertFalse(LoginRules.canSubmit("12345a"))
    }

    // ---------------- 重发倒计时 ----------------

    @Test
    fun `倒计时归零前禁止重发且文案随秒数变化`() {
        assertFalse(LoginRules.canResend(60))
        assertEquals("60 秒后可重新发送", LoginRules.resendLabel(60))
        assertFalse(LoginRules.canResend(1))
        assertTrue(LoginRules.canResend(0))
        assertEquals("重新发送验证码", LoginRules.resendLabel(0))
    }

    @Test
    fun `倒计时递减到零后不再变负`() {
        assertEquals(59, LoginRules.tick(60))
        assertEquals(1, LoginRules.tick(2))
        assertEquals(0, LoginRules.tick(1))
        assertEquals(0, LoginRules.tick(0))
    }
}
