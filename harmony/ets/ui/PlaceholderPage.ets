import { Colors, PAGE_GRADIENT } from '../theme/Colors';
import { ScreenTopBar } from './Widgets';

/**
 * 占位页:题库 / 排行榜 / 对战 / 设置 在各自里程碑完成前使用,
 * 保证返回链路可用(根页面栈行为与最终版本一致)。
 */
@Component
export struct PlaceholderPage {
  title: string = '';
  onBack: () => void = () => {
  };

  build() {
    Column() {
      ScreenTopBar({ title: this.title, onBack: this.onBack })
      Column() {
        Text('🚧').fontSize(34)
        Text(`${this.title} · 正在按里程碑移植`)
          .fontSize(13).fontColor(Colors.Cocoa).margin({ top: 10 })
      }
      .layoutWeight(1)
      .width('100%')
      .justifyContent(FlexAlign.Center)
    }
    .width('100%')
    .height('100%')
    .padding({ left: 16, right: 16 })
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }
}
