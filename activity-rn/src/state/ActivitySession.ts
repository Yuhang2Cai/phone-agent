// 活动会话状态：同运行时保存薄路由及滚动位置，账号代次变化必须清空私有投影。
import {ActivityClient, ActivityRequestError} from '../api/ActivityClient';
import type {ActivityTask, CoinAccount, CoinPage, CoinTransaction, RewardReceipt, TaskList} from '../api/ActivityModels';
import type {ActivityHost, HostSnapshot} from '../host/ActivityHost';

export type ActivityRoute = 'home' | 'detail' | 'history' | 'rules' | 'feedback' | 'records';
/** 请求态与任务业务状态分离；retryable 只能来自回读，重试仍需用户点击。 */
export type Feedback = {phase: 'idle' | 'submitting' | 'unknown' | 'checking' | 'retryable' | 'failed' | 'success' | 'accepted';
  task?: ActivityTask; action?: 'accept' | 'claim'; requestId?: string; receipt?: RewardReceipt; error?: string};
/** 页面不可变快照，余额未知必须为 null，不能填入排版样例。 */
export type ActivityState = {host: HostSnapshot; tasks: ActivityTask[]; balance: number | null; loading: boolean; error?: string;
  route: ActivityRoute; detail?: ActivityTask; detailLoading: boolean; feedback: Feedback; history: CoinTransaction[];
  nextCursor: string | null; historyLoading: boolean; historyError?: string; serverTime?: string};
/** 新身份无私有缓存，薄路由也归零，避免跨账户恢复详情。 */
const initial = (host: HostSnapshot): ActivityState => ({host, tasks: [], balance: null, loading: true, route: 'home',
  detailLoading: false, feedback: {phase: 'idle'}, history: [], nextCursor: null, historyLoading: false});

/** 平台无关状态机；身份与焦点事件只能触发读取，业务写入全部由显式操作触发。 */
export class ActivitySession {
  private state = initial({generation: 0, authenticated: false, focused: false});
  private readonly client: ActivityClient;
  private readonly listeners = new Set<() => void>();
  private readVersion = 0;
  private routes: ActivityRoute[] = [];
  private restoring = false;
  /** 滚动位置仅保留于当前运行时，换号清空。 */
  scrollOffset = 0;
  constructor(private readonly host: ActivityHost) {this.client = new ActivityClient(host);}
  /** React 订阅获得稳定引用，仅发布更新时更换快照。 */
  getSnapshot = (): ActivityState => this.state;
  /** 页面卸载主动解绑，不随底栏切换累积监听。 */
  subscribe = (listener: () => void): (() => void) => {this.listeners.add(listener);return () => {this.listeners.delete(listener);};};
  /** 成组发布余额与反馈，避免短暂显示互相矛盾的状态。 */
  private update(patch: Partial<ActivityState>): void {this.state = {...this.state, ...patch};this.listeners.forEach(listener => listener());}

  /** 重复焦点事件不重复请求；新账号先清数据再读取。 */
  async bind(snapshot: HostSnapshot): Promise<void> {
    // getSnapshot 与事件可能交错返回，较旧代次不能覆盖已经收到的新身份。
    if (snapshot.generation < this.state.host.generation) return;
    const previous = this.state.host;const changed = previous.generation !== snapshot.generation;this.client.bind(snapshot);
    if (changed) {this.readVersion++;this.routes = [];this.scrollOffset = 0;this.state = initial(snapshot);this.listeners.forEach(listener => listener());}
    else this.update({host: snapshot});
    if (snapshot.focused && (changed || !previous.focused)) {
      this.restoring = true;
      try {await this.refresh();await this.restorePending(snapshot.generation);} finally {if (this.current(snapshot.generation)) this.restoring = false;}
    }
  }
  /** 重建只读取原生薄引用和后端事实；恢复成功前禁止发起新写，且不自动重试原写。 */
  private async restorePending(generation: number, force = false): Promise<void> {
    if (!this.state.host.authenticated || (!force && this.state.feedback.phase !== 'idle')) return;
    try {
      const pending = await this.host.getPending(generation);if (!pending || !this.current(generation)) return;
      this.update({feedback: {phase: 'unknown', action: pending.action, requestId: pending.payload.requestId}});
      let task: ActivityTask | undefined;
      let recoveredAcceptance = false;
      if (pending.action === 'claim') task = await this.client.request<ActivityTask>('detail', {id: pending.payload.id});
      else {
        const list = await this.client.request<TaskList>('tasks', {requestId: pending.payload.requestId});
        recoveredAcceptance = !!list.recoveredTask;
        task = list.recoveredTask ?? list.tasks.find(item => item.definitionId === pending.payload.id && item.ruleVersion === pending.payload.version);
      }
      if (!this.current(generation)) return;
      if (!task) {this.update({error: 'RESULT_UNKNOWN'});return;}
      // 恢复界面采用服务器当前投影，原请求键仍来自磁盘；只有原回执可展示成功。
      this.update({feedback: {phase: 'unknown', task, action: pending.action, requestId: pending.payload.requestId}});
      if (pending.action === 'claim' && task.receipt) {this.finish(task, task.receipt, pending.payload.requestId);await this.clearPending(pending.payload.requestId, generation);}
      else if (pending.action === 'accept' && recoveredAcceptance && task.instanceId) {
        this.update({feedback: {phase: 'accepted', task, action: 'accept', requestId: pending.payload.requestId}});await this.clearPending(pending.payload.requestId, generation);
      }
    } catch (error) {if (this.current(generation)) this.update({error: this.code(error)});}
  }
  /** 任务与余额一起刷新，较旧请求不能覆盖新账号或已确认的领奖余额。 */
  async refresh(): Promise<void> {
    if (!this.state.host.focused || this.state.host.generation <= 0) return;
    const generation = this.state.host.generation;const version = ++this.readVersion;this.update({loading: true, error: undefined});
    try {
      const [list, account] = await Promise.all([this.client.request<TaskList>('tasks'), this.state.host.authenticated ? this.client.request<CoinAccount>('account') : Promise.resolve(null)]);
      if (!this.current(generation) || version !== this.readVersion) return;
      if (!Array.isArray(list.tasks) || (account && (!Number.isSafeInteger(account.balance) || account.balance < 0))) throw new ActivityRequestError('INVALID_RESPONSE');
      this.update({tasks: list.tasks, balance: account?.balance ?? null, serverTime: list.serverTime, loading: false});
    } catch (error) {
      if (!this.current(generation) || version !== this.readVersion) return;
      const code = this.code(error);this.update({loading: false, error: code, ...(code === 'LOGIN_REQUIRED' ? {tasks: [], balance: null, history: [], detail: undefined} : {})});
    }
  }
  /** 双击在生成请求键前即被提交态合并，按钮动作仍必须在服务器允许列表内。 */
  async act(task: ActivityTask): Promise<void> {
    if (!this.state.host.focused || this.restoring) return;
    if (!this.state.host.authenticated || task.allowedActions.includes('LOGIN')) {await this.destination('login');return;}
    const previous = this.state.feedback;
    if (previous.phase === 'submitting' || previous.phase === 'checking') return;
    if (['unknown', 'retryable'].includes(previous.phase)) {this.open('feedback');return;}
    if (task.allowedActions.includes('CLAIM') || task.allowedActions.includes('ACCEPT')) await this.submit(task, task.allowedActions.includes('CLAIM') ? 'claim' : 'accept');
    else if (task.allowedActions.includes('COMPLETE')) await this.destination('home');
    else if (task.instanceId) await this.openDetail(task);
  }
  /** 一次逻辑写持有一个稳定键；未确认结果保留原目标，绝不乐观增加金币。 */
  private async submit(task: ActivityTask, action: 'accept' | 'claim', originalKey?: string): Promise<void> {
    const generation = this.state.host.generation;this.readVersion++;this.open('feedback');this.update({feedback: {phase: 'submitting', task, action}, loading: false});
    let requestId = originalKey;
    try {
      requestId = requestId ?? await this.host.newRequestId();if (!this.current(generation)) return;
      this.update({feedback: {phase: 'submitting', task, action, requestId}});
      if (action === 'accept') {
        const instance = await this.client.request<ActivityTask>('accept', {id: task.definitionId, version: task.ruleVersion, requestId});
        if (!this.current(generation)) return;
        if (!instance.instanceId) throw new ActivityRequestError('RESULT_UNKNOWN', true);
        this.routes = [];this.update({route: 'home', tasks: this.replace(instance), feedback: {phase: 'accepted', task: instance, action, requestId}});
        await this.clearPending(requestId, generation);
      } else {
        const receipt = await this.client.request<RewardReceipt>('claim', {id: task.instanceId, requestId});
        if (this.current(generation)) {this.finish(task, receipt, requestId);await this.clearPending(requestId, generation);}
      }
    } catch (error) {
      if (this.current(generation)) {
        const uncertain = error instanceof ActivityRequestError && error.uncertain;
        this.update({feedback: {phase: uncertain ? 'unknown' : 'failed', task, action, requestId, error: this.code(error)}});
        if (!uncertain && requestId) await this.clearPending(requestId, generation);
      }
    }
  }
  /** 未知领奖读原实例，未知领取读原定义/周期的实例，不自动重新提交。 */
  async checkResult(): Promise<void> {
    const feedback = this.state.feedback;if (!feedback.action || !feedback.requestId || feedback.phase === 'checking') return;
    // 重建时若断网连原实例都未读到，先重新恢复薄引用，不能用虚构任务填充反馈卡。
    if (!feedback.task) {await this.restorePending(this.state.host.generation, true);return;}
    const generation = this.state.host.generation;this.update({feedback: {...feedback, phase: 'checking'}});
    try {
      let instance: ActivityTask | undefined;
      if (feedback.action === 'claim' && feedback.task.instanceId) instance = await this.client.request<ActivityTask>('detail', {id: feedback.task.instanceId});
      else {const list = await this.client.request<TaskList>('tasks', {requestId: feedback.requestId});instance = list.recoveredTask ?? undefined;}
      if (!this.current(generation)) return;
      if (feedback.action === 'claim' && instance?.receipt) {this.finish(instance, instance.receipt, feedback.requestId);await this.clearPending(feedback.requestId, generation);}
      else if (feedback.action === 'accept' && instance?.instanceId) {this.update({tasks: this.replace(instance), feedback: {...feedback, phase: 'accepted', task: instance}});await this.clearPending(feedback.requestId, generation);}
      else this.update({feedback: {...feedback, phase: 'retryable', task: instance ?? feedback.task}});
    } catch (error) {if (this.current(generation)) this.update({feedback: {...feedback, phase: 'unknown', error: this.code(error)}});}
  }
  /** 核查后的显式重试沿用原键，重新登录不会调用此方法。 */
  async retry(): Promise<void> {
    const feedback = this.state.feedback;
    if (!this.state.host.focused || !feedback.task || !feedback.action || !feedback.requestId || !['retryable', 'failed'].includes(feedback.phase)) return;
    if (feedback.error === 'LOGIN_REQUIRED') {await this.destination('login');return;}
    await this.submit(feedback.task, feedback.action, feedback.requestId);
  }
  /** 完整回执才允许替换余额；已撤回回执不能显示加币成功。 */
  private finish(task: ActivityTask, receipt: RewardReceipt, requestId: string): void {
    if (!receipt.receiptId || receipt.instanceId !== task.instanceId || !Number.isSafeInteger(receipt.balance) || receipt.balance < 0 || !Number.isSafeInteger(receipt.amount) || receipt.amount <= 0 || !['CLAIMED', 'REVERSED'].includes(receipt.status)) throw new ActivityRequestError('RESULT_UNKNOWN', true);
    this.readVersion++;const updated: ActivityTask = {...task, status: receipt.status, receipt, allowedActions: ['DETAIL']};
    this.update({balance: receipt.balance, tasks: this.replace(updated), feedback: {phase: receipt.status === 'CLAIMED' ? 'success' : 'failed', task: updated,
      receipt, requestId, action: 'claim', error: receipt.status === 'REVERSED' ? 'REVERSED' : undefined}});
  }
  /** 同实例或未领取的同定义原位替换，保留其他任务。 */
  private replace(task: ActivityTask): ActivityTask[] {
    let found = false;const tasks = this.state.tasks.map(item => {if (item.instanceId === task.instanceId || (!item.instanceId && item.definitionId === task.definitionId)) {found = true;return task;}return item;});
    return found ? tasks : [...tasks, task];
  }
  /** 路由仅保存在运行时，不携带凭据；普通切换无需重建状态机。 */
  open(route: ActivityRoute): void {if (route === this.state.route) return;this.routes.push(this.state.route);this.update({route});}
  /** 子页面消费返回键，首页交还原生导航。 */
  back(): boolean {const route = this.routes.pop();if (!route) return false;this.update({route});return true;}
  /** 回首页不清除未知结果，避免从任务卡重新创建写请求。 */
  home(): void {this.routes = [];this.update({route: 'home', ...(['success', 'accepted'].includes(this.state.feedback.phase) ? {feedback: {phase: 'idle'} as Feedback} : {})});}
  /** 详情通过原实例重新读取，旧卡片只作引用，不能决定领奖资格。 */
  async openDetail(task: ActivityTask): Promise<void> {
    // 未领取实例不存在，只展示当前服务端公开定义；领取必须由详情中的主按钮显式触发。
    if (!task.instanceId) {this.open('detail');this.update({detail: task, detailLoading: false, error: undefined});return;}
    const generation = this.state.host.generation;this.open('detail');this.update({detail: undefined, detailLoading: true, error: undefined});
    try {const detail = await this.client.request<ActivityTask>('detail', {id: task.instanceId});if (this.current(generation)) this.update({detail, detailLoading: false});}
    catch (error) {if (this.current(generation)) this.update({detailLoading: false, error: this.code(error)});}
  }
  /** 明细首次加载第一页，游客先去登录且登录后不会自动写入。 */
  async openHistory(): Promise<void> {if (!this.state.host.authenticated) {await this.destination('login');return;}this.open('history');await this.loadHistory(false);}
  /** 分页请求合并并保留原游标，失败不跳页、不伪造空明细成功。 */
  async loadHistory(append = true): Promise<void> {
    if (this.state.historyLoading || (append && !this.state.nextCursor)) return;
    const generation = this.state.host.generation;this.update({historyLoading: true, historyError: undefined});
    try {
      const page = await this.client.request<CoinPage>('transactions', {limit: 20, ...(append ? {cursor: this.state.nextCursor} : {})});if (!this.current(generation)) return;
      if (!Array.isArray(page.items)) throw new ActivityRequestError('INVALID_RESPONSE');
      this.update({history: append ? [...this.state.history, ...page.items] : page.items, nextCursor: page.nextCursor, historyLoading: false});
    } catch (error) {if (this.current(generation)) this.update({historyLoading: false, historyError: this.code(error)});}
  }
  /** 平台导航只使用白名单目的地，失败映射为安全提示。 */
  async destination(target: 'login' | 'home' | 'orders'): Promise<void> {
    try {await this.host.openDestination(target, this.state.host.generation);} catch (error) {this.update({error: this.code(error)});}
  }
  /** 原生握手失败时解除无限加载；点击重试可再次获取宿主快照。 */
  connectionFailed(): void {this.update({loading: false, error: 'NETWORK_ERROR'});}
  /** 清除失败保留原生引用供下次只读恢复，不把已验证回执降级为假失败。 */
  private async clearPending(requestId: string, generation: number): Promise<void> {
    try {await this.host.clearPending(requestId, generation);} catch {if (this.current(generation)) this.update({error: 'LOCAL_STORAGE_ERROR'});}
  }
  /** 身份只比较宿主代次，JS不持有用户名或访问令牌。 */
  private current(generation: number): boolean {return generation === this.state.host.generation;}
  /** UI只获取稳定原因码，不直接展示异常正文。 */
  private code(error: unknown): string {return error instanceof ActivityRequestError ? error.code : 'NETWORK_ERROR';}
}
