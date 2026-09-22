import { promptAction } from '@kit.ArkUI';
import { common } from '@kit.AbilityKit';
import { ApiException } from '../api/HttpClient';
import { AuthManager } from '../auth/AuthManager';
import { LocalSettings } from '../data/LocalSettings';
import { BackgroundMusicManager } from '../service/BackgroundMusicManager';
import { SoundEffectManager } from '../service/SoundEffectManager';
import { Colors, PAGE_GRADIENT } from '../theme/Colors';
import { ScreenTopBar } from './Widgets';

/**
 * 设置页(对应 Android 版 ui/SettingsScreen.kt)。
 *
 * 服务器地址 / 音效 / 背景音乐 / 摇一摇 / 演示全解锁 / 昵称 / 退出登录,
 * 与安卓端同一套设置项与持久化键名(见 LocalSettings)。
 */
@Component
export struct SettingsScreen {
  onBack: () => void = () => {
  };
  onLoggedOut: () => void = () => {
  };

  @State private serverInput: string = '';
  @State private nicknameInput: string = '';
  @State private editingServer: boolean = false;
  @State private editingNickname: boolean = false;
  @State private busy: boolean = false;
  @State private soundOn: boolean = true;
  @State private bgmOn: boolean = true;
  @State private shakeOn: boolean = true;
  @State private demoUnlock: boolean = false;
  @State private testing: boolean = false;
  @State private testResult: string = '';
  @State private testOk: boolean = false;
  @StorageProp('nickname') private nickname: string = '';
  @StorageProp('email') private email: string = '';

  aboutToAppear(): void {
    this.serverInput = LocalSettings.serverUrl();
    this.nicknameInput = this.nickname;
    this.soundOn = LocalSettings.soundEffectsEnabled();
    this.bgmOn = LocalSettings.backgroundMusicEnabled();
    this.shakeOn = LocalSettings.shakeToResetEnabled();
    this.demoUnlock = LocalSettings.demoAllUnlocked();
  }

  private isGuest(): boolean {
    return AuthManager.isGuest;
  }

  /** 「测试连接」:按当前输入框地址探一次服务端根路径。 */
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

  build() {
    Column() {
      ScreenTopBar({ title: '设置', subtitle: AuthManager.serverHost(), onBack: this.onBack })

      Scroll() {
        Column() {
          // ———— 服务器地址 ————
          Column() {
            Text('🌐 服务器地址').fontSize(14).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
            Text(LocalSettings.serverUrl()).fontSize(13).fontColor(Colors.Ink)
              .margin({ top: 8 })
            if (!this.editingServer) {
              Button('修改').fontSize(12).height(32).margin({ top: 8 })
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .onClick(() => {
                  this.serverInput = LocalSettings.serverUrl();
                  this.editingServer = true;
                })
            } else {
              TextInput({ text: this.serverInput, placeholder: 'http://192.168.1.5:8000/' })
                .fontSize(13).height(44).margin({ top: 8 })
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .placeholderColor(Colors.Cocoa).borderRadius(10)
                .onChange((value: string) => {
                  this.serverInput = value;
                  this.testResult = '';
                })
              Row() {
                Button(this.testing ? '测试中…' : '测试连接').fontSize(13).height(36).layoutWeight(1)
                  .backgroundColor(Colors.CyanContainer).fontColor(Colors.Cyan)
                  .enabled(!this.testing)
                  .onClick(() => this.testConnection())
                Button('保存').fontSize(13).height(36).layoutWeight(1).margin({ left: 8 })
                  .backgroundColor(Colors.Coral)
                  .onClick(async () => {
                    await LocalSettings.saveServerUrl(this.serverInput);
                    AuthManager.refreshBaseUrl();
                    this.editingServer = false;
                    promptAction.showToast({ message: `已保存:${LocalSettings.serverUrl()}` });
                  })
                Button('取消').fontSize(13).height(36).layoutWeight(1).margin({ left: 8 })
                  .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                  .onClick(() => {
                    this.editingServer = false;
                  })
              }
              .width('100%').margin({ top: 8 })
              if (this.testResult.length > 0) {
                Text(this.testResult).fontSize(11)
                  .fontColor(this.testOk ? Colors.Cyan : Colors.Amber)
                  .margin({ top: 6 })
              }
              Text('⚠ 鸿蒙模拟器/真机不能用 10.0.2.2 或 localhost,要填电脑的局域网 IPv4')
                .fontSize(10).fontColor(Colors.Amber).margin({ top: 6 })
              Text('电脑上 ipconfig 查 WLAN/以太网的 IPv4;服务端需监听 0.0.0.0:8000')
                .fontSize(10).fontColor(Colors.Cocoa).margin({ top: 2 })
            }
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(14)
          .borderRadius(16).backgroundColor(Colors.SurfaceWhite).margin({ top: 12 })

          // ———— 体验 ————
          Column() {
            Text('🎵 体验').fontSize(14).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)

            Row() {
              Column() {
                Text('音效').fontSize(13).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
                Text('点击 / 通关 / 检查出错的提示音').fontSize(10).fontColor(Colors.Cocoa)
                  .margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)

              Toggle({ type: ToggleType.Switch, isOn: this.soundOn })
                .selectedColor(Colors.Coral)
                .onChange((isOn: boolean) => {
                  this.soundOn = isOn;
                  LocalSettings.saveSoundEffectsEnabled(isOn);
                  if (isOn) {
                    SoundEffectManager.click();
                  }
                })
            }
            .width('100%').padding({ top: 10, bottom: 6 })

            Row() {
              Column() {
                Text('背景音乐').fontSize(13).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
                Text('循环播放 BGM(退到后台自动暂停)').fontSize(10).fontColor(Colors.Cocoa)
                  .margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)

              Toggle({ type: ToggleType.Switch, isOn: this.bgmOn })
                .selectedColor(Colors.Coral)
                .onChange(async (isOn: boolean) => {
                  this.bgmOn = isOn;
                  await LocalSettings.saveBackgroundMusicEnabled(isOn);
                  const ctx = getContext(this) as common.Context;
                  await BackgroundMusicManager.onEnabledChanged(ctx, isOn);
                })
            }
            .width('100%').padding({ top: 6, bottom: 6 })

            Row() {
              Column() {
                Text('摇一摇重置棋盘').fontSize(13).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
                Text('游戏页摇晃手机重置棋盘(加速度传感器)').fontSize(10).fontColor(Colors.Cocoa)
                  .margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)

              Toggle({ type: ToggleType.Switch, isOn: this.shakeOn })
                .selectedColor(Colors.Coral)
                .onChange((isOn: boolean) => {
                  this.shakeOn = isOn;
                  LocalSettings.saveShakeEnabled(isOn);
                })
            }
            .width('100%').padding({ top: 6 })
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(14)
          .borderRadius(16).backgroundColor(Colors.SurfaceWhite).margin({ top: 12 })

          // ———— 演示模式 ————
          Column() {
            Text('🧪 演示模式').fontSize(14).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
            Row() {
              Column() {
                Text('解锁全部内置关').fontSize(13).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
                Text('答辩/演示用,跳过顺序解锁').fontSize(10).fontColor(Colors.Cocoa)
                  .margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)

              Toggle({ type: ToggleType.Switch, isOn: this.demoUnlock })
                .selectedColor(Colors.Coral)
                .onChange((isOn: boolean) => {
                  this.demoUnlock = isOn;
                  LocalSettings.saveDemoAllUnlocked(isOn);
                })
            }
            .width('100%').padding({ top: 10 })
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(14)
          .borderRadius(16).backgroundColor(Colors.SurfaceWhite).margin({ top: 12 })

          // ———— 账号 ————
          Column() {
            Text('👤 账号').fontSize(14).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
            Text(this.isGuest() ? '游客(离线)'
              : (this.nickname.length > 0 ? this.nickname : '已登录'))
              .fontSize(14).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
              .margin({ top: 8 })
            if (!this.isGuest() && this.email.length > 0) {
              Text(`登录邮箱:${this.email}`).fontSize(11).fontColor(Colors.Cocoa).margin({ top: 4 })
            }

            if (!this.isGuest() && !this.editingNickname) {
              Button('修改昵称').fontSize(12).height(34).margin({ top: 10 })
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .onClick(() => {
                  this.nicknameInput = this.nickname;
                  this.editingNickname = true;
                })
            }
            if (!this.isGuest() && this.editingNickname) {
              TextInput({ text: this.nicknameInput, placeholder: '新昵称(≤20 字)' })
                .maxLength(20).fontSize(13).height(44).margin({ top: 10 })
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .placeholderColor(Colors.Cocoa).borderRadius(10)
                .onChange((value: string) => {
                  this.nicknameInput = value;
                })
              Row() {
                Button('保存').fontSize(13).height(36).layoutWeight(1)
                  .backgroundColor(Colors.Coral).enabled(!this.busy)
                  .onClick(async () => {
                    if (this.busy) {
                      return;
                    }
                    this.busy = true;
                    try {
                      await AuthManager.changeNickname(this.nicknameInput.trim());
                      this.editingNickname = false;
                      promptAction.showToast({ message: '昵称已更新' });
                    } catch (e) {
                      const err = e as Error;
                      promptAction.showToast({ message: err.message });
                    } finally {
                      this.busy = false;
                    }
                  })
                Button('取消').fontSize(13).height(36).layoutWeight(1).margin({ left: 8 })
                  .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                  .onClick(() => {
                    this.editingNickname = false;
                  })
              }
              .width('100%').margin({ top: 8 })
            }

            Button(this.isGuest() ? '返回登录页' : '退出登录')
              .width('100%').height(46).fontSize(14).margin({ top: 14 })
              .backgroundColor(Colors.SurfaceWhite)
              .fontColor(Colors.Danger)
              .enabled(!this.busy)
              .onClick(async () => {
                if (this.busy) {
                  return;
                }
                this.busy = true;
                // 先请服务端作废令牌(离线也照常退出)
                await AuthManager.logoutAndRevoke();
                this.busy = false;
                this.onLoggedOut();
              })
          }
          .width('100%').alignItems(HorizontalAlign.Start).padding(14)
          .borderRadius(16).backgroundColor(Colors.SurfaceWhite).margin({ top: 12 })

          Blank().height(24)
        }
        .width('100%')
        .padding({ left: 16, right: 16 })
      }
      .layoutWeight(1)
      .align(Alignment.Top)
    }
    .width('100%')
    .height('100%')
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }
}
