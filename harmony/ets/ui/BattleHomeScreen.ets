import { promptAction } from '@kit.ArkUI';
import { AuthManager } from '../auth/AuthManager';
import { BattlePhase, BattleRole, BattleSession } from '../battle/BattleSession';
import { NetworkBattleTransport } from '../battle/NetworkBattleTransport';
import { AccountStore } from '../data/AccountStore';
import { Level, difficultyLabel } from '../model/Level';
import { Levels } from '../model/Levels';
import { Colors, PAGE_GRADIENT, PRIMARY_GRADIENT } from '../theme/Colors';
import { GameScreen } from './GameScreen';
import { ScreenTopBar, SegmentedControl } from './Widgets';

enum BattleView {
  MENU = 0,
  SETUP = 1,
  ROOM = 2,
}

/** 房间内一名玩家的展示信息(由 BattleSession 快照抄来)。 */
class PlayerRow {
  userId: number = 0;
  nickname: string = '';
  filled: number = 0;
  finishedRank: number = 0;
  connected: boolean = true;

  constructor(userId: number, nickname: string, filled: number, finishedRank: number,
    connected: boolean) {
    this.userId = userId;
    this.nickname = nickname;
    this.filled = filled;
    this.finishedRank = finishedRank;
    this.connected = connected;
  }
}

/** 结算行:名次 / 昵称 / 用时文本 / 是否本人。 */
class ResultRow {
  rank: number = 0;
  nickname: string = '';
  timeText: string = '';
  mine: boolean = false;

  constructor(rank: number, nickname: string, timeText: string, mine: boolean) {
    this.rank = rank;
    this.nickname = nickname;
    this.timeText = timeText;
    this.mine = mine;
  }
}

/**
 * 对战大厅(对应 Android 版 ui/BattleHomeScreen.kt)。
 *
 * 鸿蒙版**只保留网络对战**(蓝牙 RFCOMM 属平台能力差异,计划里已明确不做):
 * 建房/加房 → 房主选题 → 发车 → 实时进度 → 服务器判定名次 → 结算可重赛。
 * 协议与服务端零改动,因此可以和安卓端同房对战。
 *
 * 这一版按安卓端补齐了:对手进度条、服务器提示弹窗、断线重连、发车校验、
 * 结算奖牌与"返回房间等待下一局"、玩家卡片(房主/已就位/掉线)。
 */
@Component
export struct BattleHomeScreen {
  account: AccountStore | null = null;
  onBack: () => void = () => {
  };

  @State private view: number = BattleView.MENU as number;
  @State private codeInput: string = '';
  @State private busy: boolean = false;
  @State private showPick: boolean = false;
  @State private setupMode: number = 0; // 0 = 创建房间,1 = 加入房间
  /** 游客提示:游客没有令牌,建房/加房必然 401。 */
  @State private guestNotice: boolean = false;

  // 会话快照(由 session.onChange 抄进来渲染)
  @State private phase: number = BattlePhase.LOBBY as number;
  @State private roomCode: string = '';
  @State private rows: PlayerRow[] = [];
  @State private progressRows: PlayerRow[] = [];
  @State private totalFilled: number = 0;
  @State private puzzleName: string = '';
  @State private notice: string = '';
  @State private fatal: string = '';
  @State private results: ResultRow[] = [];
  @State private myRank: number = 0;
  @State private finishedBanner: string = '';
  @State private settleDismissed: boolean = false;
  @State private amHost: boolean = false;

  private session: BattleSession | null = null;
  private raceLevel: Level | null = null;

  aboutToDisappear(): void {
    this.teardown();
  }

  private teardown(): void {
    const s: BattleSession | null = this.session;
    if (s !== null) {
      s.close();
    }
    this.session = null;
    this.raceLevel = null;
    this.rows = [];
    this.progressRows = [];
    this.results = [];
    this.notice = '';
    this.fatal = '';
    this.settleDismissed = false;
    this.view = BattleView.MENU as number;
    this.phase = BattlePhase.LOBBY as number;
  }

  /** 建房:POST /api/rooms 拿到 4 位房号,再连 WebSocket。 */
  private async createRoom(): Promise<void> {
    if (this.busy) {
      return;
    }
    this.busy = true;
    try {
      const resp = await AuthManager.api().createRoom();
      this.openSession(resp.roomId, BattleRole.HOST);
    } catch (e) {
      const err = e as Error;
      promptAction.showToast({ message: `建房失败:${err.message}` });
    } finally {
      this.busy = false;
    }
  }

  private openSession(code: string, role: BattleRole): void {
    const myId: number = AuthManager.current === null ? -1 : AuthManager.current.userId;
    const session: BattleSession = new BattleSession(
      new NetworkBattleTransport(code), role, myId, code, true);
    session.onChange = () => this.syncFromSession();
    this.session = session;
    this.roomCode = code.toUpperCase();
    this.amHost = role === BattleRole.HOST;
    this.view = BattleView.ROOM as number;
    session.open();
  }

  /** 断线重连:用原房号重开一条连接(与安卓 reconnect() 同口径)。 */
  private reconnect(): void {
    if (this.busy) {
      return;
    }
    this.busy = true;
    this.fatal = '';
    this.notice = '';
    const code: string = this.roomCode;
    const role: BattleRole = this.amHost ? BattleRole.HOST : BattleRole.GUEST;
    const old: BattleSession | null = this.session;
    if (old !== null) {
      old.close();
    }
    this.session = null;
    this.openSession(code, role);
    promptAction.showToast({ message: `正在重新连接房间 ${code}…` });
    this.busy = false;
  }

  /** 把会话状态抄进 @State 供渲染(ArkUI 没有 Compose 的自动可观察对象)。 */
  private syncFromSession(): void {
    const s: BattleSession | null = this.session;
    if (s === null) {
      return;
    }
    this.phase = s.phase as number;
    this.puzzleName = s.puzzleName;
    this.fatal = s.fatal;
    this.myRank = s.myFinalRank;
    this.amHost = s.isHost;
    // 服务器提示:只在内容变化时才弹一次(否则每次同步都弹)
    if (s.notice.length > 0 && s.notice !== this.notice) {
      this.notice = s.notice;
      promptAction.showToast({ message: s.notice });
      s.consumeNotice();
    }

    const mine: PlayerRow[] = [];
    const others: PlayerRow[] = [];
    for (let i = 0; i < s.players.length; i++) {
      const p = s.players[i];
      const row = new PlayerRow(p.userId, p.nickname, p.filled, p.finishedRank, p.connected);
      mine.push(row);
      if (p.userId !== s.myUserId) {
        others.push(row);
      }
    }
    this.rows = mine;
    this.progressRows = others;
    this.totalFilled = this.totalAnswerFilled(s.raceLevel);

    const finished = s.finishedPlayers();
    if (s.someoneFinished() && !s.mySubmitted) {
      const first = finished[0];
      this.finishedBanner = `🏁 ${first.nickname} 第${first.finishedRank}名完成 · 你继续加油!` +
        `(还有 ${s.racingCount()} 人在做)`;
    } else if (s.awaitingMyResult()) {
      this.finishedBanner = `✅ 你已完成,等其他人做完(${s.racingCount()} 人还在做)…`;
    } else {
      this.finishedBanner = '';
    }

    const list: ResultRow[] = [];
    for (let i = 0; i < s.resultEntries.length; i++) {
      const e = s.resultEntries[i];
      const totalSec: number = Math.floor(e.durationMs / 1000);
      const text: string = `${Math.floor(totalSec / 60)}:${this.pad2(totalSec % 60)}.` +
        `${Math.floor((e.durationMs % 1000) / 100)}`;
      list.push(new ResultRow(e.rank, e.nickname, text, e.userId === s.myUserId));
    }
    this.results = list;

    const level: Level | null = s.raceLevel;
    if (level !== null && (this.raceLevel === null || this.raceLevel.id !== level.id)) {
      this.raceLevel = level;
      this.totalFilled = this.totalAnswerFilled(level);
      this.settleDismissed = false;
    }
  }

  private pad2(v: number): string {
    return v < 10 ? `0${v}` : `${v}`;
  }

  private totalAnswerFilled(level: Level | null): number {
    if (level === null) {
      return 0;
    }
    let n: number = 0;
    for (let r = 0; r < level.rows; r++) {
      for (let c = 0; c < level.cols; c++) {
        if (level.answerGrid[r][c] === 1) {
          n++;
        }
      }
    }
    return n;
  }

  private progressPercentOf(row: PlayerRow): number {
    if (this.totalFilled <= 0) {
      return 0;
    }
    let pct: number = Math.round(row.filled * 100 / this.totalFilled);
    if (pct < 0) {
      pct = 0;
    }
    if (pct > 100) {
      pct = 100;
    }
    return pct;
  }

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
    return `#${rank}`;
  }

  /** 发车条件:已选题 + 在线人数 ≥ 2(与安卓 enabled 同口径)。 */
  private canStart(): boolean {
    const s: BattleSession | null = this.session;
    if (s === null) {
      return false;
    }
    const online: number = s.players.filter((p) => p.connected).length;
    return this.puzzleName.length > 0 && online >= 2;
  }

  private startHintText(): string {
    const s: BattleSession | null = this.session;
    if (s === null) {
      return '';
    }
    const online: number = s.players.filter((p) => p.connected).length;
    if (this.puzzleName.length === 0) {
      return '先在下面选一道题';
    }
    if (online < 2) {
      return '等待第二位玩家加入…';
    }
    return '人数就绪,可以发车了';
  }

  build() {
    Column() {
      if (this.view === (BattleView.MENU as number)) {
        ScreenTopBar({ title: '对战大厅', subtitle: '同题竞速 · 先完成全对者胜', onBack: this.onBack })
        Column() {
          Text('同题竞速:所有人拿到同一道题,实时可见彼此进度;\n' +
            '第一个完整还原图案的人获胜 —— 名次与用时由服务器权威判定。')
            .fontSize(12).fontColor(Colors.Cocoa).lineHeight(18)

          Button('🌐 网络对战')
            .width('100%').height(56).fontSize(16).margin({ top: 16 })
            .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
            .onClick(() => {
              // 游客拦截:建房/加房都要服务端鉴权,游客点了必然 401
              if (AuthManager.isGuest) {
                this.guestNotice = true;
                return;
              }
              this.view = BattleView.SETUP as number;
            })

          if (this.guestNotice) {
            Text('⚠ 对战需要登录账号(当前是游客身份)。\n请回首页点「邮箱登录」,或在设置页「切换账号」。')
              .fontSize(12).fontColor(Colors.Danger)
              .backgroundColor(Colors.SurfaceWhite)
              .borderRadius(12)
              .padding(12)
              .margin({ top: 12 })
              .textAlign(TextAlign.Center)
          }

          Text('ℹ️ 鸿蒙版暂不支持蓝牙对战(平台能力差异);\n' +
            '网络对战与安卓端同一套协议,可以与安卓手机同房开局。')
            .fontSize(11).fontColor(Colors.Cocoa).lineHeight(17)
            .margin({ top: 18 })
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 20, right: 20 })
        .justifyContent(FlexAlign.Center)
      } else if (this.view === (BattleView.SETUP as number)) {
        ScreenTopBar({ title: '网络对战', subtitle: '建房或加入', onBack: () => {
          this.view = BattleView.MENU as number;
        } })

        // 分段切换:创建 / 加入(对应安卓 SegmentedControl)
        SegmentedControl({
          options: ['🏠 创建房间', '🚪 加入房间'],
          selected: this.setupMode,
          onSelect: (index: number) => {
            this.setupMode = index;
          },
        })
        .width('100%')
        .padding({ left: 20, right: 20, top: 10 })

        Column() {
          if (this.setupMode === 0) {
            Text('建房后你会成为房主,负责选一道题并发车。\n把 4 位房号告诉朋友即可加入。')
              .fontSize(12).fontColor(Colors.Cocoa).lineHeight(18).textAlign(TextAlign.Center)
            Button(this.busy ? '建房中…' : '创建房间(我当房主)')
              .width('100%').height(52).fontSize(15).margin({ top: 18 })
              .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
              .onClick(() => {
                if (this.busy) {
                  promptAction.showToast({ message: '正在建房,请稍候…' });
                  return;
                }
                this.createRoom();
              })
          } else {
            Text('输入房主的 4 位房号加入:').fontSize(12).fontColor(Colors.Cocoa)
              .margin({ bottom: 8 })
            TextInput({ text: this.codeInput, placeholder: '例如 A3KP' })
              .maxLength(4)
              .height(48).fontSize(18).textAlign(TextAlign.Center)
              .backgroundColor(Colors.SurfaceWhite).fontColor(Colors.Ink)
              .placeholderColor(Colors.Cocoa)
              .borderRadius(12)
              .onChange((value: string) => {
                this.codeInput = value.toUpperCase();
              })
            if (this.codeInput.length > 0 && this.codeInput.length < 4) {
              Text('房间号是 4 位,例如 A3KP').fontSize(11).fontColor(Colors.Amber)
                .margin({ top: 6 })
            }
            Button('加入房间')
              .width('100%').height(48).fontSize(15).margin({ top: 12 })
              .backgroundColor(Colors.Cyan)
              .fontColor('#08221F')
              .onClick(() => {
                // 不用 enabled 关掉:ArkUI 的 enabled(false) 完全无反馈,用户会以为按钮坏了
                if (this.codeInput.length !== 4) {
                  promptAction.showToast({ message: '请输入 4 位房号,例如 A3KP' });
                  return;
                }
                this.openSession(this.codeInput, BattleRole.GUEST);
              })
          }

          Text(`服务器:${AuthManager.serverHost()}(连不上请到「设置」改地址)`)
            .fontSize(10).fontColor(Colors.Cocoa).margin({ top: 22 })
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 20, right: 20, top: 16 })
      } else {
        this.roomView()
      }
    }
    .width('100%')
    .height('100%')
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }

  @Builder
  roomView() {
    Column() {
      ScreenTopBar({
        title: `房间 ${this.roomCode}`,
        subtitle: this.phase === (BattlePhase.LOBBY as number)
          ? (this.amHost ? '你是房主 · 选题后发车' : '等待房主选题发车…')
          : (this.phase === (BattlePhase.RACING as number) ? '竞速中' : '本局结束'),
        onBack: () => this.teardown(),
      })

      if (this.fatal.length > 0) {
        // ———— 连接中断:保留房号,可重连 ————
        Column() {
          Text('📡 连接已断开').fontSize(17).fontWeight(FontWeight.Bold).fontColor(Colors.Danger)
          Text(`房间 ${this.roomCode} 的席位会为你保留一段时间`)
            .fontSize(12).fontColor(Colors.Cocoa).margin({ top: 6 })
          Text(this.fatal).fontSize(11).fontColor(Colors.Cocoa).margin({ top: 6 })
            .textAlign(TextAlign.Center)
          Button('🔄 重新连接')
            .width('100%').height(46).fontSize(15).margin({ top: 16 })
            .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
            .onClick(() => this.reconnect())
          Button('返回大厅')
            .width('100%').height(42).fontSize(13).margin({ top: 10 })
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => this.teardown())
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center).padding(24)
      } else if (this.phase === (BattlePhase.RACING as number) && this.raceLevel !== null
        && !this.settleDismissed) {
        // ———— 竞速:复用游戏页;通关遮罩交给本页结算卡(showOverlays=false) ————
        Column() {
          if (this.finishedBanner.length > 0) {
            Text(this.finishedBanner)
              .fontSize(12).fontColor(Colors.Amber)
              .width('100%').padding(8)
              .backgroundColor(Colors.SurfaceWhite)
          }
          // 对手进度:每人一行姓名 + 进度条 + 已涂格数 / 完成名次
          if (this.progressRows.length > 0) {
            Column() {
              ForEach(this.progressRows, (row: PlayerRow) => {
                Row() {
                  Text(row.connected ? row.nickname : `${row.nickname}(掉线)`)
                    .fontSize(11).fontColor(Colors.Cocoa).width(72)
                  Stack({ alignContent: Alignment.Start }) {
                    Row().width('100%').height(8)
                      .backgroundColor(Colors.Sand).borderRadius(4)
                    Row().width(`${this.progressPercentOf(row)}%`).height(8)
                      .backgroundColor(row.finishedRank > 0 ? Colors.Amber : Colors.Cyan)
                      .borderRadius(4)
                  }
                  .layoutWeight(1).height(8).margin({ left: 6, right: 6 })
                  Text(row.finishedRank > 0 ? `已完成 第${row.finishedRank}名` : `${row.filled} 格`)
                    .fontSize(10)
                    .fontColor(row.finishedRank > 0 ? Colors.Amber : Colors.Cocoa)
                }
                .width('100%').margin({ top: 4 })
              }, (row: PlayerRow) => row.userId.toString())
            }
            .width('100%').padding({ left: 10, right: 10, top: 4, bottom: 4 })
            .backgroundColor(Colors.SurfaceWhite)
          }
          Column() {
            GameScreen({
              level: this.raceLevel,
              account: this.account,
              mode: 0,
              onBack: () => this.teardown(),
              // 对局中不弹游戏页自己的通关/超时遮罩,也不用它的「下一关/再来一局」
              showOverlays: false,
              paused: false,
              onProgress: (filled: number, elapsed: number) => {
                const s: BattleSession | null = this.session;
                if (s !== null) {
                  s.reportProgress(filled, elapsed);
                }
              },
              onSolved: (board: number[][], elapsed: number) => {
                const s: BattleSession | null = this.session;
                if (s !== null) {
                  s.localFinish(board, elapsed);
                }
              },
            })
          }
          .layoutWeight(1).width('100%')
        }
        .layoutWeight(1).width('100%')
      } else if (this.phase === (BattlePhase.SETTLED as number)) {
        // ———— 结算:奖牌 + 用时 + 返回房间等待下一局 ————
        Column() {
          Text(this.myRank === 1 ? '🏆 恭喜夺冠!' : (this.myRank > 0 ? `🏁 你第 ${this.myRank} 名` : '🏁 本局结束'))
            .fontSize(22).fontWeight(FontWeight.Bolder).fontColor(Colors.Ink)
          if (this.puzzleName.length > 0) {
            Text(`题目:${this.puzzleName}`).fontSize(12).fontColor(Colors.Cocoa).margin({ top: 4 })
          }

          Column() {
            ForEach(this.results, (row: ResultRow) => {
              Row() {
                Text(this.medal(row.rank)).fontSize(15).width(34)
                Text(row.nickname + (row.mine ? '(你)' : ''))
                  .fontSize(13).layoutWeight(1)
                  .fontColor(row.mine ? Colors.Coral : Colors.Ink)
                Text(row.timeText).fontSize(13).fontColor(Colors.Cyan)
              }
              .width('100%').padding({ top: 6, bottom: 6 })
            }, (row: ResultRow) => `${row.rank}-${row.nickname}`)
          }
          .width('100%').margin({ top: 14 })
          .padding({ left: 12, right: 12 })
          .backgroundColor(Colors.SurfaceWhite)
          .borderRadius(12)

          if (this.puzzleName.length > 0) {
            Text(`本局成绩已入榜 · 到排行榜「对战榜」把题目切到「${this.puzzleName}」即可查看`)
              .fontSize(10).fontColor(Colors.Cocoa).margin({ top: 10 })
              .textAlign(TextAlign.Center)
          }

          Row() {
            if (this.amHost) {
              Button('🔄 再来一局')
                .fontSize(14).height(44).layoutWeight(1)
                .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
                .onClick(() => {
                  const s: BattleSession | null = this.session;
                  if (s !== null) {
                    s.requestRematch();
                  }
                })
            } else {
              Button('🛋️ 返回房间')
                .fontSize(14).height(44).layoutWeight(1)
                .backgroundColor(Colors.CoralContainer).fontColor(Colors.Ink)
                .onClick(() => {
                  this.settleDismissed = true;
                })
            }
            Button('返回大厅')
              .fontSize(14).height(44).layoutWeight(1)
              .margin({ left: 10 })
              .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
              .onClick(() => this.teardown())
          }
          .width('100%').margin({ top: 20 })
        }
        .layoutWeight(1).width('100%').justifyContent(FlexAlign.Center).padding(24)
      } else {
        // ———— 房间(大厅阶段)————
        Column() {
          Text(`房号 ${this.roomCode}(告诉朋友这个号)`)
            .fontSize(15).fontWeight(FontWeight.Bold).fontColor(Colors.Cyan)

          if (this.rows.length === 0) {
            Column() {
              LoadingProgress().width(24).height(24).color(Colors.Coral)
              Text('等待房间状态同步…').fontSize(11).fontColor(Colors.Cocoa).margin({ top: 6 })
            }
            .width('100%').padding({ top: 16, bottom: 16 })
          } else {
            Column() {
              ForEach(this.rows, (row: PlayerRow) => {
                Row() {
                  Text(row.userId === this.myUserIdValue() ? '👤' : '🧑')
                    .fontSize(14).width(24)
                  Column() {
                    Text(`${row.nickname}${row.userId === this.myUserIdValue() ? '(我)' : ''}`)
                      .fontSize(13).fontColor(Colors.Ink)
                    Text(this.playerSubtitle(row))
                      .fontSize(10).fontColor(Colors.Cocoa).margin({ top: 2 })
                  }
                  .alignItems(HorizontalAlign.Start)
                  .layoutWeight(1)
                  if (!row.connected) {
                    Text('掉线').fontSize(10).fontColor(Colors.Danger)
                  }
                }
                .width('100%').padding(10).margin({ top: 6 })
                .backgroundColor(Colors.SurfaceWhite).borderRadius(10)
              }, (row: PlayerRow) => row.userId.toString())
            }
            .width('100%').padding({ top: 10, bottom: 6 })
          }

          if (this.puzzleName.length > 0) {
            Text(`已选题目:${this.puzzleName}`).fontSize(12).fontColor(Colors.Amber)
          }

          if (this.amHost) {
            if (this.showPick) {
              List() {
                ForEach(Levels.all, (level: Level) => {
                  ListItem() {
                    Row() {
                      Column() {
                        Text(level.name).fontSize(14).fontColor(Colors.Ink)
                        Text(`${difficultyLabel(level.difficulty)} · ${level.rows}×${level.cols}`)
                          .fontSize(10).fontColor(Colors.Cocoa)
                      }
                      .alignItems(HorizontalAlign.Start)
                      .layoutWeight(1)
                      Text(this.puzzleName === level.name ? '✓ 已选' : '选它')
                        .fontSize(12)
                        .fontColor(this.puzzleName === level.name ? Colors.Cyan : Colors.Coral)
                    }
                    .width('100%').padding(10)
                    .backgroundColor(Colors.SurfaceWhite).borderRadius(10)
                    .onClick(() => {
                      const s: BattleSession | null = this.session;
                      if (s !== null) {
                        s.choosePuzzle(level);
                        this.showPick = false;
                      }
                    })
                  }
                }, (level: Level) => level.id.toString())
              }
              .height(200).width('100%').margin({ top: 8 })
            }

            Button(this.showPick ? '收起题目列表' : (this.puzzleName.length > 0 ? '③ 换一道题' : '③ 选一道题'))
              .width('100%').height(44).fontSize(14).margin({ top: 10 })
              .backgroundColor(Colors.CoralContainer).fontColor(Colors.Ink)
              .onClick(() => {
                this.showPick = !this.showPick;
              })

            Text(this.startHintText()).fontSize(11).fontColor(Colors.Cocoa).margin({ top: 8 })

            Button(this.canStart() ? '④ 发车' : '④ 发车(条件未满足)')
              .width('100%').height(48).fontSize(15).margin({ top: 6 })
              .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
              .onClick(() => {
                const s: BattleSession | null = this.session;
                if (s === null) {
                  return;
                }
                // 不满足条件时给出**具体原因**,而不是静默无反应
                if (this.puzzleName.length === 0) {
                  promptAction.showToast({ message: '先选一道题再发车' });
                  return;
                }
                const online: number = s.players.filter((p) => p.connected).length;
                if (online < 2) {
                  promptAction.showToast({ message: '至少要有 2 人在线才能发车' });
                  return;
                }
                s.requestStart();
              })
          } else {
            Text('等待房主选题发车…').fontSize(12).fontColor(Colors.Cocoa).margin({ top: 14 })
            LoadingProgress().width(28).height(28).color(Colors.Coral).margin({ top: 8 })
          }

          Button('退出房间')
            .fontSize(12).height(34).margin({ top: 16 })
            .backgroundColor(Color.Transparent).fontColor(Colors.Cocoa)
            .onClick(() => this.teardown())
        }
        .layoutWeight(1).width('100%').padding({ left: 20, right: 20 }).alignItems(HorizontalAlign.Center)
      }
    }
    .width('100%')
    .height('100%')
  }

  private myUserIdValue(): number {
    const s: BattleSession | null = this.session;
    return s === null ? -1 : s.myUserId;
  }

  /** 玩家卡片副标题:房主/房客 + 就位状态。 */
  private playerSubtitle(row: PlayerRow): string {
    const role: string = row.userId === this.hostIdValue() ? '房主 · 负责选题发车' : '房客';
    if (row.finishedRank > 0) {
      return `${role} · 已完成 第${row.finishedRank}名`;
    }
    return `${role}${row.connected ? ' · ✓ 已就位' : ' · 连接中断(席位保留)'}`;
  }

  private hostIdValue(): number {
    const s: BattleSession | null = this.session;
    if (s === null) {
      return -1;
    }
    return s.hostId;
  }
}
