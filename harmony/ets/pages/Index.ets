import { common } from '@kit.AbilityKit';
import { AppGraph } from '../AppGraph';
import { AuthManager } from '../auth/AuthManager';
import { AccountStore } from '../data/AccountStore';
import { Diag } from '../data/Diag';
import { GameMode } from '../model/GameMode';
import { Level } from '../model/Level';
import { Levels } from '../model/Levels';
import { BackgroundMusicManager } from '../service/BackgroundMusicManager';
import { NetworkMonitor } from '../service/NetworkMonitor';
import { SoundEffectManager } from '../service/SoundEffectManager';
import { Colors, PAGE_GRADIENT } from '../theme/Colors';
import { BattleHomeScreen } from '../ui/BattleHomeScreen';
import { GameScreen } from '../ui/GameScreen';
import { HallScreen } from '../ui/HallScreen';
import { HomeScreen } from '../ui/HomeScreen';
import { LeaderboardScreen } from '../ui/LeaderboardScreen';
import { LoginScreen } from '../ui/LoginScreen';
import { NicknameDialog } from '../ui/NicknameDialog';
import { Screen, ScreenKind } from '../ui/Screens';
import { ScreenStack } from '../ui/ScreenStack';
import { SettingsScreen } from '../ui/SettingsScreen';

/**
 * 应用根页面(对应 Android 版 MainActivity.kt 的 XiangsuGameApp)。
 *
 * 结构刻意与安卓端一致:
 *  - 手写页面栈(@State stack),不引入 router/Navigation;
 *  - 启动固定停在登录页:本地有续登会话也不自动进游戏,由用户点「继续」;
 *  - 会话失效(401 由 AuthManager 统一登出)→ 监听 loggedIn 变化,栈重置回登录页;
 *  - 断网 → 顶部红条(NetworkMonitor 驱动,恢复后自动消失)。
 *
 * 进程级初始化:设置/会话/题库缓存/音效/网络监听。
 */
@Entry
@Component
struct Index {
  @State private ready: boolean = false;
  @State private stack: Screen[] = [new Screen(ScreenKind.LOGIN)];
  @State private account: AccountStore | null = null;
  @State private initError: string = '';
  /** 首次登录引导设置昵称(对应安卓 MainActivity 的 pendingNickname)。 */
  @State private pendingNickname: boolean = false;
  /** 断网红条是否已被用户手动关闭(恢复网络后自动复位,与安卓一致)。 */
  @State private offlineDismissed: boolean = false;
  /** 应用上下文(启动 BGM 用,避免每次 getContext)。 */
  private appContext: common.Context | null = null;
  @StorageLink('loggedIn') @Watch('onLoginStateChanged') private loggedIn: boolean = false;
  @StorageProp('offline') @Watch('onOfflineChanged') private offline: boolean = false;

  /** 网络恢复后复位"已关闭"标记:下次再断网会重新提醒(与安卓一致)。 */
  onOfflineChanged(): void {
    if (!this.offline) {
      this.offlineDismissed = false;
    }
  }

  aboutToAppear(): void {
    const context = getContext(this) as common.Context;
    this.appContext = context;
    Diag.init(context);
    Diag.log('Index', `aboutToAppear, diag=${Diag.logPath()}`);
    AppGraph.init(context)
      .then(async () => {
        NetworkMonitor.init();
        await SoundEffectManager.init(context);
        await this.loadAccount();
        this.ready = true;
        // ⚠ 设置(BGM 开关等)是**异步**读出来的:初始化完成前 AppStorage 里还是默认值,
        //   那时启动 BGM 会无视"用户已关闭"—— 所以放到这里,等设置就绪后再启动。
        this.startBgmIfEnabled();
      })
      .catch((e: Object) => {
        const err = e as Error;
        this.initError = err.message;
        this.ready = true; // 初始化失败也放行界面:至少能进设置改服务器地址
      });
  }

  onPageShow(): void {
    // 只在前台时尝试播放;开关是否允许由 startBgmIfEnabled 判断
    this.startBgmIfEnabled();
  }

  onPageHide(): void {
    BackgroundMusicManager.pause();
  }

  /** 启动背景音乐 —— 必须在设置加载完成之后,否则会忽略"已关闭"的设置。 */
  private startBgmIfEnabled(): void {
    const context = this.appContext;
    if (context === null || !this.ready) {
      return; // 设置还没读出来,等 init 完成后由 aboutToAppear 再调
    }
    BackgroundMusicManager.start(context);
  }

  /**
   * 系统返回键 / 返回手势(对应安卓 MainActivity 的 BackHandler)。
   *
   * 返回 true = 我把事件处理掉了(逐层弹栈);false = 交给系统(在首页则退出应用)。
   * 不实现这个的话,鸿蒙端在游戏页/题库页按返回会**直接退出 App**。
   */
  onBackPress(): boolean {
    Diag.log('onBackPress', `kind=${this.current().kind} stack=${this.stack.length}`);
    if (this.pendingNickname) {
      this.pendingNickname = false;
      return true;
    }
    const kind: ScreenKind = this.current().kind;
    if (kind === ScreenKind.LOGIN) {
      return false; // 登录页是栈底:交给系统(退到桌面)
    }
    if (kind === ScreenKind.GAME || kind === ScreenKind.HALL
      || kind === ScreenKind.LEADERBOARD || kind === ScreenKind.BATTLE_HOME
      || kind === ScreenKind.SETTINGS) {
      this.pop();
      return true;
    }
    if (kind === ScreenKind.MAIN) {
      // 首页:回登录页(与安卓"返回即回登录页"的观感一致,避免误退出)
      this.stack = [new Screen(ScreenKind.LOGIN)];
      return true;
    }
    return false;
  }

  /** 会话状态变化:退出/401 登出 → 回登录页并清账号缓存。 */
  onLoginStateChanged(): void {
    if (!this.loggedIn) {
      this.account = null;
      if (this.current().kind !== ScreenKind.LOGIN) {
        this.stack = [new Screen(ScreenKind.LOGIN)];
      }
    }
  }

  /** 账号级 Store(进度/额度 + 每日登录奖励)。 */
  private async loadAccount(): Promise<void> {
    const session = AuthManager.current;
    if (session === null) {
      this.account = null;
      return;
    }
    const store: AccountStore = await AppGraph.account(session.userId);
    await store.grantDailyBonusIfNewDay();
    const validIds: Set<number> = new Set<number>();
    const all: Level[] = Levels.all;
    for (let i = 0; i < all.length; i++) {
      validIds.add(all[i].id);
    }
    await store.pruneCompleted(validIds);
    this.account = store;
  }

  private current(): Screen {
    return this.stack[this.stack.length - 1];
  }

  /** 当前关卡 id(无关卡时 0):给 GameScreen.levelKey 用,避免在 build() 里做 null 收窄。 */
  private currentLevelId(): number {
    const level: Level | null = this.current().level;
    return level === null ? 0 : level.id;
  }

  /**
   * 通关回调入口(内置关也会调用,只有在线关 id < 0 才真的上传)。
   * 与安卓 MainActivity 的 `onSolved = if (online) …` 同口径:
   * 在线单人关上传 source=single 的成绩;游客不上传;失败静默。
   */
  private async submitByLevel(level: Level | null, elapsedSeconds: number): Promise<void> {
    if (level === null || level.id >= 0 || AuthManager.isGuest) {
      return;
    }
    try {
      await AuthManager.api().submitRecord(level.id, elapsedSeconds * 1000, 'single');
    } catch (e) {
      // 静默:离线也能玩,联网后重新通关即可
    }
  }

  private push(screen: Screen): void {
    this.stack = this.stack.concat([screen]);
  }

  private pop(): void {
    if (this.stack.length > 1) {
      this.stack = this.stack.slice(0, this.stack.length - 1);
    }
  }

  /**
   * 下一关:仅内置顺序关有意义(在线关 id 为负)。
   *
   * 用 push 而不是"替换栈顶":新版 GameScreen 会重新走一遍 aboutToAppear,
   * 不会复用上一关的棋盘/计时状态;返回时还能回到刚才那关。
   */
  private gotoNextLevel(): void {
    const current: Screen = this.current();
    Diag.log('gotoNextLevel', `enter stack=${this.stack.length} ` +
      `level=${current.level === null ? 'null' : current.level.id}`);
    // 导航计算交给 ScreenStack(纯逻辑、有单测覆盖),这里只负责改数组
    const next: Screen | null = ScreenStack.nextLevelScreen(current);
    if (next === null) {
      // 没有下一关:回上一页(而不是静默什么都不做)
      Diag.log('gotoNextLevel', 'no next level → pop');
      this.pop();
      return;
    }
    // **必须 push**:栈变长才会让 ArkUI 重建 GameScreen,新关卡才会重新初始化棋盘与计时
    this.push(next);
    Diag.log('gotoNextLevel', `pushed, stack=${this.stack.length} ` +
      `topLevel=${this.currentLevelId()}`);
  }

  build() {
    Stack() {
      if (!this.ready) {
        Column() {
          LoadingProgress().width(48).height(48).color(Colors.Coral)
          Text('正在初始化…').fontSize(13).fontColor(Colors.Cocoa).margin({ top: 12 })
          if (this.initError.length > 0) {
            Text(`初始化异常:${this.initError}`).fontSize(11).fontColor(Colors.Danger)
              .margin({ top: 10 })
          }
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
      } else if (this.current().kind === ScreenKind.LOGIN) {
        LoginScreen({
          onEnter: async () => {
            await this.loadAccount();
            // 首次邮箱登录(服务端 isNew)→ 引导设置昵称(对应安卓 MainActivity.pendingNickname)
            this.pendingNickname = AuthManager.consumeNewUserFlag();
            this.stack = [new Screen(ScreenKind.MAIN)];
          },
        })
      } else if (this.current().kind === ScreenKind.GAME) {
        if (this.current().level !== null) {
          GameScreen({
            level: this.current().level,
            // 换关标识:ArkUI 复用组件实例时靠它触发 GameScreen 重建棋盘
            // (这里再判一次 null 是为了满足 ArkTS 的类型收窄)
            levelKey: this.currentLevelId(),
            account: this.account,
            mode: this.current().mode,
            onBack: () => this.pop(),
            onNextLevel: () => this.gotoNextLevel(),
            // 在线单人关通关 → 上传成绩。守卫放在 submitByLevel 里:
            // ArkTS 不允许在 build() 的 UI 语法中声明局部变量(const/let)。
            onSolved: (_board: number[][], elapsedSeconds: number) => {
              this.submitByLevel(this.current().level, elapsedSeconds);
            },
          })
        }
      } else if (this.current().kind === ScreenKind.MAIN) {
        HomeScreen({
          account: this.account,
          onPlayLevel: (level: Level, mode: GameMode) => {
            this.push(new Screen(ScreenKind.GAME, level, mode));
          },
          onPlayDaily: (level: Level) => {
            this.push(new Screen(ScreenKind.GAME, level, GameMode.FREE));
          },
          onOpenHall: () => this.push(new Screen(ScreenKind.HALL)),
          onOpenLeaderboard: () => this.push(new Screen(ScreenKind.LEADERBOARD)),
          onOpenBattle: () => this.push(new Screen(ScreenKind.BATTLE_HOME)),
          onOpenSettings: () => this.push(new Screen(ScreenKind.SETTINGS)),
          onLogout: async () => {
            await AuthManager.logoutAndRevoke();
            AppGraph.clearAccounts();
            this.account = null;
            this.stack = [new Screen(ScreenKind.LOGIN)];
          },
        })
      } else if (this.current().kind === ScreenKind.HALL) {
        HallScreen({
          onPlay: (level: Level) => this.push(new Screen(ScreenKind.GAME, level, GameMode.FREE)),
          onBack: () => this.pop(),
        })
      } else if (this.current().kind === ScreenKind.LEADERBOARD) {
        LeaderboardScreen({ onBack: () => this.pop() })
      } else if (this.current().kind === ScreenKind.BATTLE_HOME) {
        BattleHomeScreen({
          account: this.account,
          onBack: () => this.pop(),
        })
      } else {
        SettingsScreen({
          onBack: () => this.pop(),
          onLoggedOut: () => {
            AppGraph.clearAccounts();
            this.account = null;
            this.stack = [new Screen(ScreenKind.LOGIN)];
          },
        })
      }

      // 断网提示条(NetworkMonitor 驱动):可点击关闭;恢复网络后自动复位,下次断网再提醒
      if (this.offline && this.ready && !this.offlineDismissed) {
        Text('⚠ 网络已断开,在线功能暂不可用(内置关可离线玩)· 点我关闭')
          .fontSize(11)
          .fontColor(Color.White)
          .backgroundColor(Colors.Danger)
          .width('100%')
          .textAlign(TextAlign.Center)
          .padding({ top: 4, bottom: 4 })
          .onClick(() => {
            this.offlineDismissed = true;
          })
      }

      // 首次登录引导设置昵称(与安卓端同一流程:登录页 → 首页之间弹一次)
      if (this.pendingNickname) {
        NicknameDialog({
          current: AuthManager.nickname(),
          title: '设置昵称',
          hint: '排行榜、对局名次等展示用昵称,提交后即时生效(也可以稍后在设置页改)',
          onDismiss: () => {
            this.pendingNickname = false;
          },
          onDone: () => {
            this.pendingNickname = false;
          },
        })
      }
    }
    .width('100%')
    .height('100%')
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }
}
