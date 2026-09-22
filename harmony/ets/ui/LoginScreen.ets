import { promptAction } from '@kit.ArkUI';
import { ApiException } from '../api/HttpClient';
import { SendCodeResponse } from '../api/Dtos';
import { AuthManager } from '../auth/AuthManager';
import { LoginRules } from '../auth/LoginRules';
import { LocalSettings } from '../data/LocalSettings';
import { Colors, HERO_GRADIENT, PAGE_GRADIENT, PRIMARY_GRADIENT } from '../theme/Colors';

/**
 * 登录页(对应 Android 版 ui/LoginScreen.kt):邮箱 + 6 位验证码两步式。
 *
 *   ① 输入邮箱 → 请求发码(服务端 SMTP 发信;未配置则打印在服务端控制台)
 *   ② 填入 6 位码 → 校验通过即签发 token → 回调 onEnter 进首页
 *
 * 启动固定停在本页:**即使本地有续登会话也不自动进游戏**,
 * 而是在顶部给一张「继续上次登录」卡片(点了才校验并进入)。
 */
@Component
export struct LoginScreen {
  /** 由根页面注入:进入游戏(登录成功 / 游客 / 一键继续)。 */
  onEnter: () => void = () => {
  };

  @State private step: number = 0; // 0 = 输入邮箱,1 = 输入验证码
  @State private email: string = '';
  @State private code: string = '';
  @State private busy: boolean = false;
  @State private error: string = '';
  @State private notice: string = '';
  @State private devCode: string = '';
  @State private countdown: number = 0;
  @State private showServerPanel: boolean = false;
  @State private serverInput: string = '';
  @State private testing: boolean = false;
  @State private testResult: string = '';
  @State private testOk: boolean = false;
  @StorageProp('loggedIn') private loggedIn: boolean = false;
  @StorageProp('nickname') private nickname: string = '';
  @StorageProp('email') private sessionEmail: string = '';

  private timerId: number = -1;

  aboutToAppear(): void {
    this.email = AuthManager.lastEmail;
    this.serverInput = LocalSettings.serverUrl();
  }

  aboutToDisappear(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
  }

  /** 发码(含重发):成功后进入验证码步骤并启动倒计时。 */
  private async sendCode(isResend: boolean): Promise<void> {
    if (this.busy) {
      return;
    }
    const invalid: string | null = LoginRules.validateEmail(this.email);
    if (invalid !== null) {
      this.error = invalid;
      return;
    }
    this.busy = true;
    this.error = '';
    try {
      const resp: SendCodeResponse = await AuthManager.requestEmailCode(this.email);
      this.step = 1;
      this.code = '';
      this.devCode = resp.devCode === undefined ? '' : resp.devCode;
      if (resp.delivered) {
        this.notice = `验证码已发送至 ${resp.email},请查收(含垃圾箱)`;
      } else if (this.devCode.length > 0) {
        this.notice = '演示模式:服务器未配置 SMTP,验证码已显示在下方';
      } else {
        this.notice = '服务器未配置 SMTP,验证码已打印在服务端控制台';
      }
      if (isResend) {
        this.notice = '已重新发送,请使用最新收到的验证码';
      }
      this.startCountdown(resp.resendAfterSeconds);
    } catch (e) {
      const err = e as ApiException;
      this.error = err.message;
    } finally {
      this.busy = false;
    }
  }

  private startCountdown(seconds: number): void {
    this.countdown = seconds;
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
    }
    this.timerId = setInterval(() => {
      this.countdown = LoginRules.tick(this.countdown);
      if (this.countdown <= 0 && this.timerId >= 0) {
        clearInterval(this.timerId);
        this.timerId = -1;
      }
    }, 1000);
  }

  /** 提交验证码登录。 */
  private async doLogin(): Promise<void> {
    if (this.busy || !LoginRules.canSubmit(this.code)) {
      if (!LoginRules.canSubmit(this.code)) {
        this.error = '请输入 6 位数字验证码';
      }
      return;
    }
    this.busy = true;
    this.error = '';
    try {
      await AuthManager.loginWithEmailCode(this.email, this.code);
      this.onEnter();
    } catch (e) {
      const err = e as ApiException;
      this.error = err.message;
    } finally {
      this.busy = false;
    }
  }

  /** 「继续上次登录」:先校验令牌是否仍有效。 */
  private async continueSession(): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.error = '';
    const valid: boolean = await AuthManager.verifyRestoredSession();
    if (valid) {
      this.onEnter();
    } else {
      this.error = '登录状态已失效,请重新用邮箱登录';
    }
    this.busy = false;
  }

  private async enterGuest(): Promise<void> {
    await AuthManager.enterGuest();
    this.onEnter();
  }

  /** 服务器设置面板里的「测试连接」:不改设置,先探一次根路径。 */
  private async testConnection(): Promise<void> {
    if (this.testing) {
      return;
    }
    this.testing = true;
    this.testResult = '正在连接…';
    this.testOk = false;
    try {
      const message: string = await AuthManager.testConnection(this.serverInput);
      this.testOk = true;
      this.testResult = `✅ ${message}`;
    } catch (e) {
      const err = e as ApiException;
      this.testOk = false;
      this.testResult = `⚠ ${err.message}`;
    } finally {
      this.testing = false;
    }
  }

  /** 保存地址(顺手清掉自检结果,避免"地址换了但还是旧结论")。 */
  private async saveServerUrl(): Promise<void> {
    await LocalSettings.saveServerUrl(this.serverInput);
    AuthManager.refreshBaseUrl();
    this.serverInput = LocalSettings.serverUrl();
    this.testResult = '';
    this.testOk = false;
    promptAction.showToast({ message: `已保存:${LocalSettings.serverUrl()}` });
  }

  build() {
    Column() {
      // ———— 品牌 Hero ————
      Column() {
        Text('🖼').fontSize(34)
        Text('像素填空')
          .fontSize(38)
          .fontWeight(FontWeight.Bolder)
          .fontColor(Color.White)
          .letterSpacing(2)
          .margin({ top: 6 })
        Text('用数字线索还原像素画')
          .fontSize(13)
          .fontColor('#E3DEFF')
          .margin({ top: 6 })
      }
      .width('100%')
      .padding({ top: 44, bottom: 34 })
      .linearGradient({ angle: 180, colors: HERO_GRADIENT })

      Scroll() {
        Column() {
          // ———— 续登卡片(启动固定停在登录页,这里给一次性入口)————
          if (this.loggedIn) {
            Column() {
              Row() {
                Column() {
                  Text('继续上次登录').fontSize(11).fontColor(Colors.Cocoa)
                  Text(this.nickname.length > 0 ? this.nickname : '已登录账号')
                    .fontSize(15)
                    .fontWeight(FontWeight.Bold)
                    .fontColor(Colors.Ink)
                    .margin({ top: 2 })
                  Text(this.sessionEmail.length > 0 ? `上次登录:${this.sessionEmail}` : '本地保存的会话')
                    .fontSize(11)
                    .fontColor(Colors.Cocoa)
                    .margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)

                Button(this.busy ? '校验中…' : '继续 ▶')
                  .fontSize(14)
                  .height(40)
                  .backgroundColor(Colors.Coral)
                  .enabled(!this.busy)
                  .onClick(() => this.continueSession())
              }
              .width('100%')
            }
            .width('100%')
            .padding(14)
            .borderRadius(16)
            .backgroundColor(Colors.GlassFill)
            .border({ width: 1, color: Colors.GlassLine })
            .margin({ bottom: 18 })
          }

          // ———— 第一步:邮箱 ————
          if (this.step === 0) {
            Text('邮箱登录')
              .fontSize(20)
              .fontWeight(FontWeight.Bolder)
              .fontColor(Colors.Ink)
            Text('输入邮箱,我们会发送 6 位验证码;首次登录自动注册')
              .fontSize(12)
              .fontColor(Colors.Cocoa)
              .textAlign(TextAlign.Center)
              .margin({ top: 6, bottom: 4 })
            Text('服务器未配 SMTP 时,验证码会直接显示在下一步(演示模式)')
              .fontSize(10)
              .fontColor(Colors.Cocoa)
              .textAlign(TextAlign.Center)
              .margin({ bottom: 14 })

            TextInput({ text: this.email, placeholder: 'you@example.com' })
              .type(InputType.Email)
              .fontSize(15)
              .height(50)
              .backgroundColor(Colors.SurfaceWhite)
              .fontColor(Colors.Ink)
              .placeholderColor(Colors.Cocoa)
              .borderRadius(14)
              .enabled(!this.busy)
              .onChange((value: string) => {
                this.email = value;
                this.error = '';
              })

            Button(this.busy ? '正在发送…' : '获取验证码')
              .width('100%')
              .height(50)
              .fontSize(16)
              .fontWeight(FontWeight.Bold)
              .margin({ top: 14 })
              .enabled(!this.busy)
              .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
              .onClick(() => this.sendCode(false))
          } else {
            // ———— 第二步:验证码 ————
            Text('输入验证码')
              .fontSize(20)
              .fontWeight(FontWeight.Bolder)
              .fontColor(Colors.Ink)
            Text(`已发送至 ${this.email.trim()}`)
              .fontSize(12)
              .fontColor(Colors.Cocoa)
              .margin({ top: 6 })
            Button('← 换个邮箱')
              .fontSize(12)
              .fontColor(Colors.Coral)
              .backgroundColor(Color.Transparent)
              .height(32)
              .enabled(!this.busy)
              .onClick(() => {
                this.step = 0;
                this.code = '';
                this.error = '';
                this.notice = '';
                this.devCode = '';
              })

            TextInput({ text: this.code, placeholder: '______' })
              .type(InputType.NUMBER_PASSWORD)
              .maxLength(6)
              .fontSize(22)
              .fontWeight(FontWeight.Bold)
              .textAlign(TextAlign.Center)
              .height(54)
              .backgroundColor(Colors.SurfaceWhite)
              .fontColor(Colors.Ink)
              .placeholderColor(Colors.Cocoa)
              .borderRadius(14)
              .enabled(!this.busy)
              .onChange((value: string) => {
                const digits: string = LoginRules.formatCode(value);
                this.code = digits;
                this.error = '';
                // 填满 6 位自动提交,省一次点击
                if (LoginRules.canSubmit(digits) && !this.busy) {
                  this.doLogin();
                }
              })

            if (this.devCode.length > 0) {
              Text(`演示验证码:${this.devCode}`)
                .fontSize(16)
                .fontWeight(FontWeight.Bold)
                .fontColor(Colors.Amber)
                .margin({ top: 10 })
            }
            if (this.notice.length > 0) {
              Text(this.notice)
                .fontSize(11)
                .fontColor(Colors.Cyan)
                .textAlign(TextAlign.Center)
                .margin({ top: 10 })
            }

            Button(this.busy ? '正在登录…' : '登录')
              .width('100%')
              .height(50)
              .fontSize(16)
              .fontWeight(FontWeight.Bold)
              .margin({ top: 14 })
              .enabled(!this.busy)
              .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
              .onClick(() => this.doLogin())

            Button(LoginRules.resendLabel(this.countdown))
              .fontSize(12)
              .backgroundColor(Color.Transparent)
              .fontColor(this.countdown > 0 ? Colors.Cocoa : Colors.Coral)
              .enabled(this.countdown <= 0 && !this.busy)
              .onClick(() => this.sendCode(true))
          }

          if (this.error.length > 0) {
            Text(`⚠ ${this.error}`)
              .fontSize(12)
              .fontColor(Colors.Danger)
              .textAlign(TextAlign.Center)
              .margin({ top: 10 })
          }

          // ———— 游客进入(免登录,离线也能玩)————
          Button('👤 游客进入')
            .width('100%')
            .height(48)
            .fontSize(15)
            .fontWeight(FontWeight.Bold)
            .fontColor(Colors.Ink)
            .backgroundColor(Colors.GlassFill)
            .border({ width: 1, color: Colors.GlassLine })
            .margin({ top: 18 })
            .enabled(!this.busy)
            .onClick(() => this.enterGuest())

          // ———— 服务器设置(内联面板,与安卓端设置项等价)————
          Button(`服务器设置 · ${AuthManager.serverHost()}`)
            .fontSize(12)
            .fontColor(Colors.Cocoa)
            .backgroundColor(Color.Transparent)
            .height(34)
            .margin({ top: 10 })
            .onClick(() => {
              this.serverInput = LocalSettings.serverUrl();
              this.showServerPanel = !this.showServerPanel;
            })

          if (this.showServerPanel) {
            Column() {
              TextInput({ text: this.serverInput, placeholder: 'http://192.168.1.5:8000/' })
                .fontSize(14)
                .height(46)
                .backgroundColor(Colors.SurfaceWhite)
                .fontColor(Colors.Ink)
                .placeholderColor(Colors.Cocoa)
                .borderRadius(12)
                .onChange((value: string) => {
                  this.serverInput = value;
                  this.testResult = '';
                })
              Row() {
                Button(this.testing ? '测试中…' : '测试连接')
                  .fontSize(14)
                  .layoutWeight(1)
                  .backgroundColor(Colors.CyanContainer)
                  .fontColor(Colors.Cyan)
                  .enabled(!this.testing)
                  .onClick(() => this.testConnection())
                Button('保存')
                  .fontSize(14)
                  .layoutWeight(1)
                  .backgroundColor(Colors.Coral)
                  .margin({ left: 10 })
                  .onClick(() => this.saveServerUrl())
                Button('取消')
                  .fontSize(14)
                  .layoutWeight(1)
                  .backgroundColor(Colors.Sand)
                  .margin({ left: 10 })
                  .onClick(() => {
                    this.showServerPanel = false;
                    this.testResult = '';
                  })
              }
              .width('100%')
              .margin({ top: 10 })

              if (this.testResult.length > 0) {
                Text(this.testResult)
                  .fontSize(11)
                  .fontColor(this.testOk ? Colors.Cyan : Colors.Amber)
                  .margin({ top: 8 })
              }

              Text('⚠ 鸿蒙模拟器/真机不能用 10.0.2.2 或 localhost,必须填电脑的局域网 IPv4')
                .fontSize(10)
                .fontColor(Colors.Amber)
                .margin({ top: 8 })
              Text('查法:电脑上运行 ipconfig,看 WLAN/以太网的 IPv4 地址;')
                .fontSize(10)
                .fontColor(Colors.Cocoa)
                .margin({ top: 4 })
              Text('启动服务端:双击 scripts\\start-server-smtp.bat(监听 0.0.0.0:8000)')
                .fontSize(10)
                .fontColor(Colors.Cocoa)
                .margin({ top: 2 })
              Text('先点「测试连接」;通过后再「保存」,然后回到上面点「获取验证码」')
                .fontSize(10)
                .fontColor(Colors.Cocoa)
                .margin({ top: 2 })
            }
            .width('100%')
            .padding(12)
            .borderRadius(14)
            .backgroundColor(Colors.SurfaceWhite)
            .margin({ top: 10 })
          }

          Blank().height(24)
        }
        .width('100%')
        .padding({ left: 24, right: 24, top: 24 })
        .alignItems(HorizontalAlign.Center)
      }
      .layoutWeight(1)
      .align(Alignment.Top)
    }
    .width('100%')
    .height('100%')
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }
}
