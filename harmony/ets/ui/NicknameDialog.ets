import { promptAction } from '@kit.ArkUI';
import { ApiException } from '../api/HttpClient';
import { AuthManager } from '../auth/AuthManager';
import { Colors, PRIMARY_GRADIENT } from '../theme/Colors';

/**
 * 昵称弹窗(对应 Android 版 ui/Widgets.kt 的 NicknameDialog)。
 *
 * 两处复用:① 首次邮箱登录后的引导;② 设置页手动修改。
 * ArkUI 里用 Stack 全屏遮罩实现(不用 AlertDialog 的自定义内容,行为更可控):
 * 点遮罩可关闭(提交中不可关),本地校验长度 → 提交服务端 → 成功回调 onDone。
 */
@Component
export struct NicknameDialog {
  /** 当前昵称(预填)。 */
  current: string = '';
  title: string = '修改昵称';
  hint: string = '排行榜、对局名次等展示用昵称,提交后即时生效';
  /** 关闭(取消)。 */
  onDismiss: () => void = () => {
  };
  /** 提交成功。 */
  onDone: () => void = () => {
  };

  @State private name: string = '';
  @State private busy: boolean = false;
  @State private error: string = '';

  aboutToAppear(): void {
    this.name = this.current;
  }

  private async submit(): Promise<void> {
    const trimmed: string = this.name.trim();
    if (trimmed.length === 0) {
      this.error = '昵称不能为空';
      return;
    }
    if (trimmed.length > 20) {
      this.error = '昵称最多 20 个字符';
      return;
    }
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.error = '';
    try {
      await AuthManager.changeNickname(trimmed);
      promptAction.showToast({ message: `昵称已更新:${trimmed}` });
      this.onDone();
    } catch (e) {
      const err = e as ApiException;
      this.error = err.message;
    } finally {
      this.busy = false;
    }
  }

  build() {
    Column() {
      Column() {
        Text(this.title)
          .fontSize(17)
          .fontWeight(FontWeight.Bold)
          .fontColor(Colors.Ink)
        TextInput({ text: this.name, placeholder: '昵称(≤20 字)' })
          .maxLength(20)
          .fontSize(15)
          .height(48)
          .margin({ top: 14 })
          .backgroundColor(Colors.Sand)
          .fontColor(Colors.Ink)
          .placeholderColor(Colors.Cocoa)
          .borderRadius(12)
          .enabled(!this.busy)
          .onChange((value: string) => {
            this.name = value;
            this.error = '';
          })
        if (this.error.length > 0) {
          Text(`⚠ ${this.error}`)
            .fontSize(12)
            .fontColor(Colors.Danger)
            .margin({ top: 8 })
        }
        Text(this.hint)
          .fontSize(11)
          .fontColor(Colors.Cocoa)
          .margin({ top: 10 })
        Row() {
          Button(this.busy ? '提交中…' : '保存')
            .fontSize(15)
            .height(44)
            .layoutWeight(1)
            .enabled(!this.busy)
            .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
            .onClick(() => this.submit())
          Button('稍后')
            .fontSize(15)
            .height(44)
            .layoutWeight(1)
            .margin({ left: 10 })
            .backgroundColor(Colors.Sand)
            .fontColor(Colors.Ink)
            .enabled(!this.busy)
            .onClick(() => this.onDismiss())
        }
        .width('100%')
        .margin({ top: 18 })
      }
      .width('86%')
      .padding(22)
      .borderRadius(20)
      .backgroundColor(Colors.SurfaceWhite)
      .border({ width: 1, color: Colors.GlassLine })
      // 吃掉冒泡上来的点击:ArkTS 的 ClickEvent 没有 stopPropagation,
      // ArkUI 里"内层也挂一个 onClick"即可阻止事件继续冒泡到遮罩。
      .onClick(() => {
      })
    }
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Center)
    .backgroundColor('#99000000')
    .onClick(() => {
      // 点遮罩关闭;提交中不响应
      if (!this.busy) {
        this.onDismiss();
      }
    })
  }
}
