// 请求边界回归：使用受控异步宿主模拟时序，验证身份隔离和唯一写，不替代真实后端验收。
import {ActivityClient, ActivityRequestError} from '../ActivityClient';
import type {ActivityHost, NativeReply} from '../../host/ActivityHost';

/** 仅控制回包时间，不替换待测客户端逻辑；每例独立创建宿主和代次。 */
function fixture() {
  let complete!: (reply: NativeReply) => void;
  const reply = new Promise<NativeReply>(resolve => { complete = resolve; });
  const request = jest.fn(() => reply);
  const host: ActivityHost = {request, getPending: async () => null, clearPending: async () => true, reportInteractive: () => {}, getSnapshot: async () => ({generation: 1, authenticated: true, focused: true}), newRequestId: async () => 'synthetic-key', openDestination: async () => true};
  const client = new ActivityClient(host);
  client.bind({generation: 1, authenticated: true, focused: true});
  return {client, request, complete};
}

/** A 的响应在切换 B 后到达，必须拒绝，不能把余额或任务交给新页面。 */
test('旧代次回包不能污染新会话', async () => {
  const f = fixture();
  const pending = f.client.request('account');
  const assertion = expect(pending).rejects.toMatchObject({code: 'SESSION_CHANGED'});
  f.client.bind({generation: 2, authenticated: true, focused: true});
  f.complete({ok: true, status: 200, body: '{"balance":10}'});
  await assertion;
});

/** 同一键的双击共用正在进行的写；不能只禁用按钮却仍发出两个底层请求。 */
test('重复领取金币只派发一次', async () => {
  const f = fixture();
  const first = f.client.request('claim', {id: 'instance', requestId: 'key'});
  const second = f.client.request('claim', {id: 'instance', requestId: 'key'});
  f.complete({ok: true, status: 200, body: '{"receiptId":"receipt"}'});
  await Promise.all([first, second]);
  expect(f.request).toHaveBeenCalledTimes(1);
});

/** 网络超时不能构造空成功对象或自动新造键，必须保留可核查的未知结果语义。 */
test('写超时标记待核查且不重发', async () => {
  const f = fixture();
  const pending = f.client.request('claim', {id: 'instance', requestId: 'key'});
  const assertion = expect(pending).rejects.toEqual(new ActivityRequestError('RESULT_UNKNOWN', true));
  f.complete({ok: false, status: 0, error: 'TIMEOUT'});
  await assertion;
  expect(f.request).toHaveBeenCalledTimes(1);
});
