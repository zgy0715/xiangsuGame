import { Colors, HERO_GRADIENT, PRIMARY_GRADIENT } from '../theme/Colors';
import { difficultyLabel, difficultyStars } from '../model/Level';

/**
 * 共享控件(对应 Android 版 ui/Widgets.kt 的常用部分)。
 * 鸿蒙版按 ArkUI 的习惯做成 @Builder 函数与小组件,样式与安卓端对齐。
 */

/** 顶栏:标题 + 副标题 + 返回。 */
@Component
export struct ScreenTopBar {
  title: string = '';
  subtitle: string = '';
  onBack: () => void = () => {
  };

  build() {
    Row() {
      Button('←')
        .fontSize(18)
        .backgroundColor(Color.Transparent)
        .fontColor(Colors.Ink)
        .width(44)
        .height(44)
        .onClick(() => this.onBack())
      Column() {
        Text(this.title)
          .fontSize(18)
          .fontWeight(FontWeight.Bold)
          .fontColor(Colors.Ink)
        if (this.subtitle.length > 0) {
          Text(this.subtitle)
            .fontSize(11)
            .fontColor(Colors.Cocoa)
            .margin({ top: 2 })
        }
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)

      Blank().width(44)
    }
    .width('100%')
    .padding({ left: 4, right: 4, top: 4, bottom: 8 })
    .alignItems(VerticalAlign.Center)
  }
}

/** 品牌渐变头卡(首页/登录页复用)。 */
@Component
export struct GradientHero {
  title: string = '';
  subtitle: string = '';
  emoji: string = '🖼';

  build() {
    Column() {
      Row() {
        Text(this.emoji).fontSize(30)
        Column() {
          Text(this.title)
            .fontSize(24)
            .fontWeight(FontWeight.Bolder)
            .fontColor(Color.White)
          if (this.subtitle.length > 0) {
            Text(this.subtitle)
              .fontSize(12)
              .fontColor('#E3DEFF')
              .margin({ top: 4 })
          }
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
        .margin({ left: 12 })
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
    }
    .width('100%')
    .padding({ left: 20, right: 20, top: 26, bottom: 26 })
    .borderRadius(22)
    .linearGradient({ angle: 135, colors: HERO_GRADIENT })
  }
}

/** 进度条(已通关 / 总关卡)。 */
@Component
export struct ProgressBar {
  current: number = 0;
  total: number = 1;
  label: string = '';

  build() {
    Column() {
      if (this.label.length > 0) {
        Text(this.label).fontSize(11).fontColor(Colors.Cocoa).margin({ bottom: 4 })
      }
      Row() {
        Row()
          .width(`${this.total <= 0 ? 0 : Math.round(this.current * 100 / this.total)}%`)
          .height(8)
          .borderRadius(4)
          .backgroundColor(Colors.Coral)
        Blank().layoutWeight(1)
      }
      .width('100%')
      .height(8)
      .borderRadius(4)
      .backgroundColor(Colors.Sand)
    }
    .width('100%')
  }
}

/**
 * 像素画预览(对应 Android 版 BoardView.kt 里的 PicturePreview):
 * 把答案矩阵画成一小块像素图 —— 只有涂黑格,其余留空,方便放在任意底色上。
 *
 * 刻意**不**复用 BoardView:后者会创建离屏画布缓存,通关瞬间再建一个代价偏高;
 * 这里只需要"画一堆小方块"。
 */
@Component
export struct PicturePreview {
  answer: number[][] = [];
  /** 墨色,默认暖金(深色底上清晰)。 */
  ink: string = '#FFC85C';

  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(
    new RenderingContextSettings(true));
  private w: number = 0;
  private h: number = 0;

  private draw(): void {
    const rows: number = this.answer.length;
    if (rows === 0 || this.w <= 0 || this.h <= 0) {
      return;
    }
    const cols: number = this.answer[0].length;
    if (cols === 0) {
      return;
    }
    const cw: number = this.w / cols;
    const ch: number = this.h / rows;
    this.ctx.clearRect(0, 0, this.w, this.h);
    this.ctx.fillStyle = this.ink;
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        if (this.answer[r][c] === 1) {
          // 多画 0.5 像素,避免相邻方块之间出现细缝
          this.ctx.fillRect(c * cw, r * ch, cw + 0.5, ch + 0.5);
        }
      }
    }
  }

  build() {
    Canvas(this.ctx)
      .width('100%')
      .height('100%')
      .onReady(() => {
        this.draw();
      })
      .onAreaChange((_o: Area, n: Area) => {
        this.w = Number.parseFloat(n.width.toString());
        this.h = Number.parseFloat(n.height.toString());
        this.draw();
      })
  }
}

/** 难度星级(★)。 */
export function starsOf(difficulty: number): string {
  return '★'.repeat(difficultyStars(difficulty));
}

/** 难度中文名。 */
export function difficultyText(difficulty: number): string {
  return difficultyLabel(difficulty);
}

/**
 * 分段控件(对应 Android 版 Widgets.kt 的 SegmentedControl)。
 *
 * 用法:传选项数组与当前下标,点哪段回调哪个下标 ——
 * ArkTS 的 @Builder 不能带渲染 lambda,所以这里用"数据 + 回调"的写法。
 */
@Component
export struct SegmentedControl {
  options: string[] = [];
  selected: number = 0;
  onSelect: (index: number) => void = () => {
  };

  build() {
    Row() {
      ForEach(this.options, (label: string, index: number) => {
        Button(label)
          .fontSize(12)
          .height(34)
          .layoutWeight(1)
          .margin({ left: index === 0 ? 0 : 6 })
          .backgroundColor(index === this.selected ? Colors.Coral : Colors.Sand)
          .fontColor(index === this.selected ? '#FFFFFF' : Colors.Cocoa)
          .onClick(() => this.onSelect(index))
      }, (label: string, index: number) => `${index}-${label}`)
    }
    .width('100%')
  }
}

/**
 * 品牌渐变主按钮(对应 Android 版 GradientButton)。
 * 统一主操作的视觉:靛紫渐变 + 白字。
 */
@Component
export struct GradientButton {
  text: string = '';
  widthPercent: string = '100%';
  heightVp: number = 48;
  onTap: () => void = () => {
  };

  build() {
    Button(this.text)
      .width(this.widthPercent)
      .height(this.heightVp)
      .fontSize(15)
      .fontWeight(FontWeight.Bold)
      .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
      .onClick(() => this.onTap())
  }
}

/**
 * 玻璃小徽标(对应 Android 版 GlassChip):如「👑 房主」。
 */
@Component
export struct GlassChip {
  text: string = '';

  build() {
    Text(this.text)
      .fontSize(10)
      .fontColor(Colors.Ink)
      .backgroundColor(Colors.GlassFill)
      .border({ width: 1, color: Colors.GlassLine })
      .borderRadius(10)
      .padding({ left: 8, right: 8, top: 3, bottom: 3 })
  }
}
