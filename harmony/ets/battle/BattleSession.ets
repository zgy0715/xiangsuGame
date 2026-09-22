import { levelFromDto, levelToDto } from '../api/LevelMapper';
import { LevelDto } from '../api/Dtos';
import { Diag } from '../data/Diag';
import { Level } from '../model/Level';
import { BattleProtocol, DecodedFrame, ChoosePuzzlePayload, ErrorPayload, FinishPayload,
  FinishUpPayload, PlayerLeftPayload, ProgressPayload, ProgressUpPayload, RacePlayer,
  RaceStartPayload as RaceStart, ResultEntry, ResultPayload, RoomStatePayload,
  TimeSyncPayload } from './BattleProtocol';
import { BattleTransport, BattleTransportListener } from './BattleTransport';

/** 对战侧身份(网络房:房主 / 房客)。 */
export enum BattleRole {
  HOST = 0,
  GUEST = 1,
}

/** 房间阶段(与服务器 phase 字符串一致)。 */
export enum BattlePhase {
  LOBBY = 0,
  RACING = 1,
  SETTLED = 2,
  CLOSED = 3,
}

/**
 * 对战会话状态机(对应 Android 版 battle/BattleSession.kt)。
 *
 * 与传输无关:网络对战只是它的一种传输。状态是普通字段 + onChange 回调,
 * UI 侧把关心的字段抄进自己的 @State 里渲染(ArkUI 没有 Compose 的自动可观察对象)。
 */
export class BattleSession {
  readonly role: BattleRole;
  readonly myUserId: number;
  readonly roomTag: string;
  readonly canReconnect: boolean;

  phase: BattlePhase = BattlePhase.LOBBY;
  hostId: number = -1;
  players: RacePlayer[] = [];
  puzzleName: string = '';
  raceLevel: Level | null = null;
  serverStartMs: number = 0;
  clockOffsetMs: number = 0;
  mySubmitted: boolean = false;
  myFinalRank: number = 0;
  resultEntries: ResultEntry[] = [];
  notice: string = '';
  fatal: string = '';

  /** 状态变化通知(UI 用它把数据抄进 @State)。 */
  onChange: () => void = () => {
  };

  private readonly transport: BattleTransport;
  private lastProgressSent: number = 0;
  private syncSentAt: number = 0;

  constructor(transport: BattleTransport, role: BattleRole, myUserId: number,
              roomTag: string, canReconnect: boolean) {
    this.transport = transport;
    this.role = role;
    this.myUserId = myUserId;
    this.roomTag = roomTag;
    this.canReconnect = canReconnect;
  }

  get isHost(): boolean {
    return this.role === BattleRole.HOST;
  }

  /** 竞速中已完成的玩家,按名次升序(用于"第几名是谁"的非阻断提示)。 */
  finishedPlayers(): RacePlayer[] {
    const list: RacePlayer[] = this.players.filter((p: RacePlayer) => p.finishedRank > 0);
    list.sort((a: RacePlayer, b: RacePlayer) => a.finishedRank - b.finishedRank);
    return list;
  }

  /** 已有人完成(仍在竞速中)—— 驱动提示横幅,不冻结棋盘。 */
  someoneFinished(): boolean {
    return this.phase === BattlePhase.RACING
      && this.players.some((p: RacePlayer) => p.finishedRank > 0);
  }

  /** 我已提交但服务器尚未结算(还有人在做)。 */
  awaitingMyResult(): boolean {
    return this.phase === BattlePhase.RACING && this.mySubmitted
      && this.players.some((p: RacePlayer) => p.finishedRank === 0 && p.connected);
  }

  /** 竞速中还没完成的人数。 */
  racingCount(): number {
    return this.players.filter((p: RacePlayer) => p.connected && p.finishedRank === 0).length;
  }

  open(): void {
    this.transport.connect(new SessionListener(this));
  }

  close(): void {
    this.transport.close();
  }

  consumeNotice(): void {
    this.notice = '';
    this.onChange();
  }

  /** 本机时钟 → 服务器时钟(offset 由 timeSync 校准)。 */
  serverNowMs(): number {
    return Date.now() + this.clockOffsetMs;
  }

  /** 请求一次 NTP 校时(往返时延折半)。 */
  requestTimeSync(): void {
    this.syncSentAt = Date.now();
    this.transport.send(BattleProtocol.encode(BattleProtocol.UP_TIME_SYNC));
  }

  // ---------------- 上行动作 ----------------

  choosePuzzle(level: Level): void {
    const payload: ChoosePuzzlePayload = { level: levelToDto(level) };
    this.transport.send(BattleProtocol.encode(BattleProtocol.UP_CHOOSE_PUZZLE, payload));
  }

  requestStart(): void {
    this.transport.send(BattleProtocol.encode(BattleProtocol.UP_START));
  }

  requestRematch(): void {
    this.transport.send(BattleProtocol.encode(BattleProtocol.UP_REMATCH));
  }

  reportProgress(filled: number, elapsedSeconds: number): void {
    if (this.phase !== BattlePhase.RACING || this.mySubmitted) {
      return;
    }
    const now: number = Date.now();
    if (now - this.lastProgressSent < 1000) {
      return;
    }
    this.lastProgressSent = now;
    const payload: ProgressUpPayload = { filled: filled, elapsedMs: elapsedSeconds * 1000 };
    this.transport.send(BattleProtocol.encode(BattleProtocol.UP_PROGRESS, payload));
  }

  /** 本地解出(Validator 已确认)→ 提交棋盘与本地用时;名次由服务器判定。 */
  localFinish(board: number[][], elapsedSeconds: number): void {
    if (this.mySubmitted) {
      return;
    }
    this.mySubmitted = true;
    const payload: FinishUpPayload = { grid: board, elapsedMs: elapsedSeconds * 1000 };
    this.transport.send(BattleProtocol.encode(BattleProtocol.UP_FINISH, payload));
    this.onChange();
  }

  // ---------------- 下行处理 ----------------

  ingestFrame(raw: string): void {
    this.handleFrame(raw);
  }

  /**
   * 处理一帧。**整段包在 try/catch 里**:服务端理应总是发完整字段,但一旦收到
   * 畸形/半截帧,裸 `as` 强转后的解引用会抛未捕获异常,把 WebSocket 回调链路打断
   * (安卓端靠 `parse<T>() ?: return` + DTO 默认值兜住,鸿蒙这里等价地做两层防护:
   * 先 try/catch 整帧,再对必需字段做校验,校验不过就**忽略这一帧继续跑**)。
   */
  handleFrame(raw: string): void {
    try {
      this.handleFrameInner(raw);
    } catch (e) {
      const err = e as Error;
      Diag.log('BattleSession', `丢弃畸形帧: ${err.message}`);
    }
  }

  private handleFrameInner(raw: string): void {
    const frame: DecodedFrame | null = BattleProtocol.decode(raw);
    if (frame === null) {
      return;
    }
    const type: string = frame.type;
    if (type === BattleProtocol.DOWN_ROOM_STATE) {
      const st: RoomStatePayload = JSON.parse(frame.payloadText) as RoomStatePayload;
      if (st.phase === undefined || st.hostId === undefined) {
        return; // 缺必需字段:忽略该帧,别把 hostId 污染成 undefined
      }
      this.phase = BattleSession.parsePhase(st.phase);
      this.hostId = st.hostId;
      this.players = st.players === undefined ? [] : st.players;
      this.puzzleName = st.puzzleName === undefined ? '' : st.puzzleName;
      this.requestTimeSync();
      if (st.phase === 'lobby') {
        this.raceLevel = null;
        this.serverStartMs = 0;
        this.mySubmitted = false;
        this.resultEntries = [];
        this.myFinalRank = 0;
      }
    } else if (type === BattleProtocol.DOWN_PLAYER_LEFT) {
      const p: PlayerLeftPayload = JSON.parse(frame.payloadText) as PlayerLeftPayload;
      if (p.userId === undefined) {
        return;
      }
      this.players = this.players.filter((it: RacePlayer) => it.userId !== p.userId);
    } else if (type === BattleProtocol.DOWN_RACE_START) {
      const st: RaceStart = JSON.parse(frame.payloadText) as RaceStart;
      if (st.level === undefined || st.level === null) {
        return; // 没有题目就没法开跑
      }
      this.phase = BattlePhase.RACING;
      this.raceLevel = levelFromDto(st.level as LevelDto);
      this.serverStartMs = st.serverStartMs === undefined ? 0 : st.serverStartMs;
      this.mySubmitted = false;
      this.requestTimeSync();
      this.players = this.players.map((it: RacePlayer) => {
        if (it.userId === this.myUserId) {
          return newRacePlayer(it.userId, it.nickname, it.connected, it.ready, it.filled, 0);
        }
        return newRacePlayer(it.userId, it.nickname, it.connected, it.ready, 0, 0);
      });
    } else if (type === BattleProtocol.DOWN_TIME_SYNC) {
      const p: TimeSyncPayload = JSON.parse(frame.payloadText) as TimeSyncPayload;
      if (p.serverTimeMs === undefined) {
        return;
      }
      const rtt: number = Date.now() - this.syncSentAt;
      this.clockOffsetMs = p.serverTimeMs - Date.now() + Math.floor(rtt / 2);
    } else if (type === BattleProtocol.DOWN_PROGRESS) {
      const p: ProgressPayload = JSON.parse(frame.payloadText) as ProgressPayload;
      if (p.userId === undefined || p.filled === undefined) {
        return;
      }
      this.players = this.players.map((it: RacePlayer) => {
        if (it.userId === p.userId) {
          return newRacePlayer(it.userId, it.nickname, it.connected, it.ready, p.filled,
            it.finishedRank);
        }
        return it;
      });
    } else if (type === BattleProtocol.DOWN_FINISHED) {
      const f: FinishPayload = JSON.parse(frame.payloadText) as FinishPayload;
      if (f.userId === undefined || f.rank === undefined) {
        return;
      }
      this.players = this.players.map((it: RacePlayer) => {
        if (it.userId === f.userId) {
          return newRacePlayer(it.userId, it.nickname, it.connected, it.ready, it.filled, f.rank);
        }
        return it;
      });
      if (f.userId === this.myUserId && !this.mySubmitted) {
        this.mySubmitted = true;
      }
    } else if (type === BattleProtocol.DOWN_RESULT) {
      const r: ResultPayload = JSON.parse(frame.payloadText) as ResultPayload;
      if (r.entries === undefined || r.entries === null) {
        return;
      }
      this.resultEntries = r.entries;
      let mine: number = 0;
      for (let i = 0; i < this.resultEntries.length; i++) {
        if (this.resultEntries[i].userId === this.myUserId) {
          mine = this.resultEntries[i].rank;
        }
      }
      this.myFinalRank = mine;
      this.players = this.players.map((p: RacePlayer) => {
        for (let i = 0; i < this.resultEntries.length; i++) {
          if (this.resultEntries[i].userId === p.userId) {
            return newRacePlayer(p.userId, p.nickname, p.connected, p.ready, p.filled,
              this.resultEntries[i].rank);
          }
        }
        return p;
      });
      this.phase = BattlePhase.SETTLED;
    } else if (type === BattleProtocol.DOWN_ERROR) {
      const e: ErrorPayload = JSON.parse(frame.payloadText) as ErrorPayload;
      if (e.message === undefined || e.message === null) {
        return; // 没有 message 就没什么可提示的(以前这里会抛 TypeError)
      }
      this.notice = e.message;
      // 服务端的致命提示(房间解散等)直接作为 fatal 处理
      if (e.message.indexOf('解散') >= 0) {
        this.fatal = e.message;
      }
    }
    this.onChange();
  }

  private static parsePhase(name: string): BattlePhase {
    if (name === 'racing') {
      return BattlePhase.RACING;
    }
    if (name === 'settled') {
      return BattlePhase.SETTLED;
    }
    return BattlePhase.LOBBY;
  }
}

/** 新建一个玩家状态(ArkTS 没有 data class 的 copy,用工厂函数代替)。 */
export function newRacePlayer(userId: number, nickname: string, connected: boolean,
                              ready: boolean, filled: number, finishedRank: number): RacePlayer {
  return {
    userId: userId,
    nickname: nickname,
    connected: connected,
    ready: ready,
    filled: filled,
    finishedRank: finishedRank,
  };
}

/** 传输回调 → 会话(单独一个类,避免在会话里写匿名对象)。 */
class SessionListener implements BattleTransportListener {
  private readonly session: BattleSession;

  constructor(session: BattleSession) {
    this.session = session;
  }

  onFrame(raw: string): void {
    this.session.handleFrame(raw);
  }

  onClosed(reason: string): void {
    if (reason.length > 0 && reason !== 'bye') {
      this.session.fatal = reason;
    }
    this.session.phase = BattlePhase.CLOSED;
    this.session.onChange();
  }
}
