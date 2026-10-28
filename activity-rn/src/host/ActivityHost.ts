// 跨平台宿主契约：Android 已提供原生适配，未来 iOS 实现相同薄接口，不传递令牌。
export type HostSnapshot = {generation: number; authenticated: boolean; focused: boolean; entryId?: number};
export type ActivityAction = 'tasks' | 'account' | 'accept' | 'detail' | 'claim' | 'transactions';
export type NativeReply = {ok: boolean; status: number; body?: string | null; error?: string};
/** 原生持久化的薄请求引用；不保存余额、资格或认证资料。 */
export type PendingWrite = {action: 'accept' | 'claim'; payload: {id: string; version?: number; requestId: string}};

/** 仅声明业务动作、原生导航和随机键；不允许任意地址、用户身份或奖励输入。 */
export interface ActivityHost {
  getSnapshot(): Promise<HostSnapshot>;
  newRequestId(): Promise<string>;
  getPending(generation: number): Promise<PendingWrite | null>;
  clearPending(requestId: string, generation: number): Promise<boolean>;
  reportInteractive(generation: number, entryId: number): void;
  request(action: ActivityAction, payload: string, generation: number): Promise<NativeReply>;
  openDestination(destination: 'login' | 'home' | 'orders', generation: number): Promise<boolean>;
}
