import { promptAction } from '@kit.ArkUI';
import { AccountStore } from '../data/AccountStore';
import { Diag } from '../data/Diag';
import { LocalSettings } from '../data/LocalSettings';
import { CellState, nextCellState } from '../model/CellState';
import { GameBoard } from '../model/GameBoard';
import { GameMode } from '../model/GameMode';
import { Level, difficultyLabel, difficultyStars } from '../model/Level';
import { HintStep, nextHint } from '../model/PuzzleSolver';
import { Levels } from '../model/Levels';
import { Validator } from '../model/Validator';
import { ShakeDetector } from '../sensor/ShakeDetector';
import { SoundEffectManager } from '../service/SoundEffectManager';
import { Colors, PAGE_GRADIENT, PRIMARY_GRADIENT } from '../theme/Colors';
import { BoardView } from './BoardView';
import { PicturePreview } from './Widgets';

/**
 * 游戏页(对应 Android 版 ui/GameScreen.kt)。
 *
 * 功能与安卓端保持一致:
 *   双笔制(涂黑笔 / ✕ 留白笔)+ 拖动连涂(整笔 = 一步撤销)、撤销/重做/重置、
 *   显式检查错误、💡 提示下一步(求解器推导)、一键补齐(额度制)、参考答案、
 *   自由/限时两种模式、通关揭晓与下一关。
 *
 * 计时用 Date.now() 差值累加(不是"每秒 +1"):后台/休眠导致漏拍时自动补上,计时不偏慢。
 */
@Component
export struct GameScreen {
  level: Level | null = null;
  /**
   * 换关标识(根页面传关卡 id)。
   *
   * 为什么需要:ArkUI 在"同一个分支里换了数据"时会**复用组件实例**,不重跑 aboutToAppear。
   * 之前根页面用"替换栈顶"切关,栈长度不变 → GameScreen 被复用、`this.board` 还指着上一关,
   * 表现就是"点了下一关没反应"。现在根页面改成 push(强制重建),这里再用 @Watch 兜一层。
   */
  @Prop @Watch('onLevelChanged') levelKey: number = 0;
  account: AccountStore | null = null;
  mode: GameMode = GameMode.FREE;
  onBack: () => void = () => {
  };
  onNextLevel: (() => void) | null = null;
  /** 重玩本关(对应安卓 WinOverlay 的「再来一局」)。 */
  onReplay: (() => void) | null = null;
  /** 通关回调:上传成绩 / 对局 finish(由调用方处理)。 */
  onSolved: ((board: number[][], elapsedSeconds: number) => void) | null = null;
  /** 竞速进度上报(网络/蓝牙对战用)。 */
  onProgress: ((filled: number, elapsedSeconds: number) => void) | null = null;
  /**
   * 外部冻结(对战时用):暂停计时与交互,且**不显示通关/超时遮罩**。
   * 对应安卓 GameScreen 的 `externalPaused` + `showOverlays = false` ——
   * 对局结算由对战页自己的卡片负责,游戏页不该插一脚。
   */
  paused: boolean = false;
  /** 是否显示通关/超时遮罩(对战时传 false,交给对战页结算)。 */
  showOverlays: boolean = true;

  @State private version: number = 0;
  @State private tool: number = CellState.FILLED as number;
  @State private checkedWrong: string[] = [];
  @State private hasChecked: boolean = false;
  @State private hintCell: string = '';
  @State private hintMessage: string = '';
  @State private isSolved: boolean = false;
  @State private elapsedSeconds: number = 0;
  @State private timedOut: boolean = false;
  @State private showAnswer: boolean = false;
  @State private solvedReported: boolean = false;
  /** 本关是否查看过参考答案(结算时仅提示,不惩罚)。 */
  @State private peekedAnswer: boolean = false;
  /** 揭晓用的全屏放大查看。 */
  @State private showZoomed: boolean = false;
  /** 下一关是否可用(根页面没接回调、或已是最后一关时为 false)。 */
  @State private nextLevelAvailable: boolean = true;
  /** "只差几格"的提示(涂黑笔擦除语义容易让玩家以为已经全对)。 */
  @State private almostMessage: string = '';
  /** 棋盘区实测尺寸(vp):用来在顶栏显示"当前一格多大",方便按屏幕调字号。 */
  @State private boardAreaW: number = 0;
  @State private boardAreaH: number = 0;

  private board: GameBoard | null = null;
  private validator: Validator | null = null;
  /** 上一次初始化的关卡 id(-1 = 还没初始化),用于判断是否真的换关。 */
  private lastLevelId: number = -1;
  private timerId: number = -1;
  /** 通关看门狗定时器(兜底判定,见 startWinWatchdog)。 */
  private watchdogId: number = -1;
  private lastTickAt: number = 0;
  /** 本笔方向(-1 = 未定):涂黑笔落在黑格上 → 整笔为"擦除",与安卓端一致。 */
  private strokeTarget: number = -1;
  /** 摇一摇重置(设置页开关控制);null = 未启用/设备无加速度计。 */
  private shake: ShakeDetector | null = null;
  /** 摇一摇后的提示条(3 秒后自动消失,与安卓 shakeHint 同款)。 */
  @State private shakeHint: boolean = false;

  aboutToAppear(): void {
    const level: Level | null = this.level;
    if (level === null) {
      return;
    }
    Diag.log('GameScreen', `aboutToAppear levelId=${level.id} ${level.rows}x${level.cols}`);
    this.lastLevelId = level.id;
    this.board = new GameBoard(level.rows, level.cols);
    this.validator = new Validator(level);
    this.startTimer();
    this.startWinWatchdog();
    this.startShake();
  }

  /**
   * 关卡变化 → 重建棋盘、校验器与所有局面状态。
   *
   * 这是"点下一关没反应"的正解:ArkUI 复用组件实例时不重跑 aboutToAppear,
   * 只有 @Watch 会触发 —— 靠它把 GameScreen 重置成新关卡的状态。
   */
  onLevelChanged(): void {
    const level: Level | null = this.level;
    Diag.log('GameScreen', `onLevelChanged levelKey=${this.levelKey} ` +
      `level=${level === null ? 'null' : level.id} last=${this.lastLevelId}`);
    if (level === null || level.id === this.lastLevelId) {
      return;
    }
    this.lastLevelId = level.id;
    this.board = new GameBoard(level.rows, level.cols);
    this.validator = new Validator(level);
    this.version++;
    this.isSolved = false;
    this.timedOut = false;
    this.solvedReported = false;
    this.checkedWrong = [];
    this.hasChecked = false;
    this.hintCell = '';
    this.hintMessage = '';
    this.almostMessage = '';
    this.showAnswer = false;
    this.showZoomed = false;
    this.peekedAnswer = false;
    this.elapsedSeconds = 0;
    this.lastTickAt = Date.now();
    this.strokeTarget = -1;
    this.startTimer();
  }

  /** 摇一摇重置:设置页开关打开且设备有加速度计时启用(对应安卓 GameScreen 的 ShakeDetector)。 */
  private startShake(): void {
    if (!LocalSettings.shakeToResetEnabled()) {
      return;
    }
    if (!ShakeDetector.available()) {
      return;
    }
    const detector = new ShakeDetector(() => {
      if (this.lockedOut() || !this.interactiveNow()) {
        return;
      }
      this.resetBoard();
      this.shakeHint = true;
      setTimeout(() => {
        this.shakeHint = false;
      }, 3000);
    });
    detector.start();
    this.shake = detector;
  }

  /** 当前是否允许操作(通关/超时/被对战冻结时不允许)。 */
  private interactiveNow(): boolean {
    return !this.lockedOut();
  }

  aboutToDisappear(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
      this.timerId = -1;
    }
    if (this.watchdogId >= 0) {
      clearInterval(this.watchdogId);
      this.watchdogId = -1;
    }
    const shake = this.shake;
    if (shake !== null) {
      shake.stop();
      this.shake = null;
    }
  }

  /**
   * 限时秒数:关卡自带;没有则按难度给默认值 —— 与安卓端一致
   * (简单 180 / 中等 360 / 困难 600)。
   */
  private limitSeconds(): number {
    const level: Level | null = this.level;
    if (level === null) {
      return 300;
    }
    if (level.timedLimitSeconds > 0) {
      return level.timedLimitSeconds;
    }
    if (level.difficulty === 0) {
      return 180; // EASY
    }
    if (level.difficulty === 2) {
      return 600; // HARD
    }
    return 360; // MEDIUM(以及未知难度)
  }

  private startTimer(): void {
    if (this.timerId >= 0) {
      clearInterval(this.timerId);
    }
    this.lastTickAt = Date.now();
    this.timerId = setInterval(() => {
      if (this.isSolved || this.timedOut || this.paused) {
        this.lastTickAt = Date.now(); // 冻结期间不累计
        return;
      }
      const now: number = Date.now();
      let delta: number = Math.floor((now - this.lastTickAt) / 1000);
      if (delta < 1) {
        return;
      }
      this.lastTickAt = now;
      if (delta > 30) {
        delta = 30; // 长时间后台:最多补 30 秒,避免"切出去一会儿回来就超时"
      }
      this.elapsedSeconds += delta;
      if (this.mode === GameMode.TIMED && this.elapsedSeconds >= this.limitSeconds()
        && !this.isSolved) {
        this.timedOut = true;
      }
      this.reportProgress();
    }, 1000);
  }

  /** 限时模式剩余秒数(与安卓 remainingSeconds 同口径)。 */
  private remainingSeconds(): number {
    const left: number = this.limitSeconds() - this.elapsedSeconds;
    return left > 0 ? left : 0;
  }

  /** 已涂黑格数 / 答案需要的总格数(进度条用)。 */
  private filledCount(): number {
    const board: GameBoard | null = this.board;
    const level: Level | null = this.level;
    if (board === null || level === null) {
      return 0;
    }
    let n: number = 0;
    for (let r = 0; r < level.rows; r++) {
      for (let c = 0; c < level.cols; c++) {
        if (board.board[r][c] === (CellState.FILLED as number)) {
          n++;
        }
      }
    }
    return n;
  }

  private totalFilled(): number {
    const level: Level | null = this.level;
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

  // ---------------- 棋盘操作 ----------------

  private recompute(): void {
    const validator: Validator | null = this.validator;
    const board: GameBoard | null = this.board;
    if (validator === null || board === null) {
      return;
    }
    // 已满足提示与错误标红都随盘面刷新;通关判定用纯函数实时算
    const wrongs: Set<string> = validator.wrongCells(board.board);
    if (this.hasChecked) {
      const list: string[] = [];
      wrongs.forEach((k: string) => list.push(k));
      this.checkedWrong = list;
    }
    if (!this.isSolved && validator.isSolved(board.board)) {
      this.almostMessage = '';
      this.isSolved = true;
      Diag.log('win', `solved levelId=${this.level === null ? -1 : this.level.id} ` +
        `elapsed=${this.elapsedSeconds}`);
      this.onWin();
    }
  }

  /**
   * 通关看门狗:每秒兜底判一次。
   *
   * 正常路径是"每涂一格 → recompute() → 判定通关";但交互事件(触摸/鼠标)在
   * 预览器与真机上偶有丢失,会出现"看着全对了但没弹通关"。这里独立兜底,
   * 判定完全依赖棋盘快照这个唯一事实来源,不依赖任何事件是否送达。
   */
  private startWinWatchdog(): void {
    if (this.watchdogId >= 0) {
      clearInterval(this.watchdogId);
    }
    this.watchdogId = setInterval(() => {
      if (this.isSolved || this.lockedOut()) {
        return;
      }
      const validator: Validator | null = this.validator;
      const board: GameBoard | null = this.board;
      if (validator === null || board === null) {
        return;
      }
      if (validator.isSolved(board.board)) {
        this.almostMessage = '';
        this.isSolved = true;
        this.onWin();
      }
    }, 1000);
  }

  private satisfiedKeys(): string[] {
    const validator: Validator | null = this.validator;
    const board: GameBoard | null = this.board;
    if (validator === null || board === null) {
      return [];
    }
    const result: string[] = [];
    validator.satisfiedClues(board.board).forEach((k: string) => result.push(k));
    return result;
  }

  private reportProgress(): void {
    const board: GameBoard | null = this.board;
    const level: Level | null = this.level;
    const callback = this.onProgress;
    if (board === null || level === null || callback === null) {
      return;
    }
    let filled: number = 0;
    for (let r = 0; r < level.rows; r++) {
      for (let c = 0; c < level.cols; c++) {
        if (board.board[r][c] === (CellState.FILLED as number)) {
          filled++;
        }
      }
    }
    callback(filled, this.elapsedSeconds);
  }

  private onWin(): void {
    const board: GameBoard | null = this.board;
    const level: Level | null = this.level;
    const account: AccountStore | null = this.account;
    if (board === null || level === null) {
      return;
    }
    if (!this.solvedReported) {
      this.solvedReported = true;
      SoundEffectManager.win(); // 通关音效(与安卓一致:只响一次)
      if (account !== null) {
        if (level.id > 0) {
          account.markCompleted(level.id);
        } else {
          account.recordOnlineSolved(level.id);
        }
      }
      const callback = this.onSolved;
      if (callback !== null) {
        const snapshot: number[][] = [];
        for (let r = 0; r < level.rows; r++) {
          snapshot.push(board.board[r].slice());
        }
        callback(snapshot, this.elapsedSeconds);
      }
    }
  }

  private strokeBegin(row: number, col: number): void {
    const board: GameBoard | null = this.board;
    if (board === null || this.lockedOut()) {
      return;
    }
    this.hintCell = '';
    // 起笔音效(一笔响一次,避免连涂太吵 —— 与安卓一致)
    SoundEffectManager.click();
    // 起笔决定本笔方向(与安卓端 strokeTarget 同构):
    // 涂黑笔落在已涂黑格上 = 这一笔是"擦除",否则是"涂黑"。
    this.strokeTarget = -1;
    board.startStroke();
    this.strokeCell(row, col);
  }

  private strokeCell(row: number, col: number): void {
    const board: GameBoard | null = this.board;
    if (board === null || this.lockedOut()) {
      return;
    }
    let target: CellState;
    if (this.tool === (CellState.MARKED_EMPTY as number)) {
      // 留白笔:标记"确定留白"
      target = CellState.MARKED_EMPTY;
    } else if (this.strokeTarget >= 0) {
      // 本笔方向已由起笔第一格决定,后续格子跟着走
      target = this.strokeTarget === (CellState.UNDECIDED as number)
        ? CellState.UNDECIDED
        : CellState.FILLED;
    } else {
      const state: number = row < board.board.length && col < board.board[row].length
        ? board.board[row][col]
        : (CellState.UNDECIDED as number);
      target = state === (CellState.FILLED as number) ? CellState.UNDECIDED : CellState.FILLED;
      this.strokeTarget = target as number;
    }
    if (board.strokeCell(row, col, target)) {
      this.version++;
      this.recompute();
    }
  }

  private strokeEnd(): void {
    const board: GameBoard | null = this.board;
    if (board === null) {
      return;
    }
    this.strokeTarget = -1;
    if (board.commitStroke()) {
      this.version++;
      this.reportProgress();
    }
    this.checkAlmostDone();
  }

  /**
   * 一笔结束后给出"还差多少"的提示。
   *
   * 为什么需要:涂黑笔在已涂黑的格子上是**擦除**语义(与安卓一致),所以玩家
   * "想确认一下"再点一格,很可能把刚涂好的格擦掉 —— 看着像全对了,其实少一格,
   * 于是既不弹通关也没反应。这里明确告诉他"还差 N 格",避免当成 bug。
   */
  private checkAlmostDone(): void {
    const validator: Validator | null = this.validator;
    const board: GameBoard | null = this.board;
    const level: Level | null = this.level;
    if (validator === null || board === null || level === null || this.lockedOut()) {
      return;
    }
    if (validator.isSolved(board.board)) {
      return; // 通了就交给通关流程,不打扰
    }
    let missing: number = 0;
    let extra: number = 0;
    for (let r = 0; r < level.rows; r++) {
      for (let c = 0; c < level.cols; c++) {
        const filled: boolean = board.board[r][c] === (CellState.FILLED as number);
        const want: boolean = level.answerGrid[r][c] === 1;
        if (want && !filled) {
          missing++;
        } else if (!want && filled) {
          extra++;
        }
      }
    }
    if (missing + extra === 0) {
      this.almostMessage = ''; // 理论上不会走到这里(isSolved 已拦)
      return;
    }
    if (missing + extra > 8) {
      this.almostMessage = '';
      return; // 差得多,属于正常进行中,不打扰
    }
    if (missing > 0 && extra > 0) {
      this.almostMessage = `还差 ${missing} 格没涂,另有 ${extra} 格涂多了(点「🔍 检查」可以直接标出来)`;
    } else if (missing > 0) {
      this.almostMessage = `🎯 就快好了!还差 ${missing} 格没涂`;
    } else {
      this.almostMessage = `⚠ 多涂了 ${extra} 格(涂黑笔点在黑格上是擦除)`;
    }
  }

  private strokeCancel(): void {
    const board: GameBoard | null = this.board;
    if (board === null) {
      return;
    }
    board.cancelStroke();
    this.version++;
  }

  private lockedOut(): boolean {
    return this.isSolved || this.timedOut || this.paused;
  }

  /**
   * 再来一局:把本关棋盘与计时全部复位(对应安卓 WinOverlay 的「再来一局」→ onReplay)。
   * 注意 solvedReported 也要复位,否则重玩通关后不会再触发上报。
   */
  private replay(): void {
    const level: Level | null = this.level;
    if (level === null) {
      return;
    }
    this.board = new GameBoard(level.rows, level.cols);
    this.validator = new Validator(level);
    this.isSolved = false;
    this.timedOut = false;
    this.solvedReported = false;
    this.peekedAnswer = false;
    this.showAnswer = false;
    this.showZoomed = false;
    this.checkedWrong = [];
    this.hasChecked = false;
    this.hintCell = '';
    this.hintMessage = '';
    this.elapsedSeconds = 0;
    this.lastTickAt = Date.now();
    this.strokeTarget = -1;
    this.version++;
    const callback = this.onReplay;
    if (callback !== null) {
      callback();
    }
  }

  /**
   * 顶栏副标题:难度 + 棋盘尺寸 + 交互提示。
   * (不再显示"一格 xx vp"那种调试信息 —— 对齐安卓端的口径)
   */
  private boardSubtitle(): string {
    const level: Level | null = this.level;
    if (level === null) {
      return '';
    }
    let text: string = `${difficultyLabel(level.difficulty)} ${'★'.repeat(difficultyStars(level.difficulty))}`;
    text += ` · ${level.rows}×${level.cols}`;
    text += this.mode === GameMode.TIMED ? ' · ⏱限时' : ' · 🎮自由';
    return text;
  }

  private undo(): void {
    const board: GameBoard | null = this.board;
    if (board === null || this.lockedOut()) {
      return;
    }
    if (board.undo()) {
      this.checkedWrong = [];
      this.hasChecked = false;
      this.hintCell = '';
      this.version++;
      this.reportProgress();
    }
  }

  private redo(): void {
    const board: GameBoard | null = this.board;
    if (board === null || this.lockedOut()) {
      return;
    }
    if (board.redo()) {
      this.checkedWrong = [];
      this.hasChecked = false;
      this.version++;
      this.reportProgress();
    }
  }

  private resetBoard(): void {
    const board: GameBoard | null = this.board;
    if (board === null) {
      return;
    }
    board.reset();
    this.checkedWrong = [];
    this.hasChecked = false;
    this.hintCell = '';
    this.elapsedSeconds = 0;
    this.timedOut = false;
    this.lastTickAt = Date.now();
    this.version++;
    this.reportProgress();
  }

  private checkErrors(): void {
    const validator: Validator | null = this.validator;
    const board: GameBoard | null = this.board;
    if (validator === null || board === null || this.lockedOut()) {
      return;
    }
    const wrongs: Set<string> = validator.wrongCells(board.board);
    const list: string[] = [];
    wrongs.forEach((k: string) => list.push(k));
    this.checkedWrong = list;
    this.hasChecked = true;
    this.version++;
    // 检查结果音效:有错报错,全对给个轻快的点击声(与安卓一致)
    if (list.length === 0) {
      SoundEffectManager.click();
    } else {
      SoundEffectManager.error();
    }
  }

  private clearWrong(): void {
    const board: GameBoard | null = this.board;
    if (board === null) {
      return;
    }
    const wrongs: Set<string> = new Set<string>(this.checkedWrong);
    board.startStroke();
    for (let r = 0; r < (this.level === null ? 0 : this.level.rows); r++) {
      for (let c = 0; c < (this.level === null ? 0 : this.level.cols); c++) {
        if (wrongs.has(`${r},${c}`)) {
          board.strokeCell(r, c, CellState.UNDECIDED);
        }
      }
    }
    board.commitStroke();
    this.checkedWrong = [];
    this.hasChecked = false;
    this.version++;
  }

  private requestHint(): void {
    const level: Level | null = this.level;
    const board: GameBoard | null = this.board;
    if (level === null || board === null || this.lockedOut()) {
      return;
    }
    SoundEffectManager.click();
    const hint: HintStep = nextHint(level.clueGrid, board.board);
    this.hintMessage = hint.message;
    this.hintCell = hint.hasCell ? `${hint.row},${hint.col}` : '';
    this.version++;
  }

  private async fillWithQuota(): Promise<void> {
    const level: Level | null = this.level;
    const board: GameBoard | null = this.board;
    const account: AccountStore | null = this.account;
    if (level === null || board === null || this.lockedOut()) {
      return;
    }
    if (account === null || account.fillQuotaRemaining <= 0) {
      promptAction.showToast({ message: '补齐额度已用完(每日登录 +2)' });
      return;
    }
    const used: boolean = await account.useFill();
    if (!used) {
      return;
    }
    board.applySolution(level.answerGrid);
    this.checkedWrong = [];
    this.hasChecked = false;
    this.hintCell = '';
    this.version++;
    this.recompute();
    this.reportProgress();
  }

  build() {
    Stack() {
      Column() {
        // ———— 顶栏:返回 / 关卡名 / 难度 / 计时 ————
        Row() {
          Button('←').fontSize(16).backgroundColor(Color.Transparent).fontColor(Colors.Ink)
            .onClick(() => this.onBack())
          Column() {
            Text(this.level === null ? '' : this.level.name)
              .fontSize(16).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
            Text(this.level === null ? '' : this.boardSubtitle())
              .fontSize(10).fontColor(Colors.Cocoa)
          }
          .alignItems(HorizontalAlign.Start)
          .layoutWeight(1)

          Column() {
            Text(this.timerText())
              .fontSize(16).fontWeight(FontWeight.Bold)
              .fontColor(this.timedOut || this.isLowTime() ? Colors.Danger : Colors.Cyan)
            Text(this.mode === GameMode.TIMED ? '剩余时间' : '自由模式')
              .fontSize(10)
              .fontColor(this.isLowTime() ? Colors.Danger : Colors.Cocoa)
          }
          .alignItems(HorizontalAlign.End)
        }
        .width('100%')
        .padding({ left: 8, right: 14, top: 6, bottom: 6 })

        // ———— 进度条:已涂 X / N ————
        Row() {
          Stack({ alignContent: Alignment.Start }) {
            Row()
              .width('100%').height(10)
              .backgroundColor(Colors.Sand)
              .borderRadius(5)
            Row()
              .width(`${this.progressPercent()}%`).height(10)
              .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
              .borderRadius(5)
          }
          .layoutWeight(1)
          .height(10)

          Text(`已涂 ${this.filledCount()} / ${this.totalFilled()}`)
            .fontSize(11).fontWeight(FontWeight.Bold).fontColor(Colors.Ink)
            .margin({ left: 8 })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 2, bottom: 2 })

        // ———— "只差几格"提示(涂黑笔在已涂黑格上是擦除,别让玩家以为卡住了)————
        if (this.almostMessage.length > 0 && !this.isSolved) {
          Text(this.almostMessage)
            .fontSize(12).fontWeight(FontWeight.Bold).fontColor(Colors.Amber)
            .backgroundColor(Colors.AmberContainer)
            .borderRadius(10)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .margin({ top: 6 })
            .onClick(() => {
              // 点一下直接帮他标出错误格,省得自己找
              this.checkErrors();
            })
        }

        // ———— 棋盘 ————
        Column() {
          if (this.level !== null) {
            BoardView({
              level: this.level,
              board: this.boardForView(),
              satisfiedClues: this.satisfiedKeys(),
              wrongCells: this.checkedWrong,
              hintCell: this.hintCell,
              tool: this.tool,
              interactive: !this.lockedOut(),
              // 通关后隐藏提示数字:棋盘变成一幅干净的像素画(与安卓 showClues=false 一致)
              showClues: !this.isSolved,
              version: this.version,
              // 换关(或同一屏切题)时重置视口,按新棋盘重新算"整盘适配"
              boardKey: this.level.id,
              onStrokeBegin: (r: number, c: number) => this.strokeBegin(r, c),
              onStrokeCell: (r: number, c: number) => this.strokeCell(r, c),
              onStrokeEnd: () => this.strokeEnd(),
              onStrokeCancel: () => this.strokeCancel(),
            })
          }
        }
        .layoutWeight(1)
        .width('100%')
        .padding({ left: 4, right: 4 })
        .borderRadius(12)
        .onAreaChange((_oldValue: Area, newValue: Area) => {
          // 记下棋盘区实际尺寸:顶栏用它算出"一格多少 vp",也方便按屏幕调字号
          this.boardAreaW = Number.parseFloat(newValue.width.toString());
          this.boardAreaH = Number.parseFloat(newValue.height.toString());
        })
        .clip(true)

        // ———— 提示/检查结果条 ————
        if (this.hintMessage.length > 0) {
          Text(`💡 ${this.hintMessage}`)
            .fontSize(11).fontColor(Colors.Amber)
            .padding({ top: 6 })
        }
        if (this.hasChecked) {
          Row() {
            Text(this.checkedWrong.length === 0
              ? '✅ 目前没有发现错误'
              : `🔍 发现 ${this.checkedWrong.length} 个错误格`)
              .fontSize(12)
              .fontColor(this.checkedWrong.length === 0 ? Colors.Cyan : Colors.Danger)
              .layoutWeight(1)
            if (this.checkedWrong.length > 0) {
              Button('清除').fontSize(12).height(34).backgroundColor(Colors.Sand)
                .onClick(() => this.clearWrong())
            }
            Button('关闭').fontSize(12).height(34).backgroundColor(Color.Transparent)
              .fontColor(Colors.Cocoa)
              .onClick(() => {
                this.hasChecked = false;
                this.checkedWrong = [];
              })
          }
          .width('100%')
          .padding({ left: 12, right: 12, top: 4 })
        }

        // ———— 工具行:涂黑笔 / 留白笔 ————
        Row() {
          Button('✏️ 涂黑笔')
            .fontSize(12).height(34).layoutWeight(1)
            .backgroundColor(this.tool === (CellState.FILLED as number) ? Colors.Coral : Colors.Sand)
            .fontColor(Colors.Ink)
            .onClick(() => {
              SoundEffectManager.click();
              this.tool = CellState.FILLED as number;
            })
          Button('✕ 留白笔')
            .fontSize(12).height(34).layoutWeight(1).margin({ left: 8 })
            .backgroundColor(this.tool === (CellState.MARKED_EMPTY as number) ? Colors.Cyan : Colors.Sand)
            .fontColor(Colors.Ink)
            .onClick(() => {
              SoundEffectManager.click();
              this.tool = CellState.MARKED_EMPTY as number;
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 6 })

        // 摇一摇重置提示条(3 秒后自动消失)
        if (this.shakeHint) {
          Text('📱 已摇一摇,棋盘已重置')
            .fontSize(12).fontWeight(FontWeight.Bold).fontColor(Colors.Amber)
            .backgroundColor(Colors.AmberContainer)
            .borderRadius(10)
            .padding({ left: 12, right: 12, top: 6, bottom: 6 })
            .margin({ top: 6 })
        }

        // ———— 操作行:分两排,每排 3 个(一排 6 个会挤到 ~60vp,字被截断还容易点不中)————
        Row() {
          Button('↩ 撤销')
            .fontSize(12).height(38).layoutWeight(1)
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => {
              if (!this.canUndo()) {
                promptAction.showToast({ message: '没有可撤销的操作' });
                return;
              }
              SoundEffectManager.click();
              this.undo();
            })
          Button('↪ 重做')
            .fontSize(12).height(38).layoutWeight(1).margin({ left: 6 })
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => {
              if (!this.canRedo()) {
                promptAction.showToast({ message: '没有可重做的操作' });
                return;
              }
              SoundEffectManager.click();
              this.redo();
            })
          Button('↺ 重置')
            .fontSize(12).height(38).layoutWeight(1).margin({ left: 6 })
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => {
              SoundEffectManager.click();
              this.resetBoard();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 6 })

        Row() {
          Button('💡 提示')
            .fontSize(12).height(38).layoutWeight(1)
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => this.requestHint())
          Button('🔍 检查')
            .fontSize(12).height(38).layoutWeight(1).margin({ left: 6 })
            .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
            .onClick(() => this.checkErrors())
          Button(this.fillLabel())
            .fontSize(12).height(38).layoutWeight(1).margin({ left: 6 })
            .backgroundColor(this.canFill() ? Colors.CoralContainer : Colors.SurfaceWhite)
            .fontColor(this.canFill() ? Colors.Amber : Colors.Cocoa)
            .onClick(() => {
              if (!this.canFill()) {
                promptAction.showToast({ message: '一键补齐次数已用完(每日登录 +2)' });
                return;
              }
              this.fillWithQuota();
            })
        }
        .width('100%')
        .padding({ left: 12, right: 12, top: 6 })

        Button(this.showAnswer ? '隐藏参考答案' : '👁 参考答案')
          .fontSize(11).height(30).backgroundColor(Color.Transparent).fontColor(Colors.Cocoa)
          .onClick(() => {
            this.showAnswer = !this.showAnswer;
            // 记录"看过答案":结算时提示一下,不惩罚(与安卓 peekedAnswer 一致)
            if (this.showAnswer) {
              this.peekedAnswer = true;
            }
          })

        if (this.showAnswer && this.level !== null) {
          // 真棋盘预览(只读、可双击放大):比原来的 6px 文字矩阵可读得多
          Column() {
            BoardView({
              level: this.level,
              board: this.level.answerGrid,
              satisfiedClues: [],
              wrongCells: [],
              hintCell: '',
              tool: 0,
              interactive: false,
              showClues: false,
              version: this.version,
              boardKey: this.level.id,
            })
          }
          .width('70%')
          .height(this.answerPreviewHeight())
          .margin({ top: 4 })
          .borderRadius(10)
          .clip(true)
        }

        Blank().height(10)
      }
      .width('100%')
      .height('100%')

      // ———— 通关 / 超时 遮罩 ————
      // 对战时调用方传 showOverlays = false:结算交给对战页自己的卡片,
      // 否则一解出题就被这张卡盖住,「返回」还会直接退房。
      if (this.showOverlays && (this.isSolved || this.timedOut)) {
        Column() {
          Text(this.isSolved ? '🎉 太棒了!' : '⏰ 时间到')
            .fontSize(26).fontWeight(FontWeight.Bolder).fontColor(Colors.Ink)
          if (this.isSolved && this.level !== null) {
            Text(`成功还原「${this.level.name}」`)
              .fontSize(13).fontColor(Colors.Cocoa).margin({ top: 4 })
          } else {
            Text('可以重置后重来,或关掉限时挑战')
              .fontSize(13).fontColor(Colors.Cocoa).margin({ top: 6 })
          }

          // 用时胶囊
          Text(`用时 ${this.formatTime(this.elapsedSeconds)}`)
            .fontSize(14).fontWeight(FontWeight.Bold).fontColor(Colors.Cyan)
            .backgroundColor(Colors.CyanContainer)
            .borderRadius(50)
            .padding({ left: 16, right: 16, top: 6, bottom: 6 })
            .margin({ top: 10 })
          if (this.peekedAnswer) {
            Text('(本关查看过参考答案)')
              .fontSize(11).fontColor(Colors.Cocoa).margin({ top: 4 })
          }

          // 揭晓的像素画预览:用轻量 PicturePreview(不建离屏画布),
          // 避免"通关瞬间同时创建第二个 Canvas 缓存"这种卡渲染的坑;
          // 想看细节点下面的「放大查看图案」(全屏、可缩放)。
          if (this.isSolved && this.level !== null) {
            Column() {
              PicturePreview({
                answer: this.level.answerGrid,
                ink: Colors.Coral,
              })
            }
            // 128vp 而不是 150:通关卡内容较多,小屏上要把竖向空间留给按钮,
            // 否则「下一关」会被挤出可视区(看起来就像按钮没反应)
            .width(128).height(128)
            .padding(6)
            .margin({ top: 10 })
            .borderRadius(14)
            .backgroundColor(Colors.Sand)
            .clip(true)

            Button('🔍 放大查看图案')
              .fontSize(12).height(30)
              .backgroundColor(Color.Transparent).fontColor(Colors.Coral)
              .onClick(() => {
                this.showZoomed = true;
              })
          }

          // ———— 主操作放最上面:小屏上卡片底部可能被挤出可视区,
          //      把「下一关」提到顶部能确保它一定在屏幕内、一定点得到 ————
          Row() {
            if (this.isSolved && this.hasNextLevel()) {
              Button('下一关 ▶')
                .fontSize(15).height(46).layoutWeight(1)
                .linearGradient({ angle: 90, colors: PRIMARY_GRADIENT })
                .onClick(() => this.gotoNext())
            }
            if (this.isSolved) {
              Button('再来一局')
                .fontSize(14).height(46).layoutWeight(1).margin({ left: 8 })
                .backgroundColor(Colors.Sand).fontColor(Colors.Ink)
                .onClick(() => this.replay())
            }
          }
          .width('100%').margin({ top: 16 })

          Button(this.isSolved ? '返回关卡列表' : '返回')
            .fontSize(12).height(34).backgroundColor(Color.Transparent).fontColor(Colors.Cocoa)
            .onClick(() => this.onBack())
        }
        .width('86%')
        .padding(22)
        .borderRadius(20)
        .backgroundColor(Colors.SurfaceWhite)
        .border({ width: 1, color: Colors.GlassLine })
      }

      // ———— 全屏放大查看像素画(双指缩放 + 双击放大 + 右上角关闭)————
      if (this.showZoomed && this.level !== null) {
        Column() {
          BoardView({
            level: this.level,
            board: this.level.answerGrid,
            satisfiedClues: [],
            wrongCells: [],
            hintCell: '',
            tool: 0,
            interactive: false,
            showClues: false,
            version: this.version,
            boardKey: this.level.id,
          })
            .width('94%')
            .layoutWeight(1)

          Text('双指缩放 / 双击放大 · 点右上角关闭')
            .fontSize(11).fontColor(Colors.Cocoa).margin({ bottom: 10 })
        }
        .width('100%')
        .height('100%')
        .backgroundColor('#F2111118')
        .onClick(() => {
          this.showZoomed = false;
        })
      }
    }
    .width('100%')
    .height('100%')
    .linearGradient({ angle: 180, colors: PAGE_GRADIENT })
  }

  /** 当前棋盘快照:通关揭晓时改成"答案盘",让画面变成一幅干净的像素画(安卓同款做法)。 */
  private boardForView(): number[][] {
    const level: Level | null = this.level;
    const board: GameBoard | null = this.board;
    if (this.isSolved && level !== null) {
      return level.answerGrid;
    }
    return board === null ? [] : board.board;
  }

  /** 限时且剩余不足 60 秒:计时胶囊变红(与安卓 isLow 一致)。 */
  private isLowTime(): boolean {
    return this.mode === GameMode.TIMED && this.remainingSeconds() < 60;
  }

  /** 进度百分比(0~100,给进度条宽度用)。 */
  private progressPercent(): number {
    const total: number = this.totalFilled();
    if (total <= 0) {
      return 0;
    }
    let pct: number = Math.round(this.filledCount() * 100 / total);
    if (pct < 0) {
      pct = 0;
    }
    if (pct > 100) {
      pct = 100;
    }
    return pct;
  }

  /** 顶栏计时文案:限时显示倒计时,自由显示已用时(与安卓一致)。 */
  private timerText(): string {
    if (this.mode === GameMode.TIMED) {
      return `⏱ ${this.formatTime(this.remainingSeconds())}`;
    }
    return `⏱ ${this.formatTime(this.elapsedSeconds)}`;
  }

  /** 撤销/重做/补齐按钮的可用性(与安卓 canUndo/canRedo/额度禁用一致)。 */
  private canUndo(): boolean {
    const board: GameBoard | null = this.board;
    return board !== null && board.canUndo && !this.lockedOut();
  }

  private canRedo(): boolean {
    const board: GameBoard | null = this.board;
    return board !== null && board.canRedo && !this.lockedOut();
  }

  private canFill(): boolean {
    const account: AccountStore | null = this.account;
    return account !== null && account.fillQuotaRemaining > 0 && !this.lockedOut();
  }

  /** 补齐按钮文案:显示剩余次数 / 已用完(与安卓一致)。 */
  private fillLabel(): string {
    const account: AccountStore | null = this.account;
    if (account === null) {
      return '⚡ 补齐';
    }
    if (account.fillQuotaRemaining <= 0) {
      return '⚡ 已用完';
    }
    return `⚡ 补齐 ${account.fillQuotaRemaining}`;
  }

  /** 参考答案预览的高度:按行列比给个合适值,避免拉太高把棋盘挤没了。 */
  private answerPreviewHeight(): number {
    const level: Level | null = this.level;
    if (level === null || level.cols <= 0) {
      return 140;
    }
    const w: number = this.boardAreaW > 0 ? this.boardAreaW * 0.7 : 260;
    let h: number = w * level.rows / level.cols;
    if (h > 220) {
      h = 220;
    }
    if (h < 90) {
      h = 90;
    }
    return h;
  }

  /** 下一关:带**显式反馈**的转发。 */
  private gotoNext(): void {
    Diag.log('gotoNext', `enter onNextLevel=${this.onNextLevel === null ? 'null' : 'fn'} ` +
      `levelId=${this.level === null ? -1 : this.level.id} solved=${this.isSolved}`);
    const callback = this.onNextLevel;
    if (callback === null) {
      promptAction.showToast({ message: '这是最后一关啦(或当前模式没有下一关)' });
      return;
    }
    try {
      SoundEffectManager.click();
      callback();
      Diag.log('gotoNext', 'callback returned OK');
    } catch (e) {
      const err = e as Error;
      Diag.log('gotoNext', `callback threw: ${err.message}`);
      promptAction.showToast({ message: `进入下一关失败:${err.message}` });
    }
  }

  /**
   * 是否真的存在下一关。
   *
   * 之前按钮只判断 `onNextLevel !== null`,而根页面永远传着回调 —— 到了第 10 关
   * 点下去就是"回关卡列表",用户会觉得按钮坏了。这里按关卡表实算一次。
   */
  private hasNextLevel(): boolean {
    const level: Level | null = this.level;
    if (level === null || level.id <= 0) {
      return false; // 在线关(id < 0)没有顺序下一关
    }
    const all: Level[] = Levels.all;
    for (let i = 0; i < all.length; i++) {
      if (all[i].id === level.id) {
        return i + 1 < all.length;
      }
    }
    return false;
  }

  private formatTime(seconds: number): string {
    const m: number = Math.floor(seconds / 60);
    const s: number = seconds % 60;
    return `${m < 10 ? '0' : ''}${m}:${s < 10 ? '0' : ''}${s}`;
  }

  /** 参考答案的文字预览(# 涂黑 / · 留白),用于"实在推不出来"时对照。 */
  private answerPreview(): string {
    const level: Level | null = this.level;
    if (level === null) {
      return '';
    }
    const lines: string[] = [];
    for (let r = 0; r < level.rows; r++) {
      let line: string = '';
      for (let c = 0; c < level.cols; c++) {
        line += level.answerGrid[r][c] === 1 ? '#' : '·';
      }
      lines.push(line);
    }
    return lines.join('\n');
  }
}
