// 活动请求边界：封装身份代次、重复点击合并和未知写语义，页面不能自行加币或重试写请求。
import type {ActivityAction, ActivityHost, HostSnapshot, NativeReply} from '../host/ActivityHost';

/** 受控请求错误；uncertain 表示服务器可能已经提交，必须查询原实例再决定同键重试。 */
export class ActivityRequestError extends Error {
  constructor(public readonly code: string, public readonly uncertain = false, public readonly status = 0) {
    super(code);
  }
}

/** 宿主由平台注入；bind 输入原生快照，不自行推断账号或令牌。 */
export class ActivityClient {
  private snapshot: HostSnapshot = {generation: 0, authenticated: false, focused: false};
  private readonly pending = new Map<string, Promise<unknown>>();
  constructor(private readonly host: ActivityHost) {}
  /** 会话事件覆盖当前代次；页面在代次变化时清空私有数据。 */
  bind(snapshot: HostSnapshot): void {
    if (snapshot.generation !== this.snapshot.generation) this.pending.clear();
    this.snapshot = snapshot;
  }
  /** 相同代次/动作/载荷合并正在执行的请求；完成后删除，仅调用方显式操作才会再次派发。 */
  request<T>(action: ActivityAction, payload: Record<string, unknown> = {}): Promise<T> {
    const generation = this.snapshot.generation;
    if (generation <= 0) return Promise.reject(new ActivityRequestError('SESSION_NOT_READY'));
    // 排序避免字段插入顺序导致双击绕过合并；原始业务值和幂等键保持不变。
    const serialized = JSON.stringify(Object.fromEntries(Object.keys(payload).sort().map(key => [key, payload[key]])));
    const key = `${generation}:${action}:${serialized}`;
    const existing = this.pending.get(key);
    if (existing) return existing as Promise<T>;
    const pending = this.execute<T>(action, serialized, generation).finally(() => { this.pending.delete(key); });
    this.pending.set(key, pending);
    return pending;
  }

  /** 一次派发，回包后再次核对身份；传输失败的写操作只返回待核查，绝不自动重放。 */
  private async execute<T>(action: ActivityAction, payload: string, generation: number): Promise<T> {
    const writing = action === 'accept' || action === 'claim';
    let result: NativeReply;
    try {
      result = await this.host.request(action, payload, generation);
    } catch (error) {
      // 原生桥明确拒绝的参数/身份错误没有写副作用；其他异常无法证明服务端未提交。
      const code = (error as {code?: string})?.code;
      if (generation !== this.snapshot.generation || code === 'SESSION_CHANGED') throw new ActivityRequestError('SESSION_CHANGED');
      if (code === 'INVALID_REQUEST') throw new ActivityRequestError('INVALID_REQUEST');
      throw new ActivityRequestError(writing ? 'RESULT_UNKNOWN' : 'NETWORK_ERROR', writing);
    }
    if (generation !== this.snapshot.generation) throw new ActivityRequestError('SESSION_CHANGED');
    if (!result.ok) {
      if (writing && (result.status === 0 || result.status >= 500)) throw new ActivityRequestError('RESULT_UNKNOWN', true, result.status);
      let code = result.status === 401 ? 'LOGIN_REQUIRED' : result.status === 0 ? 'NETWORK_ERROR' : 'REQUEST_FAILED';
      // 后端稳定原因码只作为枚举输入；UI 自行映射中文，不直接展示服务端异常正文。
      try { const reason = JSON.parse(result.body ?? '{}').reasonCode; if (typeof reason === 'string' && /^[A-Z_]{1,64}$/.test(reason)) code = reason; } catch { /* 非 JSON 错误保留 HTTP 通用提示。 */ }
      throw new ActivityRequestError(code, false, result.status);
    }
    try {
      if (!result.body) throw new Error('空响应');
      return JSON.parse(result.body) as T;
    } catch {
      // 无法解析成功响应同样不能推断余额；写入可能已完成，必须走回执核查。
      throw new ActivityRequestError(writing ? 'RESULT_UNKNOWN' : 'INVALID_RESPONSE', writing, result.status);
    }
  }
}
