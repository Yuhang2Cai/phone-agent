// 状态机回归：使用可控宿主响应验证真实请求方向，不以UI快照代替业务断言。
import {ActivitySession} from '../ActivitySession';
import type {ActivityHost, NativeReply} from '../../host/ActivityHost';
import type {ActivityTask} from '../../api/ActivityModels';

/** 合成任务仅用于测试输入，生产页面不能引用该夹具。 */
const task: ActivityTask = {definitionId: 'definition', instanceId: 'instance', ruleVersion: 1, title: '合成任务', type: 'DAILY_ORDER_COUNT', targetCount: 1, dayCount: 1, rewardCoins: 10, status: 'READY', allowedActions: ['CLAIM', 'DETAIL']};
const snapshot = {generation: 1, authenticated: true, focused: true};
/** 单个测试控制所有网络结果和请求键，避免真实时间/网络影响状态顺序。 */
function host(): ActivityHost & {request: jest.Mock} {
  return {getSnapshot: async () => snapshot, newRequestId: async () => 'original-key', openDestination: async () => true, getPending: async () => null, clearPending: async () => true, reportInteractive: () => {},
    request: jest.fn(async action => ({ok: true, status: 200, body: JSON.stringify(action === 'tasks' ? {tasks: [task], serverTime: '2026-10-28T04:00:00Z'} : {balance: 10})}))};
}
/** 延迟Promise用于精确安排换号和请求返回顺序。 */
function deferred<T>() {let resolve!: (value: T) => void;const promise = new Promise<T>(done => {resolve = done;});return {promise, resolve};}

test('切换账号清空余额并丢弃旧请求的迟到结果', async () => {
  const bridge = host(); const session = new ActivitySession(bridge); await session.bind(snapshot);
  expect(session.getSnapshot().balance).toBe(10);
  const old = deferred<NativeReply>();bridge.request.mockImplementation(action => action === 'account' ? old.promise : Promise.resolve({ok: true, status: 200, body: JSON.stringify({tasks: [task]})}));
  const loading = session.refresh(); await session.bind({generation: 2, authenticated: false, focused: false});
  old.resolve({ok: true, status: 200, body: '{"balance":999}'});await loading;
  expect(session.getSnapshot().balance).toBeNull();expect(session.getSnapshot().tasks).toEqual([]);
});

test('领奖超时不乐观加币且查询不会自动重放写入', async () => {
  const bridge = host();const session = new ActivitySession(bridge);await session.bind(snapshot);
  bridge.request.mockImplementation(async action => action === 'claim' ? {ok: false, status: 0} : {ok: true, status: 200, body: JSON.stringify(task)});
  await session.act(task);expect(session.getSnapshot().feedback.phase).toBe('unknown');expect(session.getSnapshot().balance).toBe(10);
  await session.checkResult();expect(bridge.request.mock.calls.filter(call => call[0] === 'claim')).toHaveLength(1);
  expect(session.getSnapshot().feedback.phase).toBe('retryable');
});

test('快速重复点击只生成一个逻辑写请求并保留原键', async () => {
  const bridge = host();const session = new ActivitySession(bridge);await session.bind(snapshot);
  const pending = deferred<NativeReply>();bridge.request.mockImplementation(action => action === 'claim' ? pending.promise : Promise.resolve({ok: true, status: 200, body: JSON.stringify({tasks: [task], balance: 20})}));
  const first = session.act(task);const second = session.act(task);await Promise.resolve();await Promise.resolve();
  expect(bridge.request.mock.calls.filter(call => call[0] === 'claim')).toHaveLength(1);
  // 宿主白名单使用 id，不接受网络接口模型里的 instanceId 字段名。
  expect(JSON.parse(bridge.request.mock.calls.find(call => call[0] === 'claim')![1])).toEqual({id: 'instance', requestId: 'original-key'});
  pending.resolve({ok: true, status: 200, body: JSON.stringify({receiptId: 'receipt', instanceId: 'instance', amount: 10, balance: 20, status: 'CLAIMED'})});await Promise.all([first, second]);
  expect(session.getSnapshot().balance).toBe(20);expect(session.getSnapshot().feedback.phase).toBe('success');
});

/** 前一写入未核清前不能被其他任务覆盖，否则原幂等键会丢失。 */
test('另一任务点击不能覆盖未知写入', async () => {
  const bridge = host();const session = new ActivitySession(bridge);await session.bind(snapshot);
  bridge.request.mockResolvedValue({ok: false, status: 0});await session.act(task);
  await session.act({...task, definitionId: 'other-definition', instanceId: 'other-instance'});
  expect(bridge.request.mock.calls.filter(call => call[0] === 'claim')).toHaveLength(1);
  expect(session.getSnapshot().feedback.task?.instanceId).toBe('instance');
});

/** 进程重建只恢复原生薄引用并回读原实例，不重发已经可能成功的领奖请求。 */
test('重建恢复原请求键并只读原实例', async () => {
  const bridge = Object.assign(host(), {getPending: jest.fn(async () => ({action: 'claim', payload: {id: 'instance', requestId: 'original-key'}})), clearPending: jest.fn(async () => true)});
  bridge.request.mockImplementation(async action => ({ok: true, status: 200, body: JSON.stringify(action === 'tasks' ? {tasks: [task]} : action === 'detail' ? task : {balance: 10})}));
  const session = new ActivitySession(bridge);await session.bind(snapshot);
  expect(session.getSnapshot().feedback.requestId).toBe('original-key');
  expect(session.getSnapshot().feedback.phase).toBe('unknown');
  expect(bridge.request.mock.calls.filter(call => call[0] === 'claim')).toHaveLength(0);
});

/** 未知领取跨天后，列表中的新周期实例不是原请求已提交的证据。 */
test('恢复领取不能把当前周期实例误认成原请求回执', async () => {
  const bridge = Object.assign(host(), {getPending: jest.fn(async () => ({action: 'accept' as const, payload: {id: task.definitionId, version: 1, requestId: 'old-key'}})), clearPending: jest.fn(async () => true)});
  const session = new ActivitySession(bridge);await session.bind(snapshot);
  expect(session.getSnapshot().feedback.phase).toBe('unknown');expect(bridge.clearPending).not.toHaveBeenCalled();
});

/** 查看尚未领取的任务只展示公开详情，不能把详情点击当作领取确认。 */
test('未领取任务详情不会自动执行领取', async () => {
  const bridge = host();const session = new ActivitySession(bridge);await session.bind(snapshot);
  const available: ActivityTask = {...task, instanceId: undefined, status: 'AVAILABLE', allowedActions: ['ACCEPT']};
  await session.openDetail(available);
  expect(bridge.request.mock.calls.filter(call => call[0] === 'accept' || call[0] === 'claim')).toHaveLength(0);
  expect(session.getSnapshot().route).toBe('detail');expect(session.getSnapshot().detail).toEqual(available);
});
