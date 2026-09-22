import { HttpClient } from './HttpClient';
import { BankResponse, CreateRoomResponse, DailyLevelResponse, LeaderboardResponse, LevelDto,
  LoginRequest, LoginResponse, MeResponse, MyPuzzlesResponse, NicknameRequest, NicknameResponse,
  SendCodeRequest, SendCodeResponse, SubmitRecordRequest, SubmitRecordResponse } from './Dtos';

/**
 * 服务端接口(对应 Android 版 api/GameApi.kt 与 server/ 各 router 一一对应)。
 *
 * 路径、方法、字段都保持与安卓端完全一致 —— 服务端零改动,
 * 鸿蒙端只是换了一个会说同一套协议的客户端。
 */
export class GameApi {
  private readonly http: HttpClient;

  constructor(http: HttpClient) {
    this.http = http;
  }

  // ---- 认证(邮箱 → 6 位验证码 → Bearer token)----
  async sendCode(email: string): Promise<SendCodeResponse> {
    const body: SendCodeRequest = { email: email };
    return this.http.post<SendCodeResponse>('api/auth/send-code', body);
  }

  async login(email: string, code: string, nickname?: string): Promise<LoginResponse> {
    const body: LoginRequest = { email: email, code: code, nickname: nickname };
    return this.http.post<LoginResponse>('api/auth/login', body);
  }

  async me(): Promise<MeResponse> {
    return this.http.get<MeResponse>('api/auth/me');
  }

  async updateNickname(nickname: string): Promise<NicknameResponse> {
    const body: NicknameRequest = { nickname: nickname };
    return this.http.post<NicknameResponse>('api/auth/nickname', body);
  }

  /** 让服务端作废当前令牌(用户主动退出登录时调用)。 */
  async logout(): Promise<void> {
    await this.http.post<Object>('api/auth/logout', null);
  }

  // ---- 谜题 ----
  async bank(difficulty: string | null, offset: number, limit: number): Promise<BankResponse> {
    let path: string = `api/puzzles/bank?offset=${offset}&limit=${limit}`;
    if (difficulty !== null && difficulty.length > 0) {
      path += `&difficulty=${encodeURIComponent(difficulty)}`;
    }
    return this.http.get<BankResponse>(path);
  }

  /** 每日一题;date 传 null 表示由服务端按东八区决定"今天"(推荐,保证全服同题)。 */
  async daily(date: string | null): Promise<DailyLevelResponse> {
    const path: string = date === null || date.length === 0
      ? 'api/puzzles/daily'
      : `api/puzzles/daily?date=${encodeURIComponent(date)}`;
    return this.http.get<DailyLevelResponse>(path);
  }

  async puzzle(id: number): Promise<LevelDto> {
    return this.http.get<LevelDto>(`api/puzzles/${id}`);
  }

  // ---- 对战房间 ----
  async createRoom(): Promise<CreateRoomResponse> {
    return this.http.post<CreateRoomResponse>('api/rooms', null);
  }

  // ---- 排行榜 ----
  async submitRecord(puzzleId: number, durationMs: number, source: string): Promise<SubmitRecordResponse> {
    const body: SubmitRecordRequest = {
      puzzleId: puzzleId,
      durationMs: durationMs,
      source: source,
    };
    return this.http.post<SubmitRecordResponse>('api/leaderboard/submit', body);
  }

  async leaderboard(puzzleId: number, source: string | null, limit: number): Promise<LeaderboardResponse> {
    let path: string = `api/leaderboard?puzzleId=${puzzleId}&limit=${limit}`;
    if (source !== null && source.length > 0) {
      path += `&source=${encodeURIComponent(source)}`;
    }
    return this.http.get<LeaderboardResponse>(path);
  }

  async myPuzzles(limit: number): Promise<MyPuzzlesResponse> {
    return this.http.get<MyPuzzlesResponse>(`api/leaderboard/my-puzzles?limit=${limit}`);
  }
}
