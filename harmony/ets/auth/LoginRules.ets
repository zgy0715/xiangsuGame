/**
 * 登录规则(对应 Android 版 auth/LoginRules.kt)。
 *
 * 与服务端 server/auth.py 的 normalize_email 保持一致:小写 + 去首尾空白。
 * 客户端做格式校验只是为了即时提示,真正的可达性由"收到验证码"证明。
 */
export class LoginRules {

  /** 邮箱格式校验;返回错误文案,null 表示通过。 */
  static validateEmail(raw: string): string | null {
    const email: string = LoginRules.normalizeEmail(raw);
    if (email.length === 0) {
      return '请输入邮箱';
    }
    if (email.length > 254) {
      return '邮箱过长,请检查后重试';
    }
    if (!LoginRules.EMAIL_RE.test(email)) {
      return '邮箱格式不正确,请检查后重试';
    }
    return null;
  }

  /** 规范化:去首尾空白 + 转小写(与服务端一致)。 */
  static normalizeEmail(raw: string): string {
    return (raw ?? '').trim().toLowerCase();
  }

  /** 验证码输入格式化:只保留数字,最多 6 位。 */
  static formatCode(raw: string): string {
    let digits: string = '';
    for (let i = 0; i < raw.length; i++) {
      const ch: string = raw.charAt(i);
      if (ch >= '0' && ch <= '9') {
        digits += ch;
      }
      if (digits.length >= 6) {
        break;
      }
    }
    return digits;
  }

  /** 是否已是完整的 6 位码(用于"填满自动提交")。 */
  static canSubmit(code: string): boolean {
    return LoginRules.CODE_RE.test(code.trim());
  }

  /** 倒计时是否已归零(可以重发)。 */
  static canResend(countdownSeconds: number): boolean {
    return countdownSeconds <= 0;
  }

  /** 倒计时递减一步(不小于 0)。 */
  static tick(countdownSeconds: number): number {
    return countdownSeconds > 0 ? countdownSeconds - 1 : 0;
  }

  /** 重发按钮文案。 */
  static resendLabel(countdownSeconds: number): string {
    return countdownSeconds > 0 ? `重新发送(${countdownSeconds}s)` : '重新发送验证码';
  }

  private static readonly EMAIL_RE: RegExp = /^[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}$/;
  private static readonly CODE_RE: RegExp = /^\d{6}$/;
}
