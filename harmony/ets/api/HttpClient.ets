import { http } from '@kit.NetworkKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { ApiErrorBody, ServerInfo } from './Dtos';

/** 统一的接口异常:message 已是可直接展示给用户的中文文案。 */
export class ApiException extends Error {
  readonly code: number;

  constructor(code: number, message: string) {
    super(message);
    this.code = code;
  }

  /** 令牌失效(与服务端 401 对应),调用方据此回登录页。 */
  get unauthorized(): boolean {
    return this.code === 401;
  }
}

/**
 * REST 客户端(对应 Android 版 api/ApiClient.kt)。
 *
 * - baseUrl 与 token 都由外部注入(设置页改地址 / 登录后换 token);
 * - 非 2xx 一律抛 [ApiException],文案优先取服务端下发的
 *   `{"detail":{"error":"…"}}` —— 与安卓端修好之后的解析口径一致;
 * - 网络异常给出可操作的提示(检查网络或服务器地址)。
 */
export class HttpClient {
  private baseUrl: string = '';
  private token: string = '';
  /**
   * 令牌失效(401)时的回调。
   *
   * 与安卓端 api/ApiClient.kt 的 `if (e.code() == 401) AuthManager.logout()` 同口径:
   * 令牌过期/被服务端删除/账号注销时**全局登出**,由导航层回登录页。
   * 不做这件事会停在"假登录":界面显示已登录,但所有在线功能静默失败。
   *
   * 用回调而不是直接 import AuthManager —— 避免 api ↔ auth 形成循环依赖。
   * 由 AuthManager.init() 注入。
   */
  private onUnauthorized: (() => void) | null = null;

  /** 注入"收到 401 时做什么"(见 AuthManager.init)。 */
  setUnauthorizedHandler(handler: () => void): void {
    this.onUnauthorized = handler;
  }

  setBaseUrl(url: string): void {
    // 与 LocalSettings.normalizeServerUrl 保持**同一规则**(缺协议补 http://、补尾斜杠)。
    // 这里不 import LocalSettings 是为了避免 data ↔ api 反向依赖;
    // 三处口径由单元测试 ApiAuth.test.ets 的 normalize_* 用例锁定。
    let normalized: string = (url ?? '').trim();
    if (normalized.length > 0) {
      if (!normalized.startsWith('http://') && !normalized.startsWith('https://')) {
        normalized = `http://${normalized}`;
      }
      if (!normalized.endsWith('/')) {
        normalized += '/';
      }
    }
    this.baseUrl = normalized;
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  setToken(token: string): void {
    this.token = token ?? '';
  }

  /** 服务器主机名(去掉协议与路径),设置页展示用。 */
  serverHost(): string {
    let raw: string = this.baseUrl;
    if (raw.startsWith('https://')) {
      raw = raw.substring(8);
    } else if (raw.startsWith('http://')) {
      raw = raw.substring(7);
    }
    while (raw.endsWith('/')) {
      raw = raw.substring(0, raw.length - 1);
    }
    return raw;
  }

  async get<T>(path: string): Promise<T> {
    return this.send<T>(http.RequestMethod.GET, path, null);
  }

  async post<T>(path: string, body: Object | null): Promise<T> {
    const payload: string | null = body === null ? null : JSON.stringify(body);
    return this.send<T>(http.RequestMethod.POST, path, payload);
  }

  /**
   * 连通性自检:请求服务端根路径(公开、无鉴权、极轻),用来看"地址填对没有"。
   * 成功返回一句可展示的说明;失败抛 ApiException(文案可直接展示)。
   */
  async ping(): Promise<string> {
    const info: ServerInfo = await this.get<ServerInfo>('');
    const name: string = info.name === undefined ? '服务端' : info.name;
    const smtp: string = info.smtp === true ? '邮件发送已配置' : '未配 SMTP(验证码走演示模式)';
    return `${name} 在线 · ${smtp}`;
  }

  private async send<T>(method: http.RequestMethod, path: string, payload: string | null): Promise<T> {
    if (this.baseUrl.length === 0) {
      throw new ApiException(-1, '还没有设置服务器地址,请到设置页填写');
    }
    const request: http.HttpRequest = http.createHttp();
    try {
      const header: Record<string, string> = { 'Content-Type': 'application/json' };
      if (this.token.length > 0) {
        header['Authorization'] = `Bearer ${this.token}`;
      }
      const options: http.HttpRequestOptions = {
        method: method,
        header: header,
        connectTimeout: 8000,
        readTimeout: 15000,
      };
      if (payload !== null) {
        options.extraData = payload;
      }
      const response: http.HttpResponse = await request.request(this.baseUrl + path, options);
      const code: number = response.responseCode as number;
      const text: string = typeof response.result === 'string' ? response.result as string : '';
      if (code >= 200 && code < 300) {
        if (text.length === 0) {
          // 空响应(204 等):ArkTS 不允许 `{}` 这种无类型对象字面量,用 JSON.parse 兜底
          return JSON.parse('null') as T;
        }
        return JSON.parse(text) as T;
      }
      if (code === 401) {
        // 令牌已失效:全局登出(导航层监听 loggedIn 会回登录页)。
        // 与安卓端 ApiClient.safe() 的处理一致 —— 否则会停在"假登录"。
        const handler = this.onUnauthorized;
        if (handler !== null) {
          handler();
        }
      }
      throw new ApiException(code, HttpClient.errorMessage(code, text));
    } catch (e) {
      if (e instanceof ApiException) {
        throw e;
      }
      const err = e as BusinessError;
      const detail: string = err !== null && err.message !== undefined ? err.message : '网络异常';
      // 把"正在连哪个地址"直接摆出来:填错地址是新手最常见的失败原因
      throw new ApiException(-1,
        `无法连接服务器 ${this.serverHost()}(${detail})。请确认服务端已启动、手机/模拟器与电脑同一局域网,并在设置页核对地址`);
    } finally {
      request.destroy();
    }
  }

  /** 服务端错误体 → 可展示中文。优先服务端的 {"detail":{"error":…}},否则按状态码兜底。 */
  private static errorMessage(code: number, body: string): string {
    if (body.length > 0) {
      try {
        const parsed: ApiErrorBody = JSON.parse(body) as ApiErrorBody;
        if (parsed.error !== undefined && parsed.error.length > 0) {
          return parsed.error;
        }
        if (parsed.detail !== undefined && parsed.detail !== null
          && parsed.detail.error !== undefined && parsed.detail.error.length > 0) {
          return parsed.detail.error;
        }
      } catch (e) {
        // 服务端返回的不是 JSON(网关 502/HTML 等):走下面的兜底文案
      }
    }
    if (code === 401) {
      return '登录已过期,请重新登录';
    }
    if (code === 404) {
      return '请求的资源不存在';
    }
    if (code === 408 || code === 502 || code === 503 || code === 504) {
      return '服务器暂时不可用,请稍后重试';
    }
    return `请求失败(${code})`;
  }
}
