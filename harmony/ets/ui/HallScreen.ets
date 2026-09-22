import { PuzzleMeta } from '../api/Dtos';
import { AuthManager } from '../auth/AuthManager';
import { PuzzleRepository } from '../data/PuzzleRepository';
import { Level } from '../model/Level';
import { Colors, PAGE_GRADIENT, PRIMARY_GRADIENT } from '../theme/Colors';
import { ScreenTopBar } from './Widgets';
import { BoardView } from './BoardView';

/**
 * 题库(对应 Android 版 ui/HallScreen.kt):按难度分页拉取在线关卡。
 *
 * 在线关(id 为负)与内置关共用 Model/玩法,拿到后直接进游戏页;
 * 玩过的关会写进本地缓存,断网也能重玩(见 LevelCache)。
 */
@Component
export struct HallScreen {
  onPlay: (level: Level) => void = () => {
  };
  onBack: () => void = () => {
  };

  @State private difficulty: string = '';
  @State private items: PuzzleMeta[] = [];
  @State private total: number = 0;
  @State private loading: boolean = true;
  @State private loadingMore: boolean = false;
  @State private error: string = '';
  /** 拉取失败(通常是连不上服务器):显示全屏失败态 + 重试,而不是一行小字。 */
  @State private failed: boolean = false;
  @State private opening: boolean = false;
  /** 正在打开的那道题 id(-1 = 无):让被点的那行转圈并防连点。 */
  @State private openingId: number = -1;

  private static readonly PAGE_SIZE: number = 12;

  aboutToAppear(): void {
    this.reload();
  }

  private async reload(): Promise<void> {
    this.loading = true;
    this.error = '';
    this.failed = false;
    this.items = [];
    try {
      const resp = await PuzzleRepository.fetchBank(
        this.difficulty.length === 0 ? null : this.difficulty, 0, HallScreen.PAGE_SIZE);
      this.items = resp.items;
      this.total = resp.total;
    } catch (e) {
      const err = e as Error;
      this.error = err.message;
      this.failed = true;
    } finally {
      this.loading = false;
    }
  }

  private async loadMore(): Promise<void> {
    if (this.loadingMore || this.items.length >= this.total) {
      return;
    }
    this.loadingMore = true;
    try {
      const resp = await PuzzleRepository.fetchBank(
        this.difficulty.length === 0 ? null : this.difficulty,
        this.items.length, HallScreen.PAGE_SIZE);
      this.items = this.items.concat(resp.items);
      this.total = resp.total;
    } catch (e) {
      const err = e as Error;
      this.error = err.message;
    } finally {
      this.loadingMore = false;
    }
  }

  private async open(meta: PuzzleMeta): Promise<void> {
    if (this.opening) {
      return;
    }
    this.opening = true;
    this.openingId = meta.id;
    try {
      const level: Level = await PuzzleRepository.fetchLevel(meta.id);
      this.onPlay(level);
    } catch (e) {
      const err = e as Error;
      this.error = `打开关卡失败:${err.message}`;
    } finally {
      this.opening = false;
      this.openingId = -1;
    }
  }

  build() {
    Column() {
      ScreenTopBar({
        title: '题库',
        subtitle: this.total > 0 ? `共 ${this.total} 题 · 服务端即时生成` : '服务端即时生成',
        onBack: this.onBack,
      })

      // 难度筛选
      Row() {
        this.filterChip('全部', '')
        this.filterChip('简单', 'EASY')
        this.filterChip('中等', 'MEDIUM')
        this.filterChip('困难', 'HARD')
      }
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 8 })

      if (AuthManager.isGuest) {
        Text('游客为离线身份,题库需要登录后使用 —— 可以先玩内置关')
          .fontSize(12).fontColor(Colors.Amber)
          .padding({ left: 16, right: 16, bottom: 8 })
      }

      if (this.error.length > 0) {
        Text(`⚠ ${this.error}`).fontSize(12).fontColor(Colors.Danger)
          .padding({ left: 16, right: 16, bottom: 6 })
      }

      if (this.failed) {
        // ———— 连不上服务器:全屏失败态 + 重试(与安卓一致,给用户恢复路径)————
        Column() {
          Text('🌐').fontSize(30)
          Text('无法连接服务器').fontSize(14).fontWeight(FontWeight.Bold)
            .fontColor(Colors.Ink).margin({ top: 10 })
          Text(`检查网络,或到「设置」确认服务器地址(当前:${AuthManager.serverHost()})`)
            .fontSize(11).fontColor(Colors.Cocoa).margin({ top: 6 })
            .textAlign(TextAlign.Center)
          Button('🔄 重试')
            .fontSize(14).height(42).margin({ top: 16 })
            .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
            .onClick(() => {
              this.reload();
            })
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center).padding(32)
      } else if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Colors.Coral)
          Text('正在取题…').fontSize(12).fontColor(Colors.Cocoa).margin({ top: 8 })
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center)
      } else if (this.items.length === 0) {
        // ———— 空列表占位(以前是一片空白,让人以为坏了)————
        Column() {
          Text('🧩').fontSize(30)
          Text('该难度题库还在生成中,稍后再来看看')
            .fontSize(12).fontColor(Colors.Cocoa).margin({ top: 10 })
            .textAlign(TextAlign.Center)
          Button('🔄 刷新')
            .fontSize(13).height(38).margin({ top: 14 })
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => {
              this.reload();
            })
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center).padding(32)
      } else {
        List() {
          ForEach(this.items, (meta: PuzzleMeta) => {
            ListItem() {
              Row() {
                Column() {
                  Text(meta.name).fontSize(15).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
                  Text(`${meta.difficulty} · ${meta.rows}×${meta.cols}` +
                    (meta.solves === undefined ? '' : ` · 已解 ${meta.solves}`))
                    .fontSize(11).fontColor(Colors.Cocoa).margin({ top: 2 })
                }
                .alignItems(HorizontalAlign.Start)
                .layoutWeight(1)

                // 正在打开的那行转圈(大棋盘下载期间给个反馈,防连点)
                if (this.openingId === meta.id) {
                  LoadingProgress().width(22).height(22).color(Colors.Coral)
                } else {
                  Text('开始 ▶').fontSize(12).fontColor(Colors.Coral)
                }
              }
              .width('100%')
              .padding(14)
              .borderRadius(14)
              .backgroundColor(Colors.SurfaceWhite)
              .onClick(() => this.open(meta))
            }
          }, (meta: PuzzleMeta) => meta.id.toString())

          if (this.items.length < this.total) {
            ListItem() {
              Button(this.loadingMore ? '加载中…' : '加载更多')
                .width('100%').height(44).fontSize(13)
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .enabled(!this.loadingMore)
                .onClick(() => this.loadMore())
            }
          }
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 16, right: 16 })
      }
    }
    .width('100%')
    .height('100%')
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }

  @Builder
  filterChip(label: string, value: string) {
    Button(label)
      .fontSize(12)
      .height(32)
      .margin({ right: 8 })
      .backgroundColor(this.difficulty === value ? Colors.Coral : Colors.SurfaceWhite)
      .fontColor(this.difficulty === value ? Color.White : Colors.Cocoa)
      .onClick(() => {
        this.difficulty = value;
        this.reload();
      })
  }
}
