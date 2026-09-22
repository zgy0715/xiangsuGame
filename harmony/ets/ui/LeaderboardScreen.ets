import { BoardEntry, LevelDto, MyBest, PlayedPuzzle } from '../api/Dtos';
import { AuthManager } from '../auth/AuthManager';
import { PuzzleRepository } from '../data/PuzzleRepository';
import { Level, difficultyFromName, difficultyLabel } from '../model/Level';
import { Levels } from '../model/Levels';
import { Colors, PAGE_GRADIENT, PRIMARY_GRADIENT } from '../theme/Colors';
import { ScreenTopBar, SegmentedControl } from './Widgets';

/** 榜单目标:一道题(今日一题 / 我玩过的某题)。 */
class BoardTarget {
  readonly puzzleId: number;
  readonly name: string;
  readonly subtitle: string;

  constructor(puzzleId: number, name: string, subtitle: string) {
    this.puzzleId = puzzleId;
    this.name = name;
    this.subtitle = subtitle;
  }
}

/**
 * 排行榜(对应 Android 版 ui/LeaderboardScreen.kt)。
 *
 * 关键点(安卓端踩过的坑):榜单**必须能切换题目** —— 之前页面写死"今日每日一题",
 * 而对战成绩往往发生在别的题上,于是「对战榜」永远是空的。
 * 这里提供:今日一题 + 「我玩过的题」(服务端 my-puzzles),并可切换 单人榜 / 对战榜。
 */
@Component
export struct LeaderboardScreen {
  onBack: () => void = () => {
  };

  @State private targets: BoardTarget[] = [];
  @State private selected: number = 0;
  /**
   * 榜单口径:空串 = 综合最快(单人 + 对战全部),与服务端默认一致。
   * 安卓端就是这么传的 —— 之前鸿蒙固定传 'single',导致同一道题两端榜单不一致。
   */
  @State private source: string = '';
  @State private entries: BoardEntry[] = [];
  @State private myBest: MyBest | null = null;
  @State private loading: boolean = true;
  @State private error: string = '';
  /** 是否处于"连不上服务器"的失败态(与"这道题没成绩"区分开)。 */
  @State private failed: boolean = false;
  @StorageProp('userId') private userId: number = -1;

  aboutToAppear(): void {
    this.loadTargets();
  }

  /** 榜单目标 = 今日一题 + 我留下过成绩的题(默认选中最近打过的那题)。 */
  private async loadTargets(): Promise<void> {
    this.loading = true;
    this.error = '';
    const list: BoardTarget[] = [];
    try {
      const daily = await PuzzleRepository.fetchDaily();
      const dto: LevelDto = daily.level;
      list.push(new BoardTarget(dto.id, '今日一题',
        `${difficultyLabel(difficultyFromName(dto.difficulty))} ${dto.rows}×${dto.cols}`));
    } catch (e) {
      // 拿不到每日一题不致命:后面还有"我玩过的题"
    }
    try {
      const mine = await AuthManager.api().myPuzzles(20);
      const played: PlayedPuzzle[] = mine.items === undefined ? [] : mine.items;
      for (let i = 0; i < played.length; i++) {
        const item: PlayedPuzzle = played[i];
        if (list.some((t: BoardTarget) => t.puzzleId === item.puzzleId)) {
          continue;
        }
        const builtin: Level | null = item.puzzleId > 0 ? Levels.byId(item.puzzleId) : null;
        const name: string = builtin !== null
          ? builtin.name
          : (item.name === undefined || item.name === null ? `题目 ${item.puzzleId}` : item.name);
        const kind: string = item.puzzleId > 0 ? '内置关' : '在线题';
        list.push(new BoardTarget(item.puzzleId, name, `${kind} · 玩过 ${item.plays} 次`));
      }
    } catch (e) {
      const err = e as Error;
      this.error = err.message;
    }
    this.targets = list;
    this.selected = 0;
    this.loading = false;
    if (list.length > 0) {
      await this.loadBoard();
    }
  }

  private async loadBoard(): Promise<void> {
    if (this.targets.length === 0) {
      return;
    }
    this.loading = true;
    try {
      const target: BoardTarget = this.targets[this.selected];
      const resp = await AuthManager.api().leaderboard(target.puzzleId, this.source, 30);
      this.entries = resp.entries === undefined ? [] : resp.entries;
      this.myBest = resp.myBest === undefined ? null : resp.myBest;
      this.error = '';
      this.failed = false;
    } catch (e) {
      const err = e as Error;
      this.error = err.message;
      this.entries = [];
      this.myBest = null;
      // 连不上服务器时不能伪装成"这道题还没有成绩" —— 那是误导,而且用户找不到重试入口
      this.failed = true;
    } finally {
      this.loading = false;
    }
  }

  build() {
    Column() {
      ScreenTopBar({ title: '排行榜', subtitle: '每道题取每人最优成绩', onBack: this.onBack })

      // 综合最快 / 对战榜 / 单人榜(分段控件)
      SegmentedControl({
        options: ['综合最快', '对战榜', '单人榜'],
        selected: this.source === '' ? 0 : (this.source === 'race' ? 1 : 2),
        onSelect: (index: number) => {
          this.source = index === 0 ? '' : (index === 1 ? 'race' : 'single');
          this.loadBoard();
        },
      })
      .width('100%')
      .padding({ left: 16, right: 16, bottom: 8 })

      // 题目选择器(横向滚动)
      Scroll() {
        Row() {
          ForEach(this.targets, (t: BoardTarget, index: number) => {
            Column() {
              Text(t.name).fontSize(12).fontWeight(FontWeight.Bold)
                .fontColor(this.selected === index ? Color.White : Colors.Ink)
              Text(t.subtitle).fontSize(9)
                .fontColor(this.selected === index ? '#E3DEFF' : Colors.Cocoa)
                .margin({ top: 2 })
            }
            .padding({ left: 12, right: 12, top: 8, bottom: 8 })
            .margin({ right: 8 })
            .borderRadius(12)
            .backgroundColor(this.selected === index ? Colors.Coral : Colors.SurfaceWhite)
            .onClick(() => {
              this.selected = index;
              this.loadBoard();
            })
          }, (t: BoardTarget) => t.puzzleId.toString())
        }
        .padding({ left: 16, right: 16 })
      }
      .scrollable(ScrollDirection.Horizontal)
      .width('100%')
      .height(58)

      if (this.myBest !== null) {
        Text(`我的最佳:第 ${this.myBest.rank} 名 · ${this.formatMs(this.myBest.durationMs)}`)
          .fontSize(12).fontColor(Colors.Amber)
          .padding({ left: 16, top: 6, bottom: 6 })
      }

      if (this.failed) {
        // ———— 连不上服务器:全屏失败态 + 重试(不再伪装成"没有成绩")————
        Column() {
          Text('🌐').fontSize(30)
          Text('排行榜需要连接服务器').fontSize(14).fontWeight(FontWeight.Bold)
            .fontColor(Colors.Ink).margin({ top: 10 })
          Text(`当前服务器:${AuthManager.serverHost()}`)
            .fontSize(11).fontColor(Colors.Cocoa).margin({ top: 6 })
          Text(this.error).fontSize(10).fontColor(Colors.Cocoa).margin({ top: 4 })
            .textAlign(TextAlign.Center)
          Button('🔄 重试')
            .fontSize(14).height(42).margin({ top: 16 })
            .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
            .onClick(() => this.loadBoard())
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center).padding(32)
      } else if (this.loading) {
        Column() {
          LoadingProgress().width(40).height(40).color(Colors.Coral)
          Text('正在取榜…').fontSize(12).fontColor(Colors.Cocoa).margin({ top: 8 })
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center)
      } else if (this.entries.length === 0) {
        Column() {
          Text('📭').fontSize(28)
          Text(this.source === 'race'
            ? '这道题还没有对战成绩 —— 切换题目,或去对战大厅打一局'
            : (this.source === 'single'
              ? '这道题还没有单人成绩 —— 先去玩一局吧'
              : '这道题还没有任何成绩 —— 先去玩一局吧'))
            .fontSize(12).fontColor(Colors.Cocoa).textAlign(TextAlign.Center)
            .margin({ top: 10 })
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center)
        .padding({ left: 32, right: 32 })
      } else {
        List() {
          ForEach(this.entries, (entry: BoardEntry) => {
            ListItem() {
              Row() {
                Text(this.medal(entry.rank))
                  .fontSize(15).fontWeight(FontWeight.Bolder)
                  .fontColor(entry.rank <= 3 ? Colors.Amber : Colors.Cocoa)
                  .width(38)
                Text(entry.nickname)
                  .fontSize(14)
                  .fontColor(entry.userId === this.userId ? Colors.Cyan : Colors.Ink)
                  .layoutWeight(1)
                if (entry.userId === this.userId) {
                  Text('你')
                    .fontSize(10).fontColor(Color.White)
                    .backgroundColor(Colors.Coral)
                    .borderRadius(6)
                    .padding({ left: 6, right: 6, top: 1, bottom: 1 })
                    .margin({ right: 8 })
                }
                Text(this.formatMs(entry.durationMs)).fontSize(13).fontColor(Colors.Cocoa)
              }
              .width('100%')
              .padding(12)
              .borderRadius(12)
              .backgroundColor(entry.userId === this.userId ? Colors.GlassFill : Colors.SurfaceWhite)
              .border(entry.userId === this.userId
                ? { width: 1, color: Colors.Coral } : { width: 0, color: Color.Transparent })
            }
          }, (entry: BoardEntry) => entry.userId.toString())
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

  /** 前三名给奖牌(与安卓一致)。 */
  private medal(rank: number): string {
    if (rank === 1) {
      return '🥇';
    }
    if (rank === 2) {
      return '🥈';
    }
    if (rank === 3) {
      return '🥉';
    }
    return `${rank}`;
  }

  /** 用时:保留十分位(1:23.4),竞速榜上相邻名次才看得出差距。 */
  private formatMs(ms: number): string {
    const totalSeconds: number = Math.floor(ms / 1000);
    const m: number = Math.floor(totalSeconds / 60);
    const s: number = totalSeconds % 60;
    const tenth: number = Math.floor((ms % 1000) / 100);
    return `${m}:${s < 10 ? '0' : ''}${s}.${tenth}`;
  }
}
