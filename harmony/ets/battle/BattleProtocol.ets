/**
 * 对战帧协议(对应 Android 版 battle/BattleProtocol.kt)。
 *
 * 帧 = {"type": "...", "payload": {...}} —— 与服务端 server/rooms.py 完全一致,
 * 鸿蒙端只是"换了一个会说同一套协议的客户端",服务端零改动。
 *
 * 上行(客户端→服务器):auth(首帧鉴权)/ start / choosePuzzle / progress / finish /
 *                     ping / rematch / timeSync
 * 下行(服务器→客户端):roomState / playerLeft / raceStart / progress / finished /
 *                     result / error / timeSync
 */
export class BattleProtocol {
  // ---- 上行 ----
  static readonly UP_AUTH: string = 'auth';
  static readonly UP_START: string = 'start';
  static readonly UP_CHOOSE_PUZZLE: string = 'choosePuzzle';
  static readonly UP_PROGRESS: string = 'progress';
  static readonly UP_FINISH: string = 'finish';
  static readonly UP_PING: string = 'ping';
  static readonly UP_REMATCH: string = 'rematch';
  static readonly UP_TIME_SYNC: string = 'timeSync';

  // ---- 下行 ----
  static readonly DOWN_ROOM_STATE: string = 'roomState';
  static readonly DOWN_PLAYER_LEFT: string = 'playerLeft';
  static readonly DOWN_RACE_START: string = 'raceStart';
  static readonly DOWN_PROGRESS: string = 'progress';
  static readonly DOWN_FINISHED: string = 'finished';
  static readonly DOWN_RESULT: string = 'result';
  static readonly DOWN_ERROR: string = 'error';
  static readonly DOWN_TIME_SYNC: string = 'timeSync';

  /** 编码一帧(payload 省略时为空对象)。 */
  static encode(type: string, payload: Object | null = null): string {
    return JSON.stringify(new OutFrame(type, payload === null ? new EmptyPayload() : payload));
  }

  /** 解码一帧:拿到 type 与 payload 的 JSON 文本(各负载再按类型解析)。 */
  static decode(raw: string): DecodedFrame | null {
    try {
      const frame: RawFrame = JSON.parse(raw) as RawFrame;
      const type: string = frame.type === undefined ? '' : frame.type;
      if (type.length === 0) {
        return null;
      }
      const payloadText: string = frame.payload === undefined || frame.payload === null
        ? '{}'
        : JSON.stringify(frame.payload);
      return new DecodedFrame(type, payloadText);
    } catch (e) {
      return null;
    }
  }
}

/** 出站帧(Payload 作为字段参与序列化)。 */
class OutFrame {
  readonly type: string;
  readonly payload: Object;

  constructor(type: string, payload: Object) {
    this.type = type;
    this.payload = payload;
  }
}

class EmptyPayload {
}

interface RawFrame {
  type?: string;
  payload?: Object;
}

/** 解码结果:type + payload 原始 JSON(便于按类型强转)。 */
export class DecodedFrame {
  readonly type: string;
  readonly payloadText: string;

  constructor(type: string, payloadText: string) {
    this.type = type;
    this.payloadText = payloadText;
  }
}

// ---------------- 负载类型(字段名与服务端 JSON 一一对应) ----------------

/** 首帧鉴权(token 不放 URL,避免进服务端访问日志)。 */
export interface AuthPayload {
  token: string;
}

/** 房内玩家状态。 */
export interface RacePlayer {
  userId: number;
  nickname: string;
  connected: boolean;
  ready: boolean;
  filled: number;
  finishedRank: number;
}

/** 房间状态(phase: lobby / racing / settled)。 */
export interface RoomStatePayload {
  roomId: string;
  hostId: number;
  phase: string;
  players: RacePlayer[];
  puzzleName?: string;
}

/** 房主选题(把整关 JSON 交给服务器做校验基准)。 */
export interface ChoosePuzzlePayload {
  level: Object;
}

/** 发车:服务器权威起跑时刻 + 完整题目。 */
export interface RaceStartPayload {
  serverStartMs: number;
  level: Object;
}

/** 上行进度。 */
export interface ProgressUpPayload {
  filled: number;
  elapsedMs: number;
}

/** 下行进度。 */
export interface ProgressPayload {
  userId: number;
  filled: number;
  elapsedMs: number;
}

/** 上行完成(棋盘 + 本地用时;名次由服务器判定)。 */
export interface FinishUpPayload {
  grid: number[][];
  elapsedMs: number;
}

/** 下行完成(名次与服务器计时)。 */
export interface FinishPayload {
  userId: number;
  rank: number;
  durationMs: number;
}

/** 结算条目。 */
export interface ResultEntry {
  userId: number;
  nickname: string;
  rank: number;
  durationMs: number;
}

export interface ResultPayload {
  entries: ResultEntry[];
}

export interface PlayerLeftPayload {
  userId: number;
}

export interface ErrorPayload {
  message: string;
}

export interface TimeSyncPayload {
  serverTimeMs: number;
}
