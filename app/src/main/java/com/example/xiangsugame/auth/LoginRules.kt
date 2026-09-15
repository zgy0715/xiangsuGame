package com.example.xiangsugame.auth

/**
 * 邮箱验证码登录的纯逻辑(无 Android 依赖,可直接 JVM 单测)。
 *
 * 客户端只做"明显非法输入的即时提示"与按钮可用性判断;真正的判定
 * (格式、频率、验证码是否有效)一律以服务端为准 —— 见 server/auth.py。
 */
object LoginRules {

    /** 邮箱输入框允许的最大长度(RFC 5321 上限)。 */
    const val MAX_EMAIL_LENGTH = 254

    /** 验证码固定 6 位十进制。 */
    const val CODE_LENGTH = 6

    /** 客户端本地邮箱校验:返回错误文案,null 表示格式可提交。与服务端规则保持一致。 */
    fun validateEmail(raw: String): String? {
        val email = raw.trim()
        return when {
            email.isEmpty() -> "请输入邮箱"
            email.length > MAX_EMAIL_LENGTH -> "邮箱过长"
            !EMAIL_RE.matches(email) -> "邮箱格式不正确"
            email.substringBefore("@").length > 64 -> "邮箱格式不正确"
            email.contains("..") -> "邮箱格式不正确"
            else -> null
        }
    }

    /** 提交前规范化邮箱:去首尾空白 + 转小写(与服务端 normalize_email 一致)。 */
    fun normalizeEmail(raw: String): String = raw.trim().lowercase()

    /**
     * 验证码输入过滤:只保留数字并截断到 6 位。
     * 用于输入框 onValueChange,顺带挡住粘贴进来的空格/字母。
     */
    fun formatCode(raw: String): String = raw.filter { it.isDigit() }.take(CODE_LENGTH)

    /** 验证码是否已填满,可提交登录。 */
    fun canSubmit(code: String): Boolean =
        code.length == CODE_LENGTH && code.all { it.isDigit() }

    /** 重新发送是否可用(服务端 60 秒重发间隔,本地同步倒计时)。 */
    fun canResend(countdownSeconds: Int): Boolean = countdownSeconds <= 0

    /** 倒计时递减一步(负数归零)。 */
    fun tick(countdownSeconds: Int): Int = (countdownSeconds - 1).coerceAtLeast(0)

    /** 倒计时展示文案。 */
    fun resendLabel(countdownSeconds: Int): String =
        if (canResend(countdownSeconds)) "重新发送验证码" else "${countdownSeconds} 秒后可重新发送"

    private val EMAIL_RE = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")
}
