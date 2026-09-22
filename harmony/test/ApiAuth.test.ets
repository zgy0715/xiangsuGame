import { describe, expect, it } from '@ohos/hypium';
import { LoginRules } from '../main/ets/auth/LoginRules';
import { ApiException, HttpClient } from '../main/ets/api/HttpClient';
import { Dates } from '../main/ets/data/Dates';
import { LocalSettings } from '../main/ets/data/LocalSettings';

/**
 * 登录规则 / 网络客户端 / 设置层的单元测试
 * (对应 Android 版 app/src/test/.../EmailLoginRulesTest.kt)。
 *
 * 这些用例同时起到"编译验证"的作用:ArkTS 只编译被引用到的文件,
 * 通过测试导入它们,能确保这一层真的进了编译产物。
 */
export default function apiAuthTest() {
  describe('ApiAuthTest', () => {

    // ———— 邮箱校验 ————
    it('validate_email_rejects_empty_and_malformed', 0, () => {
      expect(LoginRules.validateEmail('')).assertEqual('请输入邮箱');
      expect(LoginRules.validateEmail('   ')).assertEqual('请输入邮箱');
      expect(LoginRules.validateEmail('abc')).assertEqual('邮箱格式不正确,请检查后重试');
      expect(LoginRules.validateEmail('abc@')).assertEqual('邮箱格式不正确,请检查后重试');
      expect(LoginRules.validateEmail('@example.com')).assertEqual('邮箱格式不正确,请检查后重试');
      expect(LoginRules.validateEmail('a@b')).assertEqual('邮箱格式不正确,请检查后重试');
      expect(LoginRules.validateEmail('a b@example.com')).assertEqual('邮箱格式不正确,请检查后重试');
    });

    it('validate_email_accepts_normal_addresses', 0, () => {
      expect(LoginRules.validateEmail('a@example.com')).assertNull();
      expect(LoginRules.validateEmail('first.last+tag@sub.example.cn')).assertNull();
      expect(LoginRules.validateEmail('  User@Example.COM  ')).assertNull();
    });

    it('normalize_email_lowercases_and_trims', 0, () => {
      expect(LoginRules.normalizeEmail('  A@Example.COM ')).assertEqual('a@example.com');
      expect(LoginRules.normalizeEmail('')).assertEqual('');
    });

    // ———— 验证码格式化与提交判定 ————
    it('format_code_keeps_digits_only_and_caps_at_six', 0, () => {
      expect(LoginRules.formatCode('12a3b4')).assertEqual('1234');
      expect(LoginRules.formatCode('123456789')).assertEqual('123456');
      expect(LoginRules.formatCode('abc')).assertEqual('');
      expect(LoginRules.formatCode(' 1 2 3 ')).assertEqual('123');
    });

    it('can_submit_only_for_six_digits', 0, () => {
      expect(LoginRules.canSubmit('123456')).assertTrue();
      expect(LoginRules.canSubmit(' 123456 ')).assertTrue();
      expect(LoginRules.canSubmit('12345')).assertFalse();
      expect(LoginRules.canSubmit('12345a')).assertFalse();
      expect(LoginRules.canSubmit('')).assertFalse();
    });

    // ———— 重发倒计时 ————
    it('countdown_ticks_down_and_labels', 0, () => {
      expect(LoginRules.tick(60)).assertEqual(59);
      expect(LoginRules.tick(1)).assertEqual(0);
      expect(LoginRules.tick(0)).assertEqual(0);
      expect(LoginRules.canResend(0)).assertTrue();
      expect(LoginRules.canResend(3)).assertFalse();
      expect(LoginRules.resendLabel(0)).assertEqual('重新发送验证码');
      expect(LoginRules.resendLabel(12)).assertEqual('重新发送(12s)');
    });

    // ———— HttpClient:地址规范化与展示 ————
    it('http_client_normalizes_base_url_and_host', 0, () => {
      const client: HttpClient = new HttpClient();
      expect(client.getBaseUrl()).assertEqual('');
      client.setBaseUrl('http://192.168.1.5:8000');
      expect(client.getBaseUrl()).assertEqual('http://192.168.1.5:8000/');
      expect(client.serverHost()).assertEqual('192.168.1.5:8000');
      client.setBaseUrl('https://example.com/');
      expect(client.serverHost()).assertEqual('example.com');
    });

    it('http_client_fails_fast_without_base_url', 0, async () => {
      const client: HttpClient = new HttpClient();
      let message: string = '';
      try {
        await client.get<Object>('api/puzzles/daily');
      } catch (e) {
        const err = e as ApiException;
        message = err.message;
      }
      expect(message).assertEqual('还没有设置服务器地址,请到设置页填写');
    });

    // ———— 设置层默认值 ————
    it('local_settings_defaults', 0, () => {
      // 鸿蒙模拟器/真机都不能用安卓模拟器那套 10.0.2.2,默认值必须是电脑的局域网 IP
      expect(LocalSettings.DEFAULT_SERVER_URL.startsWith('http://')).assertTrue();
      expect(LocalSettings.DEFAULT_SERVER_URL.endsWith('/')).assertTrue();
      expect(LocalSettings.DEFAULT_SERVER_URL.indexOf('10.0.2.2')).assertEqual(-1);
      expect(LocalSettings.DEFAULT_SERVER_URL.indexOf('localhost')).assertEqual(-1);
    });

    // ———— 本地日期(仅展示用;每日一题的日历日由服务端决定) ————
    it('dates_today_iso_format', 0, () => {
      const today: string = Dates.todayIso();
      expect(today.length).assertEqual(10);
      expect(today.charAt(4)).assertEqual('-');
      expect(today.charAt(7)).assertEqual('-');
    });
  });
}
