import { promptAction } from '@kit.ArkUI';
import { AuthManager } from '../auth/AuthManager';
import { AccountStore } from '../data/AccountStore';
import { LocalSettings } from '../data/LocalSettings';
import { PuzzleRepository } from '../data/PuzzleRepository';
import { GameMode } from '../model/GameMode';
import { Level, difficultyLabel, difficultyStars } from '../model/Level';
import { Levels } from '../model/Levels';
import { levelFromDto } from '../api/LevelMapper';
import { Colors, PAGE_GRADIENT } from '../theme/Colors';
import { GradientHero, ProgressBar, ScreenTopBar, SegmentedControl } from './Widgets';

/**
 * 首页(对应 Android 版 ui/HomeScreen.kt)。
 *
 * 区块:品牌头卡 + 进度 + 补齐额度 + 每日一题卡 + 内置关宫格 + 四个入口
 * (题库 / 排行榜 / 对战 / 设置)。游客只能玩内置关,在线区块会提示去登录。
 */
@Component
export struct HomeScreen {
  account: AccountStore | null = null;
  onPlayLevel: (level: Level, mode: GameMode) => void = () => {
  };
  onPlayDaily: (level: Level) => void = () => {
  };
  onOpenHall: () => void = () => {
  };
  onOpenLeaderboard: () => void = () => {
  };
  onOpenBattle: () => void = () => {
  };
  onOpenSettings: () => void = () => {
  };
  onLogout: () => void = () => {
  };

  @State private mode: number = GameMode.FREE as number;
  @State private daily: Level | null = null;
  @State private dailyLoading: boolean = true;
  @State private dailyError: boolean = false;
  @State private dailyName: string = '';
  @State private completedCount: number = 0;
  @State private quotaRemaining: number = 0;
  @StorageProp('nickname') private nickname: string = '';
  @StorageProp('loggedIn') private loggedIn: boolean = false;

  aboutToAppear(): void {
    this.refreshAccount();
    this.loadDaily();
  }

  private refreshAccount(): void {
    const account: AccountStore | null = this.account;
    if (account === null) {
      return;
    }
    this.quotaRemaining = account.fillQuotaRemaining;
    const all: Level[] = Levels.all;
    let done: number = 0;
    for (let i = 0; i < all.length; i++) {
      if (account.completedLevels.has(all[i].id)) {
        done++;
      }
    }
    this.completedCount = done;
  }

  private isGuest(): boolean {
    return AuthManager.isGuest;
  }

  private async loadDaily(): Promise<void> {
    if (this.isGuest()) {
      this.dailyLoading = false;
      this.dailyError = true;
      return;
    }
    this.dailyLoading = true;
    this.dailyError = false;
    try {
      const resp = await PuzzleRepository.fetchDaily();
      this.daily = levelFromDto(resp.level);
      this.dailyName = resp.level.name;
      this.dailyError = false;
    } catch (e) {
      this.daily = null;
      this.dailyError = true;
    } finally {
      this.dailyLoading = false;
    }
  }

  private isUnlocked(levelId: number): boolean {
    const account: AccountStore | null = this.account;
    if (LocalSettings.demoAllUnlocked()) {
      return true; // 演示模式:一键放开全部内置关
    }
    return account === null ? levelId === 1 : account.isUnlocked(levelId);
  }

  private isCompleted(levelId: number): boolean {
    const account: AccountStore | null = this.account;
    return account !== null && account.completedLevels.has(levelId);
  }

  build() {
    Column() {
      ScreenTopBar({
        title: '像素填空',
        subtitle: this.isGuest() ? '游客(离线)· 仅内置关' : `欢迎,${this.nickname}`,
        onBack: () => this.onLogout(),
      })

      Scroll() {
        Column() {
          GradientHero({
            title: '像素填空',
            subtitle: '用数字线索还原像素画 · 康思谜题玩法',
            emoji: '🖼',
          })

          // ———— 游客提示条:给一个明确的「去登录」入口 ————
          if (this.isGuest()) {
            Row() {
              Column() {
                Text('👤 当前是游客身份(离线)').fontSize(13).fontWeight(FontWeight.Bold)
                  .fontColor(Colors.Ink)
                Text('登录后可玩每日一题 / 题库 / 排行榜 / 网络对战')
                  .fontSize(10).fontColor(Colors.Cocoa).margin({ top: 2 })
              }
              .alignItems(HorizontalAlign.Start)
              .layoutWeight(1)

              Button('邮箱登录')
                .fontSize(13).height(38)
                .backgroundColor(Colors.Coral)
                .onClick(() => this.onLogout())
            }
            .width('100%')
            .padding(14)
            .borderRadius(16)
            .backgroundColor(Colors.SurfaceWhite)
            .margin({ top: 14 })
          }

          // ———— 进度 + 额度 ————
          Row() {
            Column() {
              ProgressBar({
                current: this.completedCount,
                total: Levels.count(),
                label: `通关进度 ${this.completedCount}/${Levels.count()}`,
              })
            }
            .layoutWeight(2)

            Column() {
              Text(`⚡ 补齐额度 ${this.quotaRemaining}/6`)
                .fontSize(11)
                .fontColor(Colors.Amber)
              Text('每日登录 +2')
                .fontSize(10)
                .fontColor(Colors.Cocoa)
                .margin({ top: 2 })
              if (!this.isGuest()) {
                Button('切换账号')
                  .fontSize(11).height(32).margin({ top: 6 })
                  .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                  .onClick(() => this.onLogout())
              }
            }
            .alignItems(HorizontalAlign.End)
            .layoutWeight(1)
          }
          .width('100%')
          .padding(14)
          .borderRadius(16)
          .backgroundColor(Colors.SurfaceWhite)
          .margin({ top: 14 })

          // ———— 每日一题 ————
          Column() {
            Row() {
              Text('📅 每日一题').fontSize(15).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
              Blank().layoutWeight(1)
              if (this.dailyLoading) {
                LoadingProgress().width(18).height(18).color(Colors.Coral)
              }
            }
            .width('100%')

            if (this.isGuest()) {
              Text('游客为离线身份;登录后可玩每日一题并上榜')
                .fontSize(12).fontColor(Colors.Cocoa).margin({ top: 8 })
            } else if (this.dailyLoading) {
              Text('正在获取今日题目…').fontSize(12).fontColor(Colors.Cocoa).margin({ top: 8 })
            } else if (this.daily !== null) {
              Text(`今日谜题 · ${difficultyLabel(this.daily.difficulty)} ${this.daily.rows}×${this.daily.cols}`)
                .fontSize(12).fontColor(Colors.Cocoa).margin({ top: 8 })
              Text('全服同题 · 谁最快谁上榜').fontSize(10).fontColor(Colors.Cocoa).margin({ top: 2 })
              Button('开始挑战 ▶')
                .width('100%').height(42).fontSize(14).margin({ top: 10 })
                .backgroundColor(Colors.Coral)
                .onClick(() => {
                  const today: Level | null = this.daily;
                  if (today !== null) {
                    this.onPlayDaily(today);
                  }
                })
            } else {
              Text('离线中:点此重试连接服务器')
                .fontSize(12).fontColor(Colors.Danger).margin({ top: 8 })
              Button('重试')
                .height(36).fontSize(12).margin({ top: 8 })
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .onClick(() => this.loadDaily())
            }
          }
          .width('100%')
          .padding(14)
          .borderRadius(16)
          .backgroundColor(Colors.SurfaceWhite)
          .margin({ top: 12 })

          // ———— 模式切换(分段控件,与安卓视觉一致)————
          SegmentedControl({
            options: ['🎮 自由模式', '⏱ 限时挑战'],
            selected: this.mode === (GameMode.TIMED as number) ? 1 : 0,
            onSelect: (index: number) => {
              this.mode = index === 1 ? (GameMode.TIMED as number) : (GameMode.FREE as number);
            },
          })
          .width('100%')
          .margin({ top: 16 })

          // ———— 内置关宫格 ————
          Grid() {
            ForEach(Levels.all, (level: Level) => {
              GridItem() {
                Column() {
                  Text(this.isCompleted(level.id) ? '✅' : (this.isUnlocked(level.id) ? '🎯' : '🔒'))
                    .fontSize(22)
                  // 未通关只显示"第 N 关":直接亮出关卡名会把像素画的谜底提前泄露
                  Text(this.isCompleted(level.id) ? `图案:${level.name}` : `第 ${level.id} 关`)
                    .fontSize(13).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
                    .margin({ top: 4 })
                  Text(`${difficultyLabel(level.difficulty)} ${'★'.repeat(difficultyStars(level.difficulty))}`)
                    .fontSize(10).fontColor(Colors.Cocoa).margin({ top: 2 })
                  Text(`${level.rows}×${level.cols}`)
                    .fontSize(10).fontColor(Colors.Cocoa)
                }
                .width('100%')
                .padding(10)
                .borderRadius(14)
                .backgroundColor(this.isUnlocked(level.id) ? Colors.SurfaceWhite : '#16141F')
                .opacity(this.isUnlocked(level.id) ? 1 : 0.55)
                .onClick(() => {
                  if (!this.isUnlocked(level.id)) {
                    promptAction.showToast({ message: `先通关第 ${level.id - 1} 关` });
                    return;
                  }
                  this.onPlayLevel(level, this.mode === (GameMode.TIMED as number)
                    ? GameMode.TIMED
                    : GameMode.FREE);
                })
              }
            }, (level: Level) => level.id.toString())
          }
          .columnsTemplate('1fr 1fr 1fr')
          .columnsGap(10)
          .rowsGap(10)
          .width('100%')
          .height(this.gridHeight())
          .margin({ top: 12 })

          // ———— 入口 ————
          Row() {
            this.entryButton('📚 题库', 0, () => this.onOpenHall())
            this.entryButton('🏆 排行榜', 10, () => this.onOpenLeaderboard())
          }
          .width('100%').margin({ top: 16 })

          Row() {
            this.entryButton('⚔️ 对战', 0, () => this.onOpenBattle())
            this.entryButton('⚙️ 设置', 10, () => this.onOpenSettings())
          }
          .width('100%').margin({ top: 10 })

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

  @Builder
  entryButton(label: string, leftMargin: number, action: () => void) {
    Button(label)
      .fontSize(14)
      .height(52)
      .layoutWeight(1)
      .backgroundColor(Colors.SurfaceWhite)
      .fontColor(Colors.Ink)
      .margin({ left: leftMargin })
      .onClick(action)
  }

  /** 宫格高度:10 关 3 列 → 4 行,每行约 92vp。 */
  private gridHeight(): number {
    const rows: number = Math.ceil(Levels.count() / 3);
    return rows * 96;
  }
}
