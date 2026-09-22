import { CellState, cellKey } from '../model/CellState';
import { Level } from '../model/Level';

/** 画布底色:与页面夜幕同色(棋盘之外不出现第二种底色)。 */
const CANVAS_BG: string = '#100E1C';
/** 纸面(空白格底色):暖白画纸。 */
const PAPER: string = '#FAF9FF';
/** 标记留白格:浅薰衣草。 */
const MARKED_BG: string = '#E9E5F7';
/** 标记留白的小圆点。 */
const MARK_DOT: string = '#A5A0C4';
/** 已涂黑格:深墨紫。 */
const FILLED: string = '#37324F';
/** 网格线:淡薰衣草。 */
const GRID_LINE: string = '#E2DDF1';
/** 提示格:琥珀光晕 + 描边。 */
const HINT_GLOW: string = '#66FFC94D';
const HINT_BORDER: string = '#FFB300';
/** 错误格:浅红底 + 红叉。 */
const WRONG_BG: string = '#FFD6D6';
const WRONG_BORDER: string = '#D32F2F';

/**
 * 棋盘绘制与手势(对应 Android 版 ui/BoardView.kt)。
 *
 * 视口模型**与安卓端逐条对齐**(这是关键:之前用"铺满=让棋盘溢出视口"的写法,
 * 关卡一大格子就顶出边界,是错的):
 *
 *   1. **1× = 整盘适配**:格子边长 = `min(视口宽/列数, 视口高/行数)`,
 *      整盘恒定完整地铺在视口里,永远不会超出边界;
 *   2. `zoom ∈ [1, 12]`:1× 就是全貌,放大只做"看细节";
 *   3. 平移钳制**按分量各自比较**(`offset.x ∈ [视口宽 - 宽×zoom, 0]`),
 *      所以放大时棋盘始终覆盖视口、缩小时恒为 0(整盘居中);
 *   4. 放大后(> 1.05×)右上角才出现**圆形「复位」**,一键回 1×;未放大时不显示,不挡格子。
 *
 * 交互约定(与安卓一致):
 *   单指拖动 = 沿轨迹连续涂一格宽的一条笔划(整笔 = 一步撤销);
 *   双指     = 捏合缩放 + 平移,并在第二指落下时取消当前笔划(防误涂);
 *   双击     = 1× ⇄ 2×。
 *
 * 性能(鸿蒙预览器上"涂得慢"的修法):
 *   纸面 + 网格 + 数字预渲染到 OffscreenCanvas 的 ImageBitmap,每帧只做一次 drawImage;
 *   网格用 O(N) 线条而不是逐格 strokeRect;空白格跳过不画。
 */
@Component
export struct BoardView {
  /** 关卡(答案/提示来源)。 */
  level: Level | null = null;
  /** 当前棋盘快照(board[r][c] = CellState)。 */
  @Prop board: number[][] = [];
  /** 已满足的提示(数字变绿)。 */
  @Prop satisfiedClues: string[] = [];
  /** 检查出的错误格(标红)。 */
  @Prop wrongCells: string[] = [];
  /** 提示高亮格("r,c");空串表示无。 */
  @Prop hintCell: string = '';
  /** 当前工具:1 = 涂黑笔,2 = 留白笔。 */
  @Prop tool: number = 1;
  /** 是否可交互(通关/超时后停手)。 */
  @Prop interactive: boolean = true;
  /**
   * 揭晓模式:隐藏提示数字与 ✕ 点,只留纯净的像素画。
   * 对应安卓 BoardView 的 `showClues = false`(通关揭晓 / 放大查看图案用)。
   */
  @Prop @Watch('onShowCluesChanged') showClues: boolean = true;
  /** 棋盘版本号:变化即重绘(ArkUI 的 Canvas 不会自动跟随状态重绘)。 */
  @Prop @Watch('onVersionChanged') version: number = 0;
  /** 换关标识(关卡 id)。变化时重置视口。 */
  @Prop @Watch('onBoardKeyChanged') boardKey: number = 0;

  onStrokeBegin: (row: number, col: number) => void = () => {
  };
  onStrokeCell: (row: number, col: number) => void = () => {
  };
  onStrokeEnd: () => void = () => {
  };
  onStrokeCancel: () => void = () => {
  };

  private settings: RenderingContextSettings = new RenderingContextSettings(true);
  private ctx: CanvasRenderingContext2D = new CanvasRenderingContext2D(this.settings);
  private canvasW: number = 0;
  private canvasH: number = 0;
  // 注意:字段名不能叫 scale —— 与 ArkUI 组件内置的 scale() 属性方法重名会编译失败
  /** 缩放倍率:1 = 整盘适配(与安卓 MinZoom 对应)。 */
  @State private zoom: number = 1;
  @State private panX: number = 0;
  @State private panY: number = 0;
  private pinchStartZoom: number = 1;
  private pinchStartX: number = 0;
  private pinchStartY: number = 0;
  /** onTouch 用的状态:本笔是否进行中 / 上一格 / 上次落点(双击判定)。 */
  private strokeActive: boolean = false;
  private lastCellKey: string = '';
  private lastTapAt: number = 0;
  private lastTapX: number = 0;
  private lastTapY: number = 0;
  /** 双指捏合接管中(此时忽略单指涂色)。 */
  private pinchTakeover: boolean = false;
  /** 静态层(纸面 + 网格 + 数字)缓存。 */
  private staticLayer: ImageBitmap | null = null;
  private staticLayerReady: boolean = false;
  private staticLayerBuilding: boolean = false;
  /** 建缓存时的"已满足"数量:变了就重建(否则数字颜色会不刷新)。 */
  private layerClueCount: number = -1;

  /** 与安卓端一致:1× 全貌,最大 12×(看清单个格子)。 */
  private static readonly ZOOM_MIN: number = 1;
  private static readonly ZOOM_MAX: number = 12;
  private static readonly RESET_THRESHOLD: number = 1.05;

  onVersionChanged(): void {
    this.draw();
  }

  onShowCluesChanged(): void {
    // 揭晓模式切换会改变静态层内容(数字画不画),作废重建
    this.invalidateStaticLayer();
    this.draw();
  }

  onBoardKeyChanged(): void {
    this.resetView();
  }

  /** 换关:回到 1×(整盘适配)并让静态层失效。 */
  private resetView(): void {
    this.invalidateStaticLayer();
    this.zoom = BoardView.ZOOM_MIN;
    this.panX = 0;
    this.panY = 0;
    this.draw();
  }

  /**
   * 1× 时的格子边长(vp)—— **整盘适配**:取宽向与高向里较小的那个,保证行列都不越界。
   * 对应安卓的 `cellW = size.width / cols`、`cellH = size.height / rows` 两者同缩放。
   */
  private fitCell(): number {
    const level: Level | null = this.level;
    if (level === null || this.canvasW <= 0 || this.canvasH <= 0) {
      return 0;
    }
    return Math.min(this.canvasW / level.cols, this.canvasH / level.rows);
  }

  /** 当前格子边长 = 适配边长 × 缩放。 */
  private cell(): number {
    return this.fitCell() * this.zoom;
  }

  /**
   * 棋盘左上角在画布中的位置(居中 + 平移)。
   * 与安卓 clampViewOffset 同口径:**按分量各自比较** ——
   * 该方向内容比视口小就恒为居中(偏移 0),比视口大就钳制在 [视口-内容, 0]。
   */
  private originX(): number {
    const level: Level | null = this.level;
    if (level === null) {
      return 0;
    }
    const contentW: number = this.cell() * level.cols;
    if (contentW <= this.canvasW) {
      return (this.canvasW - contentW) / 2;
    }
    return this.clamp(this.panX, this.canvasW - contentW, 0);
  }

  private originY(): number {
    const level: Level | null = this.level;
    if (level === null) {
      return 0;
    }
    const contentH: number = this.cell() * level.rows;
    if (contentH <= this.canvasH) {
      return (this.canvasH - contentH) / 2;
    }
    return this.clamp(this.panY, this.canvasH - contentH, 0);
  }

  private clamp(value: number, min: number, max: number): number {
    if (value < min) {
      return min;
    }
    return value > max ? max : value;
  }

  /** 触点到格子;越界返回 null。 */
  private boardIndexAt(x: number, y: number): number[] | null {
    const level: Level | null = this.level;
    if (level === null) {
      return null;
    }
    const size: number = this.cell();
    if (size <= 0) {
      return null;
    }
    const col: number = Math.floor((x - this.originX()) / size);
    const row: number = Math.floor((y - this.originY()) / size);
    if (row < 0 || row >= level.rows || col < 0 || col >= level.cols) {
      return null;
    }
    return [row, col];
  }

  private clueColor(value: number): string {
    if (value <= 0) {
      return '#A89A82';
    }
    if (value <= 2) {
      return '#1E88E5';
    }
    if (value <= 4) {
      return '#00838F';
    }
    if (value <= 6) {
      return '#6A1B9A';
    }
    return '#D32F2F';
  }

  /**
   * 字号:跟着格子边长走。
   *
   * 安卓端用 `max(格宽, 格高) × 0.46`,在手机 28×28 的盘面上只有 ~5px,看不清;
   * 鸿蒙这边放宽到 `× 0.60`、下限 12vp、上限 30vp —— 同样盘面下数字明显更大,
   * 又不会把相邻线索挤在一起(提示窗最多 3 列,60% 已接近可读上限)。
   */
  private clueFontPx(size: number): number {
    let px: number = Math.round(size * 0.60);
    if (px < 12) {
      px = 12;
    }
    if (px > 30) {
      px = 30;
    }
    return px;
  }

  // ---------------- 静态层(纸面 + 网格 + 数字) ----------------

  /**
   * 把纸面、网格线、提示数字画到给定上下文。
   *
   * 参数类型故意放宽成 `object` 再在函数内断言:ArkTS 里
   * `OffscreenCanvasRenderingContext2D` 与 `CanvasRenderingContext2D` 是两个类型,
   * 直接写联合类型会让 fillStyle 赋值报类型不匹配。两者绘图 API 同名,只用公共子集。
   */
  private paintStatic(ctx: object): void {
    const level: Level | null = this.level;
    if (level === null) {
      return;
    }
    const t = ctx as CanvasRenderingContext2D;
    const size: number = this.cell();
    const ox: number = this.originX();
    const oy: number = this.originY();
    if (size <= 0) {
      return;
    }
    const w: number = size * level.cols;
    const h: number = size * level.rows;

    // 纸面
    t.fillStyle = PAPER;
    t.fillRect(ox, oy, w, h);

    // 网格:每行 / 每列各一条线放进同一个 path,路径数 O(N) 而不是 O(N²)
    t.strokeStyle = GRID_LINE;
    t.lineWidth = 1;
    t.beginPath();
    for (let c = 0; c <= level.cols; c++) {
      const x: number = ox + c * size;
      t.moveTo(x, oy);
      t.lineTo(x, oy + h);
    }
    for (let r = 0; r <= level.rows; r++) {
      const y: number = oy + r * size;
      t.moveTo(ox, y);
      t.lineTo(ox + w, y);
    }
    t.stroke();

    // 提示数字(会随"已满足/已涂黑"变色,所以这层在满足数量变化时重建)。
    // 揭晓模式(showClues = false)不画数字,让涂黑格合成一幅干净的像素画。
    if (!this.showClues) {
      return;
    }
    const satisfied: Set<string> = new Set<string>(this.satisfiedClues);
    const fontPx: number = this.clueFontPx(size);
    t.font = `bold ${fontPx}px sans-serif`;
    t.textAlign = 'center';
    t.textBaseline = 'middle';
    const dy: number = fontPx * 0.04;
    for (let r = 0; r < level.rows; r++) {
      for (let c = 0; c < level.cols; c++) {
        const clue: number = level.clueGrid[r][c];
        if (clue < 0) {
          continue;
        }
        const key: string = cellKey(r, c);
        const isFilled: boolean = r < this.board.length && c < this.board[r].length
          && this.board[r][c] === (CellState.FILLED as number);
        if (satisfied.has(key)) {
          t.fillStyle = isFilled ? '#9BE8C0' : '#1B8A5A';
        } else {
          t.fillStyle = isFilled ? '#FFFFFF' : this.clueColor(clue);
        }
        t.fillText(clue.toString(),
          ox + c * size + size / 2, oy + r * size + size / 2 + dy);
      }
    }
  }

  /**
   * 构建静态层缓存。
   *
   * ArkTS 里 `transferToImageBitmap()` 是**同步**返回 ImageBitmap(不是 Promise)。
   * 任何一步失败(预览器不支持离屏画布等)都只是退回"每帧直画",功能不受影响。
   */
  private buildStaticLayer(): void {
    if (this.staticLayerBuilding || this.canvasW <= 0 || this.canvasH <= 0) {
      return;
    }
    this.staticLayerBuilding = true;
    const layerClues: number = this.satisfiedClues.length;
    try {
      const off = new OffscreenCanvas(this.canvasW, this.canvasH);
      const offCtx = off.getContext('2d');
      if (offCtx !== null) {
        this.paintStatic(offCtx);
        const bmp: ImageBitmap = off.transferToImageBitmap();
        this.staticLayer = bmp;
        this.staticLayerReady = true;
        this.layerClueCount = layerClues;
      }
    } catch (e) {
      this.staticLayer = null;
      this.staticLayerReady = false;
    }
    this.staticLayerBuilding = false;
    this.draw();
  }

  /** 视口变化(缩放/平移/换关)时让静态层失效,下一帧重建。 */
  private invalidateStaticLayer(): void {
    this.staticLayer = null;
    this.staticLayerReady = false;
    this.layerClueCount = -1;
  }

  /** 复位:回 1×(整盘适配)。 */
  private resetZoom(): void {
    this.zoom = BoardView.ZOOM_MIN;
    this.panX = 0;
    this.panY = 0;
    this.invalidateStaticLayer();
    this.draw();
  }

  /** 1× ⇄ 2× 切换(双击用;只读棋盘也能放大)。 */
  private toggleZoom(): void {
    if (this.zoom > BoardView.ZOOM_MIN + 0.01) {
      this.resetZoom();
      return;
    }
    this.zoom = 2;
    const level: Level | null = this.level;
    if (level !== null) {
      this.panX = (this.cell() * level.cols - this.canvasW) / 2;
      this.panY = (this.cell() * level.rows - this.canvasH) / 2;
    }
    this.invalidateStaticLayer();
    this.draw();
  }

  /**
   * 一个"点击落笔"的完整笔划:开始 → 涂这一格 → 提交。
   * 与安卓端"落下即落笔"一致(整笔 = 一步撤销)。
   */
  private tapAt(x: number, y: number): void {
    const cell: number[] | null = this.boardIndexAt(x, y);
    if (cell === null) {
      return;
    }
    this.onStrokeBegin(cell[0], cell[1]);
    this.onStrokeEnd();
  }

  /**
   * 触摸/鼠标事件处理 —— **单指涂色不走手势系统**。
   *
   * 为什么:ArkUI 的 PanGesture 必须有位移才会触发(点一下不动就永远不回调),
   * 而 TapGesture 在预览器的鼠标点击下也不一定被识别 —— 表现就是"鼠标点了没反应"。
   * 安卓端是直接读 down/move/up 的指针事件,这里改用 onTouch 与它同构:
   *   按下 = 落笔并涂第一格;移动 = 沿轨迹连涂;抬起 = 提交(整笔一步撤销);
   *   第二根手指落下 = 取消本笔(交给 PinchGesture 缩放,防误涂);
   *   快速双击 = 1× ⇄ 2×(用时间差自己判定,不依赖 TapGesture)。
   */
  private handleTouch(event: TouchEvent): void {
    const touches: TouchObject[] = event.touches;
    const all: TouchObject[] = event.changedTouches;

    // 第二根手指落下:取消当前笔划,让 PinchGesture 接管缩放
    if (touches.length >= 2) {
      if (this.strokeActive) {
        this.onStrokeCancel();
        this.strokeActive = false;
      }
      this.pinchTakeover = true;
      return;
    }
    if (this.pinchTakeover) {
      if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
        this.pinchTakeover = false;
      }
      return;
    }

    if (event.type === TouchType.Down) {
      if (all.length === 0) {
        return;
      }
      const x: number = all[0].x;
      const y: number = all[0].y;
      const now: number = Date.now();
      // 双击(350ms 内、落点相近)= 1× ⇄ 2×。
      // 这条**不要求 interactive** —— 揭晓/参考答案那种只读棋盘也要能放大看。
      if (now - this.lastTapAt < 350 && Math.abs(x - this.lastTapX) < 40
        && Math.abs(y - this.lastTapY) < 40) {
        this.lastTapAt = 0;
        this.toggleZoom();
        return;
      }
      this.lastTapAt = now;
      this.lastTapX = x;
      this.lastTapY = y;
      if (!this.interactive) {
        return;
      }
      this.lastCellKey = '';
      const cell: number[] | null = this.boardIndexAt(x, y);
      if (cell !== null) {
        this.lastCellKey = cellKey(cell[0], cell[1]);
        this.onStrokeBegin(cell[0], cell[1]);
        this.strokeActive = true;
      }
      return;
    }

    if (event.type === TouchType.Move) {
      if (!this.strokeActive || !this.interactive || all.length === 0) {
        return;
      }
      const x: number = all[0].x;
      const y: number = all[0].y;
      const cell: number[] | null = this.boardIndexAt(x, y);
      if (cell === null) {
        return;
      }
      const key: string = cellKey(cell[0], cell[1]);
      if (key === this.lastCellKey) {
        return; // 同一格的重复事件丢掉,别白重绘
      }
      this.lastCellKey = key;
      this.onStrokeCell(cell[0], cell[1]);
      return;
    }

    if (event.type === TouchType.Up || event.type === TouchType.Cancel) {
      if (this.strokeActive) {
        this.strokeActive = false;
        this.lastCellKey = '';
        if (event.type === TouchType.Up) {
          this.onStrokeEnd();
        } else {
          this.onStrokeCancel();
        }
      }
    }
  }

  // ---------------- 绘制 ----------------

  draw(): void {
    const level: Level | null = this.level;
    const ctx: CanvasRenderingContext2D = this.ctx;
    if (this.canvasW <= 0 || this.canvasH <= 0) {
      return;
    }
    // 整张画布铺页面底色:纸面之外不会有第二种底色
    ctx.fillStyle = CANVAS_BG;
    ctx.fillRect(0, 0, this.canvasW, this.canvasH);
    if (level === null) {
      return;
    }
    const size: number = this.cell();
    if (size <= 0) {
      return;
    }

    // 静态层:有缓存就一次 blit,没有就先直画并同步建缓存
    if (this.staticLayerReady && this.layerClueCount !== this.satisfiedClues.length) {
      this.staticLayerReady = false;
      this.staticLayer = null;
    }
    if (this.staticLayerReady && this.staticLayer !== null) {
      ctx.drawImage(this.staticLayer, 0, 0, this.canvasW, this.canvasH);
    } else {
      this.paintStatic(ctx);
      this.buildStaticLayer();
    }

    const ox: number = this.originX();
    const oy: number = this.originY();
    const satisfied: Set<string> = new Set<string>(this.satisfiedClues);
    const wrong: Set<string> = new Set<string>(this.wrongCells);

    // 动态层:只画"有状态的格子"(涂黑/留白/错误/提示)
    for (let r = 0; r < level.rows; r++) {
      for (let c = 0; c < level.cols; c++) {
        const state: number = r < this.board.length && c < this.board[r].length
          ? this.board[r][c]
          : (CellState.UNDECIDED as number);
        const key: string = cellKey(r, c);
        const isWrong: boolean = wrong.has(key);
        const isHint: boolean = this.hintCell === key;
        const isMarked: boolean = state === (CellState.MARKED_EMPTY as number);
        const isFilled: boolean = state === (CellState.FILLED as number);
        if (!isWrong && !isHint && !isMarked && !isFilled) {
          continue;
        }
        const x: number = ox + c * size;
        const y: number = oy + r * size;

        ctx.fillStyle = isWrong ? WRONG_BG : (isFilled ? FILLED : MARKED_BG);
        ctx.fillRect(x, y, size, size);
        if (isHint && !isWrong) {
          ctx.fillStyle = HINT_GLOW;
          ctx.fillRect(x, y, size, size);
        }

        if (isFilled) {
          // 涂黑格要把数字重新画成浅色(静态层上那层数字被底色盖住了)。
          // 揭晓模式(showClues = false)不画:让涂黑格合成完整图案。
          const clue: number = level.clueGrid[r][c];
          if (this.showClues && clue >= 0) {
            const fontPx: number = this.clueFontPx(size);
            ctx.fillStyle = satisfied.has(key) ? '#9BE8C0' : '#FFFFFF';
            ctx.font = `bold ${fontPx}px sans-serif`;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText(clue.toString(), x + size / 2, y + size / 2 + fontPx * 0.04);
          }
        } else if (isMarked && !isWrong && this.showClues) {
          ctx.fillStyle = MARK_DOT;
          ctx.beginPath();
          ctx.arc(x + size / 2, y + size / 2, size * 0.15, 0, Math.PI * 2);
          ctx.fill();
        }

        if (isWrong) {
          ctx.strokeStyle = WRONG_BORDER;
          ctx.lineWidth = Math.max(1.5, size * 0.12);
          const pad: number = size * 0.24;
          ctx.beginPath();
          ctx.moveTo(x + pad, y + pad);
          ctx.lineTo(x + size - pad, y + size - pad);
          ctx.moveTo(x + size - pad, y + pad);
          ctx.lineTo(x + pad, y + size - pad);
          ctx.stroke();
          ctx.lineWidth = 1.5;
          ctx.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);
        } else if (isHint) {
          ctx.strokeStyle = HINT_BORDER;
          ctx.lineWidth = Math.max(1.5, size * 0.08);
          ctx.strokeRect(x + 0.5, y + 0.5, size - 1, size - 1);
        }
      }
    }
  }

  build() {
    Stack() {
      Canvas(this.ctx)
        .width('100%')
        .height('100%')
        .onReady(() => {
          if (this.canvasW <= 0) {
            this.canvasW = 320;
            this.canvasH = 460;
          }
          this.draw();
        })
        .onAreaChange((_oldValue: Area, newValue: Area) => {
          const w: number = Number.parseFloat(newValue.width.toString());
          const h: number = Number.parseFloat(newValue.height.toString());
          if (w === this.canvasW && h === this.canvasH) {
            return;
          }
          this.canvasW = w;
          this.canvasH = h;
          // 1× 就是整盘适配,尺寸一变重新算即可;静态层作废重建
          this.invalidateStaticLayer();
          this.draw();
        })
        .onTouch((event: TouchEvent) => {
          this.handleTouch(event);
        })
        .gesture(
          // 只保留双指捏合做缩放/平移。
          // 单指/鼠标点击**不走手势系统** —— 见 handleTouch() 的说明:
          // PanGesture 要求位移、TapGesture 在预览器的鼠标点击下不一定被识别,
          // 直接读 touch 事件才和安卓端"落下即落笔"等价。
          PinchGesture({ fingers: 2 })
            .onActionStart(() => {
              this.onStrokeCancel();
              this.strokeActive = false;
              this.pinchStartZoom = this.zoom;
              this.pinchStartX = this.panX;
              this.pinchStartY = this.panY;
            })
            .onActionUpdate((event: GestureEvent) => {
              this.zoom = this.clampNumber(this.pinchStartZoom * event.scale,
                BoardView.ZOOM_MIN, BoardView.ZOOM_MAX);
              this.panX = this.pinchStartX + event.offsetX;
              this.panY = this.pinchStartY + event.offsetY;
              this.invalidateStaticLayer();
              this.draw();
            })
            .onActionEnd(() => {
              this.draw();
            }),
        )

      // 放大后右上角出现「复位」(与安卓一致:未放大时不显示,不挡格子)
      if (this.zoom > BoardView.RESET_THRESHOLD && this.canvasW > 0) {
        Text('复位')
          .fontSize(12)
          .fontWeight(FontWeight.Bold)
          .fontColor('#F0EFFB')
          .backgroundColor('#D9262338')
          .border({ width: 1, color: '#33FFFFFF' })
          .borderRadius(16)
          .padding({ left: 12, right: 12, top: 5, bottom: 5 })
          .margin({ right: 8, top: 8 })
          .align(Alignment.TopEnd)
          .onClick(() => this.resetZoom())
      }
    }
    .width('100%')
    .height('100%')
  }

  /** 数值钳制(与上面的 clamp 分开,避免与像素坐标的语义混淆)。 */
  private clampNumber(value: number, min: number, max: number): number {
    if (value < min) {
      return min;
    }
    return value > max ? max : value;
  }
}
