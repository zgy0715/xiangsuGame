import { preferences } from '@kit.ArkData';
import { common } from '@kit.AbilityKit';
import { ApiException, HttpClient } from '../api/HttpClient';
import { GameApi } from '../api/GameApi';
import { LoginResponse, SendCodeResponse } from '../api/Dtos';
import { LoginRules } from './LoginRules';
import { LocalSettings } from '../data/LocalSettings';

/** 会话(对应 Android 版 AuthManager.Session)。 */
export class Session {
  readonly userId: number;
  readonly token: string;
  readonly nickname: string;
  /** 登录邮箱;游客会话为空串(离线身份)。 */
  readonly email: string;

  constructor(userId: number, token: string, nickname: string, email: string) {
    this.userId = userId;
    this.token = token;
    this.nickname = nickname;
    this.email = email;
  }
}

/**
 * 会话管理(对应 Android 版 auth/AuthManager.kt)。
 *
 * 登录:邮箱 + 6 位验证码 → 服务端签发 Bearer token(30 天);
 * 会话持久化在 preferences("xiangsu_auth"),**冷启动不自动进入游戏** ——
 * 登录页会显示「继续上次登录」,用户点了才进(与安卓端一致)。
 *
 * UI 绑定:会话变化时同步写 AppStorage('loggedIn'/'userId'/'nickname'/'email'),
 * 页面用 @StorageProp 直接读。
 */
export class AuthManager {

  /** 游客会话的本机固定 userId:负值,不会与服务端分配的真实 id 冲突。 */
  static readonly GUEST_USER_ID: number = -99;
  static readonly KEY_LOGGED_IN: string = 'loggedIn';
  static readonly KEY_USER_ID: string = 'userId';
  static readonly KEY_NICKNAME: string = 'nickname';
  static readonly KEY_EMAIL: string = 'email';
  static readonly KEY_LAST_EMAIL: string = 'lastEmail';

  private static store: preferences.Preferences | null = null;
  private static session: Session | null = null;
  private static lastEmailValue: string = '';
  private static http: HttpClient = new HttpClient();
  private static apiInstance: GameApi = new GameApi(AuthManager.http);
  /** 首次登录(服务端 isNew)后需要引导设置昵称,由导航层消费一次。 */
  private static newUserFlag: boolean = false;

  /** 由 EntryAbility.onCreate 调用:读设置、恢复会话、把 token 注入 HttpClient。 */
  static async init(context: common.Context): Promise<void> {
    AuthManager.store = await preferences.getPreferences(context, { name: 'xiangsu_auth' });
    AuthManager.lastEmailValue = await AuthManager.readString(K_LAST_EMAIL, '');
    AppStorage.setOrCreate<string>(AuthManager.KEY_LAST_EMAIL, AuthManager.lastEmailValue);
    await AuthManager.restore();
    // baseUrl 跟随设置(设置页改动后由 AppGraph 重新注入)
    AuthManager.http.setBaseUrl(LocalSettings.serverUrl());
    // 任何接口收到 401 都全局登出(与安卓端 api/ApiClient.kt 的 safe() 同口径),
    // 只清本地会话、不发注销请求,避免"401 → 注销 → 又 401"递归。
    AuthManager.http.setUnauthorizedHandler(() => {
      // 只有本来持有令牌的会话才登出:游客 token 为空,401 不能当"令牌失效"处理
      if (AuthManager.hasToken) {
        AuthManager.logout();
      }
    });
  }

  static get isLoggedIn(): boolean {
    return AuthManager.session !== null;
  }

  /** 是否为离线游客(无 token,只玩内置关,不联网)。 */
  static get isGuest(): boolean {
    return AuthManager.session !== null && AuthManager.session.userId === AuthManager.GUEST_USER_ID;
  }

  /**
   * 当前是否持有服务端令牌(游客为 false)。
   *
   * 401 处理必须看它:**游客本来就没有 token**,访问需鉴权接口同样会拿到 401;
   * 若无条件全局登出,游客点一下对战/排行榜就被踢回登录页(两端都踩过)。
   */
  static get hasToken(): boolean {
    const s: Session | null = AuthManager.session;
    return s !== null && s.token.length > 0;
  }

  static get current(): Session | null {
    return AuthManager.session;
  }

  static get lastEmail(): string {
    return AuthManager.lastEmailValue;
  }

  static nickname(): string {
    const s: Session | null = AuthManager.session;
    return s === null ? '' : s.nickname;
  }

  static token(): string {
    const s: Session | null = AuthManager.session;
    return s === null ? '' : s.token;
  }

  /** 设置页改了服务器地址后调用。 */
  static refreshBaseUrl(): void {
    AuthManager.http.setBaseUrl(LocalSettings.serverUrl());
  }

  /** 当前服务器主机名(登录页/设置页展示用)。 */
  static serverHost(): string {
    return AuthManager.http.serverHost();
  }

  /**
   * 连通性自检(登录页「测试连接」):按【当前输入框里的地址】试一次根路径。
   * 成功返回可展示的说明文案,失败抛 ApiException(由调用方展示)。
   * 不写设置、不改会话 —— 让用户先确认地址对了再登录。
   */
  static async testConnection(url: string): Promise<string> {
    if ((url ?? '').trim().length === 0) {
      throw new ApiException(-1, '请先填写服务器地址,例如 http://192.168.1.5:8000/');
    }
    // 与「保存」走同一个归一化规则(否则会出现"测试通过、保存后全失败")
    const normalized: string = LocalSettings.normalizeServerUrl(url);
    const probe: HttpClient = new HttpClient();
    probe.setBaseUrl(normalized);
    return probe.ping();
  }

  /**
   * 供其他数据仓库(题库/排行榜/对战)复用的接口实例。
   * 取用时同步一次 baseUrl 与 token —— 设置页改了地址、或刚登录换了令牌,下次调用即生效。
   */
  static api(): GameApi {
    AuthManager.http.setBaseUrl(LocalSettings.serverUrl());
    AuthManager.http.setToken(AuthManager.token());
    return AuthManager.apiInstance;
  }

  /** 冷启动静默恢复:读上次持久化的会话(真实账号或游客),但**不**自动进入游戏。 */
  private static async restore(): Promise<void> {
    const userId: number = await AuthManager.readNumber(K_USER_ID, -1);
    if (userId === AuthManager.GUEST_USER_ID) {
      AuthManager.applySession(new Session(AuthManager.GUEST_USER_ID, '', GUEST_NICKNAME, ''));
      return;
    }
    const token: string = await AuthManager.readString(K_TOKEN, '');
    const nickname: string = await AuthManager.readString(K_NICKNAME, '');
    if (userId >= 0 && token.length > 0) {
      AuthManager.applySession(new Session(userId, token,
        nickname.length > 0 ? nickname : `玩家${userId}`, AuthManager.lastEmailValue));
    }
  }

  /** 游客进入(免登录/离线):不请求服务器,直接以游客身份玩内置关卡。 */
  static async enterGuest(): Promise<void> {
    await AuthManager.persist(new Session(AuthManager.GUEST_USER_ID, '', GUEST_NICKNAME, ''));
  }

  // ---------------- 邮箱验证码登录 ----------------

  /** 第一步:请求向邮箱发送 6 位验证码。失败文案由服务端下发,可直接展示。 */
  static async requestEmailCode(email: string): Promise<SendCodeResponse> {
    const invalid: string | null = LoginRules.validateEmail(email);
    if (invalid !== null) {
      throw new ApiException(-1, invalid);
    }
    const normalized: string = LoginRules.normalizeEmail(email);
    const resp: SendCodeResponse = await AuthManager.apiInstance.sendCode(normalized);
    AuthManager.lastEmailValue = resp.email;
    AppStorage.setOrCreate<string>(AuthManager.KEY_LAST_EMAIL, resp.email);
    await AuthManager.write(K_LAST_EMAIL, resp.email);
    return resp;
  }

  /** 第二步:提交验证码登录。返回 isNew(首次注册需要引导设置昵称)。 */
  static async loginWithEmailCode(email: string, code: string): Promise<boolean> {
    const invalid: string | null = LoginRules.validateEmail(email);
    if (invalid !== null) {
      throw new ApiException(-1, invalid);
    }
    const normalized: string = LoginRules.normalizeEmail(email);
    const trimmed: string = code.trim();
    if (!LoginRules.canSubmit(trimmed)) {
      throw new ApiException(-1, '请输入 6 位数字验证码');
    }
    const resp: LoginResponse = await AuthManager.apiInstance.login(normalized, trimmed);
    AuthManager.lastEmailValue = normalized;
    AppStorage.setOrCreate<string>(AuthManager.KEY_LAST_EMAIL, normalized);
    await AuthManager.write(K_LAST_EMAIL, normalized);
    await AuthManager.persist(new Session(resp.userId, resp.token, resp.nickname, normalized));
    AuthManager.newUserFlag = resp.isNew;
    return resp.isNew;
  }

  /** 取用"是否为新账号"标记(读后即清),避免老用户每次登录都被弹窗打扰。 */
  static consumeNewUserFlag(): boolean {
    const flag: boolean = AuthManager.newUserFlag;
    AuthManager.newUserFlag = false;
    return flag;
  }

  /** 修改昵称(服务端持久化)后同步本地会话。 */
  static async changeNickname(newName: string): Promise<string> {
    if (AuthManager.isGuest) {
      throw new ApiException(-1, '游客为离线身份,登录后即可修改昵称');
    }
    const resp = await AuthManager.apiInstance.updateNickname(newName);
    await AuthManager.updateNicknameLocal(resp.nickname);
    return resp.nickname;
  }

  private static async updateNicknameLocal(nickname: string): Promise<void> {
    const s: Session | null = AuthManager.session;
    if (s !== null) {
      AuthManager.applySession(new Session(s.userId, s.token, nickname, s.email));
    }
    await AuthManager.write(K_NICKNAME, nickname);
  }

  /**
   * 校验本地续登的会话是否仍然有效(登录页点「继续」时调用)。
   * 网络不通时返回 true(离线容忍);令牌确实失效(401)则清会话并返回 false。
   */
  static async verifyRestoredSession(): Promise<boolean> {
    const s: Session | null = AuthManager.session;
    if (s === null) {
      return false;
    }
    if (s.userId === AuthManager.GUEST_USER_ID) {
      return true; // 游客是纯离线身份,无需校验
    }
    try {
      await AuthManager.apiInstance.me();
      return true;
    } catch (e) {
      const err = e as ApiException;
      if (err instanceof ApiException && err.unauthorized) {
        await AuthManager.logout();
        return false;
      }
      return true; // 网络问题不阻断进入
    }
  }

  /** 只清本地会话(401 自动登出走它,避免"401 → 请求注销 → 又 401"递归)。 */
  static async logout(): Promise<void> {
    await AuthManager.applySession(null);
    AuthManager.newUserFlag = false;
    await AuthManager.remove(K_USER_ID);
    await AuthManager.remove(K_TOKEN);
    await AuthManager.remove(K_NICKNAME);
  }

  /** 用户主动「退出登录」:先请服务端作废令牌,再清本地(离线也照常退出)。 */
  static async logoutAndRevoke(): Promise<boolean> {
    const s: Session | null = AuthManager.session;
    const shouldRevoke: boolean = s !== null && s.userId !== AuthManager.GUEST_USER_ID
      && s.token.length > 0;
    let revoked: boolean = false;
    if (shouldRevoke) {
      try {
        await AuthManager.apiInstance.logout();
        revoked = true;
      } catch (e) {
        revoked = false;
      }
    }
    await AuthManager.logout();
    return revoked;
  }

  // ---------------- 内部 ----------------

  private static async persist(s: Session): Promise<void> {
    await AuthManager.applySession(s);
    await AuthManager.write(K_USER_ID, s.userId);
    await AuthManager.write(K_TOKEN, s.token);
    await AuthManager.write(K_NICKNAME, s.nickname);
  }

  private static async applySession(s: Session | null): Promise<void> {
    AuthManager.session = s;
    AuthManager.http.setToken(s === null ? '' : s.token);
    AppStorage.setOrCreate<boolean>(AuthManager.KEY_LOGGED_IN, s !== null);
    AppStorage.setOrCreate<number>(AuthManager.KEY_USER_ID, s === null ? -1 : s.userId);
    AppStorage.setOrCreate<string>(AuthManager.KEY_NICKNAME, s === null ? '' : s.nickname);
    AppStorage.setOrCreate<string>(AuthManager.KEY_EMAIL, s === null ? '' : s.email);
  }

  private static async readString(key: string, def: string): Promise<string> {
    const store: preferences.Preferences | null = AuthManager.store;
    if (store === null) {
      return def;
    }
    const value: preferences.ValueType = await store.get(key, def);
    return typeof value === 'string' ? value as string : def;
  }

  private static async readNumber(key: string, def: number): Promise<number> {
    const store: preferences.Preferences | null = AuthManager.store;
    if (store === null) {
      return def;
    }
    const value: preferences.ValueType = await store.get(key, def);
    return typeof value === 'number' ? value as number : def;
  }

  private static async write(key: string, value: preferences.ValueType): Promise<void> {
    const store: preferences.Preferences | null = AuthManager.store;
    if (store === null) {
      return;
    }
    await store.put(key, value);
    await store.flush();
  }

  private static async remove(key: string): Promise<void> {
    const store: preferences.Preferences | null = AuthManager.store;
    if (store === null) {
      return;
    }
    await store.delete(key);
    await store.flush();
  }
}

const GUEST_NICKNAME: string = '游客';
const K_USER_ID: string = 'user_id';
const K_TOKEN: string = 'token';
const K_NICKNAME: string = 'nickname';
const K_LAST_EMAIL: string = 'last_email';
