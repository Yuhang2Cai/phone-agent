// Android 适配层：把原生薄状态与白名单方法注入共享会话，页面不依赖令牌或任意网络客户端。
import {NativeEventEmitter, NativeModules} from 'react-native';
import type {ActivityHost, HostSnapshot} from './ActivityHost';
import {ActivitySession} from '../state/ActivitySession';

/** 单个 JS 运行时只创建一份状态机，Tab 重挂载保留同账号的路由和滚动位置。 */
export const activityHost: ActivityHost = NativeModules.ActivityHost;
export const activitySession = new ActivitySession(activityHost);

/** 先订阅再握手，避免首次挂载期间丢掉认证/焦点变化；返回函数用于解绑。 */
export function observeAndroidHost(): () => void {
  const emitter = new NativeEventEmitter(NativeModules.ActivityHost);
  const subscription = emitter.addListener('ActivityHostChanged', (snapshot: HostSnapshot) => {void activitySession.bind(snapshot);});
  void connectAndroidHost();return () => subscription.remove();
}
/** 手动重连只读取状态和业务数据，永远不自动恢复领取/领奖写请求。 */
export async function connectAndroidHost(refresh = false): Promise<void> {
  try {const snapshot = await activityHost.getSnapshot();await activitySession.bind(snapshot);if (refresh && snapshot.focused) await activitySession.refresh();}
  catch {activitySession.connectionFailed();}
}
