/**
 * 服务端契约 DTO(对应 Android 版 api/dto/Dtos.kt)。
 *
 * ArkTS 没有 kotlinx-serialization,这里用 interface + JSON.parse 的 `as` 转换:
 * 字段名必须与服务端 JSON 完全一致(见 server/ 各 router 的返回体)。
 *
 * ⚠️ id / puzzleId 一律用 number 承载。Android 端是 Kotlin Int(32 位),
 * 服务端下发前必须保证在 Int32 范围内(否则安卓端解析溢出 —— 曾经把"每日一题"打成过"离线")。
 */

// ---------------- 谜题 ----------------

export interface LevelDto {
  id: number;
  name: string;
  difficulty: string;
  rows: number;
  cols: number;
  clueGrid: number[][];
  answerGrid: number[][];
  timedLimitSeconds?: number;
  solves?: number;
}

export interface DailyLevelResponse {
  date: string;
  difficulty: string;
  level: LevelDto;
}

export interface PuzzleMeta {
  id: number;
  name: string;
  difficulty: string;
  rows: number;
  cols: number;
  timedLimitSeconds?: number;
  solves?: number;
}

export interface BankResponse {
  total: number;
  items: PuzzleMeta[];
}

// ---------------- 认证 ----------------

export interface SendCodeRequest {
  email: string;
}

export interface SendCodeResponse {
  email: string;
  expiresInSeconds: number;
  resendAfterSeconds: number;
  /** true = SMTP 真发成功;false = 回退控制台(或未配置发信) */
  delivered: boolean;
  /** 仅服务端显式开启 XIANGSU_ECHO_CODE=1 时才回显(演示用) */
  devCode?: string;
}

export interface LoginRequest {
  email: string;
  code: string;
  nickname?: string;
}

export interface LoginResponse {
  userId: number;
  token: string;
  nickname: string;
  isNew: boolean;
}

export interface MeResponse {
  userId: number;
  nickname: string;
}

export interface NicknameRequest {
  nickname: string;
}

export interface NicknameResponse {
  nickname: string;
}

// ---------------- 排行榜 ----------------

export interface SubmitRecordRequest {
  puzzleId: number;
  durationMs: number;
  source: string;
}

export interface SubmitRecordResponse {
  recordId: number;
  /** 只有更快时才真正写入(服务端只保留最优成绩) */
  improved?: boolean;
}

export interface BoardEntry {
  rank: number;
  userId: number;
  nickname: string;
  durationMs: number;
}

export interface MyBest {
  rank: number;
  durationMs: number;
}

export interface LeaderboardResponse {
  puzzleId: number;
  entries: BoardEntry[];
  myBest?: MyBest;
}

export interface PlayedPuzzle {
  puzzleId: number;
  name?: string;
  difficulty?: string;
  /** "online" = 在线关(负 id);"builtin" = 内置关(正 id) */
  source: string;
  plays: number;
  lastAt?: string;
}

export interface MyPuzzlesResponse {
  items: PlayedPuzzle[];
}

// ---------------- 对战房间 ----------------

export interface CreateRoomResponse {
  roomId: string;
}

// ---------------- 连通性自检 ----------------

/** 服务端根路径 `/` 的响应:公开、无鉴权,用来验证"服务器地址填对没有"。 */
export interface ServerInfo {
  name?: string;
  message?: string;
  /** 服务端是否配好了 SMTP(没配则验证码走演示模式)。 */
  smtp?: boolean;
}

// ---------------- 错误体 ----------------

/** FastAPI 的 detail 既可能是对象 {"error": "..."},也可能是字符串或数组(422)。 */
export interface ApiErrorDetail {
  error?: string;
}

export interface ApiErrorBody {
  error?: string;
  detail?: ApiErrorDetail;
}
