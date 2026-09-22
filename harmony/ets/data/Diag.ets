import { common } from '@kit.AbilityKit';
import { fileIo } from '@kit.CoreFileKit';

/**
 * 极简诊断日志(临时用):把关键动作追加写到应用沙箱里的 xiangsu-diag.log。
 *
 * 用途:定位"点了按钮没反应"这类问题 —— 没有办法打断点的环境里,
 * 让程序自己把"事件到没到、状态是什么、下一步走了没"记下来最靠谱。
 *
 * 注意:fileIo.openSync 的 flag 'a' 在部分版本不存在,这里用 READ_WRITE|CREATE
 * 打开后 seek 到末尾再写,兼容性最好。
 */
export class Diag {
  private static ctx: common.Context | null = null;
  private static path: string = '';
  private static enabled: boolean = true;

  static init(ctx: common.Context): void {
    try {
      Diag.ctx = ctx;
      Diag.path = `${ctx.filesDir}/xiangsu-diag.log`;
    } catch (e) {
      Diag.enabled = false;
    }
  }

  /** 写一行日志(不用 await,失败静默 —— 诊断绝不能影响主流程)。 */
  static log(tag: string, detail: string): void {
    if (!Diag.enabled || Diag.path.length === 0) {
      return;
    }
    const line: string = `${new Date().toISOString()} [${tag}] ${detail}\n`;
    try {
      const file = fileIo.openSync(Diag.path,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE);
      try {
        const size: number = fileIo.statSync(file.fd).size;
        // ⚠ length 必须是**字节数**而不是字符数:日志里一旦出现中文,
        //   用 line.length 会把写入截断在多字节字符中间,日志变成乱码。
        //   这里手算 UTF-8 字节数,避免依赖 util.TextEncoder(不同 API 版本行为不一)。
        const bytes: number = Diag.utf8Length(line);
        fileIo.writeSync(file.fd, line, { offset: size, length: bytes });
      } finally {
        fileIo.closeSync(file);
      }
    } catch (e) {
      // 写不进去就算了(比如目录不可写)
    }
  }

  /** 日志文件路径(供排查时读取)。 */
  static logPath(): string {
    return Diag.path;
  }

  /**
   * 字符串的 UTF-8 字节长度。
   * 逐字符判断:> 0xFFFF 是补充平面(4 字节,代理对两个 char 合起来算一次);
   * > 0x7FF 三字节;> 0x7F 两字节;其余一字节。
   */
  private static utf8Length(text: string): number {
    let bytes: number = 0;
    for (let i = 0; i < text.length; i++) {
      const code: number = text.charCodeAt(i);
      if (code > 0xFFFF) {
        bytes += 4;
        i++; // 跳过代理对的低位
      } else if (code > 0x7FF) {
        bytes += 3;
      } else if (code > 0x7F) {
        bytes += 2;
      } else {
        bytes += 1;
      }
    }
    return bytes;
  }

  /** 清空日志(每次进新关卡时调用,免得越写越大)。 */
  static reset(): void {
    if (!Diag.enabled || Diag.path.length === 0) {
      return;
    }
    try {
      const file = fileIo.openSync(Diag.path,
        fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
      fileIo.closeSync(file);
    } catch (e) {
      // 忽略
    }
  }
}
