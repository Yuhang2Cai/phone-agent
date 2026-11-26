// 活动页面：按确认的 r2 组件组合首页、详情、反馈、规则和明细；没有硬编码业务示例数据。
import React from 'react';
import {Image, StyleSheet, Text, View} from 'react-native';
import type {ActivityState, ActivitySession} from '../state/ActivitySession';
import type {ActivityTask} from '../api/ActivityModels';
import {ActivityButton, ParkBanner, ParkHero, CoinWallet, TaskCard, StreakCard, StreakDays, actionLabel, taskLabel, businessTime, parkImage} from '../components/ActivityComponents';
import Coin104 from '../assets/1db36.svg';
import {colors as c, common as s} from '../theme/activityTheme';

/** 服务端原因码的中文白名单；未知值不直接进入UI，防止异常信息泄漏。 */
export function errorText(code?: string): string {
  return ({LOGIN_REQUIRED: '登录状态已失效，请重新登录。', RATE_LIMITED: '操作较频繁，请稍后再试。', RULE_VERSION_CHANGED: '任务规则已更新，请返回活动刷新后重新领取。',
    NOT_READY: '当前有效订单尚未达标，请查看任务进度。', INTERRUPTED: '连续任务已中断，本周期无法重新领取。', EXPIRED: '任务已结束，当前无法领奖。',
    CLAIM_EXPIRED: '已超过领奖截止时间。', REVERSED: '退款后任务不再达标，奖励已撤回。', TASK_UNAVAILABLE: '该任务当前不可领取。',
    INSUFFICIENT_WINDOW: '本活动剩余时间不足以完成全部天数。', ACTIVE_INSTANCE_EXISTS: '请先查看已领取的连续任务。',
    NOT_FOUND: '任务当前不可见，请返回活动刷新。', IDEMPOTENCY_CONFLICT: '请求状态已变化，请返回活动核查。', SERVICE_UNAVAILABLE: '活动服务暂不可用，请稍后重试。',
    RESULT_UNKNOWN: '原领取请求仍待核查，请恢复网络后重新查询。', LOCAL_STORAGE_ERROR: '原请求记录暂不可读取，请稍后重新进入活动。',
  } as Record<string, string>)[code ?? ''] ?? '网络连接异常，请检查网络后重试。';
}
/** 蓝色终态记录与进行中任务分开展示，但都来自同一权威任务列表。 */
const ended = (task: ActivityTask) => ['INTERRUPTED', 'EXPIRED', 'CLAIM_EXPIRED', 'REVERSED'].includes(task.status);

/** 错误/空态/加载复用Figma的276dp提示卡，按钮保持可访问语义。 */
export function StatePanel({kind, message, onPress, label}: {kind: 'loading' | 'error' | 'empty'; message?: string; onPress?: () => void; label?: string}) {
  const title = kind === 'loading' ? '正在加载活动' : kind === 'empty' ? '暂时没有可领取的任务' : '暂时无法加载活动';
  return <View style={[s.card, styles.statePanel, {backgroundColor: kind === 'empty' ? c.pink : c.purple}]} accessibilityLiveRegion="polite">
    <View style={styles.stateMark}><Text style={styles.markText}>{kind === 'loading' ? '…' : kind === 'empty' ? '—' : '!'}</Text></View>
    <Text style={[s.title, s.center, {color: c.forest}]}>{title}</Text><Text style={[s.bodyText, s.center]}>{message ?? (kind === 'loading' ? '正在获取任务进度与金币余额…' : kind === 'empty' ? '新的任务上线后，会显示在这里。\n你仍可查看金币余额和明细。' : '网络连接异常，请检查网络后重试。\n已有任务和金币不会因此丢失。')}</Text>
    <ActivityButton label={label ?? (kind === 'loading' ? '加载中' : kind === 'empty' ? '查看金币明细' : '重新加载')} onPress={onPress} disabled={kind === 'loading'} style={styles.stretch} />
  </View>;
}

/** 首屏只展示接口卡片；服务错误不能被转换成空任务，游客也读取真实定义。 */
export function ActivityHomeScreen({state, session}: {state: ActivityState; session: ActivitySession}) {
  const wallet = () => {void session.openHistory();};
  if (state.loading && !state.tasks.length) return <><ParkBanner /><StatePanel kind="loading" /></>;
  if (state.error) return <><ParkBanner /><StatePanel kind="error" message={errorText(state.error)} label={state.error === 'LOGIN_REQUIRED' ? '重新登录' : '重新加载'} onPress={() => {void (state.error === 'LOGIN_REQUIRED' ? session.destination('login') : session.refresh());}} /></>;
  if (!state.tasks.length) return <><CoinWallet balance={state.balance} onPress={wallet} /><ParkBanner /><StatePanel kind="empty" onPress={wallet} /></>;
  const current = state.tasks.filter(task => !ended(task));const daily = current.filter(task => task.type === 'DAILY_ORDER_COUNT');const streak = current.filter(task => task.type === 'CONSECUTIVE_ORDER_DAYS');
  return <>
    <ParkHero balance={state.balance} guest={!state.host.authenticated} onWallet={wallet} />
    {daily.length > 0 && <><View style={styles.sectionRow}><Text style={[s.section, s.grow]}>今日游玩任务</Text><Text style={s.tiny}>每天都有小奖励</Text></View>
      {daily.map((task, index) => <TaskCard key={task.instanceId ?? task.definitionId} task={task} index={index} guest={!state.host.authenticated} onAction={() => {void session.act(task);}} onDetail={() => {void session.openDetail(task);}} />)}</>}
    {streak.length > 0 && <><View style={styles.sectionRow}><Text style={[s.section, s.grow]}>连续挑战</Text><Text style={s.tiny}>下一站，更多金币</Text></View>
      {streak.map(task => <StreakCard key={task.instanceId ?? task.definitionId} task={task} onAction={() => {void session.act(task);}} onDetail={() => {void session.openDetail(task);}} />)}</>}
    {state.tasks.some(ended) && <ActivityButton label="查看任务记录" secondary onPress={() => session.open('records')} />}
    {['unknown', 'retryable'].includes(state.feedback.phase) && <ActivityButton label="有领取结果待核查" secondary onPress={() => session.open('feedback')} />}
  </>;
}

/** 详情展示真实逐日进度，按钮允许动作与首页保持一致。 */
export function TaskDetailScreen({state, session}: {state: ActivityState; session: ActivitySession}) {
  const task = state.detail;
  if (state.detailLoading) return <><ParkBanner /><StatePanel kind="loading" /></>;
  if (!task) return <StatePanel kind="error" message={errorText(state.error)} label="返回活动" onPress={() => session.home()} />;
  const writable = task.allowedActions.some(action => ['LOGIN', 'ACCEPT', 'CLAIM', 'COMPLETE'].includes(action));
  return <>
    <View style={[s.card, styles.detailRoute]}><View style={[s.row, {alignItems: 'flex-start'}]}><View style={[s.grow, {gap: 4}]}>
      <Text style={[s.title, {lineHeight: 31}]}>{task.title}</Text><Text style={[s.link, {color: c.violet, fontSize: 14}]}>完成可领 {task.rewardCoins} 金币</Text>
      <Text style={s.tiny}>{task.type === 'CONSECUTIVE_ORDER_DAYS' ? `连续 ${task.dayCount} 天，每天完成 ${task.targetCount} 单` : `当天完成 ${task.targetCount} 单`}</Text>
    </View><Image source={parkImage} style={styles.detailArt} resizeMode="cover" accessible={false} /></View>
      <StreakDays task={task} detail /><Text style={[s.link, {color: c.violet}]}>{task.instanceId ? `${taskLabel(task)} · 已完成 ${task.completed ?? 0}/${task.required ?? (task.type === 'CONSECUTIVE_ORDER_DAYS' ? task.dayCount : task.targetCount)} ${task.type === 'CONSECUTIVE_ORDER_DAYS' ? '天' : '单'}` : '领取后开始计算进度'}</Text>
    </View>
    <View style={[s.card, {backgroundColor: c.purple, minHeight: 178}]}><Text style={[s.section, {fontWeight: '500'}]}>{ended(task) ? taskLabel(task) : '完成今天的旅程'}</Text>
      <Text style={s.bodyText}>领取后支付才计入，一单多份仍算一单；连续任务中断一天，本轮失败。退款中的订单暂不计入。</Text>
      <Text style={s.small}>任务截止：{task.instanceId ? businessTime(task.windowEnd) : '领取后按自然日计算'}</Text><Text style={s.small}>领奖截止：{task.instanceId ? businessTime(task.claimDeadline) : '以领取后的任务回执为准'}</Text>
    </View>
    <ActivityButton label={task.allowedActions.includes('COMPLETE') ? '去首页下单' : actionLabel(task)} disabled={!writable} onPress={() => {void session.act(task);}} />
    <ActivityButton label="查看完整活动规则" secondary onPress={() => session.open('rules')} />
  </>;
}

/** 成功只展示真实奖励回执；未知结果、处理中、明确拒绝分别使用不同文案与操作。 */
export function RewardFeedbackScreen({state, session}: {state: ActivityState; session: ActivitySession}) {
  const feedback = state.feedback;const task = feedback.task;
  // 重建且网络不可用时没有可靠任务投影，只显示核查入口，不编造任务名称或奖励金额。
  if (!task) return <><ParkBanner /><StatePanel kind="error" message="原领取请求仍待核查，请先恢复网络并查询结果。" label="查询领取结果" onPress={() => {void session.checkResult();}} /><ActivityButton label="返回活动" secondary onPress={() => session.home()} /></>;
  if (feedback.phase === 'success' && feedback.receipt) return <>
    <CoinWallet balance={state.balance} onPress={() => {void session.openHistory();}} />
    <View style={styles.success}><Coin104 /><Text style={[s.title, s.center]}>金币已到账！</Text>
      <Text style={styles.successAmount}>+{feedback.receipt.amount} 金币</Text><Text style={[s.small, s.center]}>{task.title} · 奖励已入账</Text>
      <ActivityButton label="查看金币明细" onPress={() => {void session.openHistory();}} style={styles.stretch} />
      <ActivityButton label="继续逛游乐园" secondary onPress={() => session.home()} style={styles.stretch} />
    </View>
  </>;
  const busy = feedback.phase === 'submitting' || feedback.phase === 'checking';const unknown = feedback.phase === 'unknown';
  const accepting = feedback.action === 'accept';
  const title = busy ? feedback.phase === 'checking' ? '正在核查领取结果' : accepting ? '正在领取任务' : '正在领取金币' : unknown ? '正在核查领取结果' : feedback.phase === 'retryable' ? '尚未查到领取回执' : feedback.phase === 'accepted' ? '任务已领取' : '暂时无法领取';
  const message = busy ? '正在处理请求，请稍候。\n到账结果确认后会更新余额。' : unknown ? '网络中断，暂未确认领取是否成功。\n请先查询结果，避免重复提交。' : feedback.phase === 'retryable' ? '已查询原任务，暂未发现成功回执。\n可使用原请求重试，余额以服务端为准。' : feedback.phase === 'accepted' ? '领取后完成支付即可累计任务进度。' : errorText(feedback.error);
  return <><CoinWallet balance={state.balance} onPress={() => {void session.openHistory();}} /><ParkBanner />
    <View style={[s.card, styles.feedback]} accessibilityLiveRegion="polite"><Text style={s.bodyText}>{task.title}</Text><Text style={styles.feedbackAmount}>{task.rewardCoins} 金币</Text>
      <Text style={[s.section, {fontSize: 18, lineHeight: 27, color: c.forest}]}>{title}</Text><Text style={s.bodyText}>{message}</Text>
      <ActivityButton label={busy ? '处理中…' : unknown ? '查询领取结果' : feedback.phase === 'accepted' ? '去首页下单' : feedback.error === 'LOGIN_REQUIRED' ? '重新登录' : '重试领取'} disabled={busy || feedback.error === 'REVERSED'}
        onPress={() => {void (unknown ? session.checkResult() : feedback.phase === 'accepted' ? session.destination('home') : session.retry());}} />
    </View>{!busy && <ActivityButton label="返回活动" secondary onPress={() => session.home()} />}</>;
}

/** 金币流水逐条来自稳定游标，包含原奖励和撤回，不在页面拼造“充值成功”。 */
export function CoinHistoryScreen({state, session}: {state: ActivityState; session: ActivitySession}) {
  return <><CoinWallet balance={state.balance} /><View style={styles.sectionRow}><Text style={[s.section, s.grow, {fontSize: 18, lineHeight: 27}]}>全部记录</Text><Text style={s.small}>按时间排序</Text></View>
    {state.history.map((item, index) => <View key={item.transactionId} style={[s.card, {minHeight: 110, backgroundColor: item.kind === 'REVERSAL' ? c.blue : index % 2 ? c.mint : c.pink}]}>
      <View style={s.row}><Text style={[s.bodyText, s.grow, {fontWeight: '500', color: c.ink}]}>{item.kind === 'REVERSAL' ? '任务奖励撤回' : item.title}</Text><Text style={[s.section, {fontWeight: '500', color: item.amount > 0 ? c.gold : c.secondary}]}>{item.amount > 0 ? '+' : '−'}{Math.abs(item.amount)}</Text></View>
      <Text style={s.small}>{item.kind === 'REVERSAL' ? '退款后不再满足任务条件' : '任务奖励'}</Text><Text style={s.small}>{businessTime(item.createdAt)}</Text>
    </View>)}
    {state.historyError ? <><Text style={s.bodyText}>{errorText(state.historyError)}</Text><ActivityButton label="重新加载明细" onPress={() => {void session.loadHistory(false);}} /></> : state.historyLoading ? <Text style={s.small}>正在加载金币明细…</Text> : !state.history.length ? <View style={[s.card, {backgroundColor: c.purple}]}><Text style={s.section}>暂时没有金币记录</Text><Text style={s.bodyText}>完成任务并领取金币后，记录会显示在这里。</Text></View> : state.nextCursor ? <ActivityButton label="加载更多记录" secondary onPress={() => {void session.loadHistory();}} /> : <Text style={s.small}>已显示全部记录</Text>}
  </>;
}

/** 规则说明是确认稿业务文案，补充部分退款整单排除口径，不暴露技术实现。 */
const rules = [
  ['01  先领取，再完成', '点击“领取任务”后完成支付的订单才计入进度。未支付订单不计入，一笔订单购买多份仍只算一单。'],
  ['02  每日任务', '按北京时间自然日统计，每天 00:00 更新。不同任务可同时推进，同一任务每周期只奖励一次。'],
  ['03  连续任务', '从领取当天开始，每天达到指定订单数。中断一天则本轮失败，同一活动周期内不能重新领取。'],
  ['04  领取金币', '达成条件后需手动领奖，请在任务结束后的 24 小时内领取。过期无法领取，具体截止时间以任务详情为准。'],
  ['05  退款与金币', '退款申请中的订单暂不计入，部分退款也按整单排除。退款成功后若任务不再达标，已发金币会撤回，明细中保留记录。'],
  ['06  金币用途', '金币用于站内积累与展示，当前不支持抵扣、兑换、提现或转赠。'],
];
/** 规则不依赖账号和网络，在加载/错误页仍可查看。 */
export function ActivityRulesScreen() {return <>{rules.map(([title, body], index) => <View key={title} style={[s.card, {minHeight: 96, backgroundColor: [c.blue, c.purple, c.pink, c.mint][index % 4]}]}><Text style={[s.bodyText, {fontWeight: '500', color: c.forest}]}>{title}</Text><Text style={[s.small, {color: c.secondary}]}>{body}</Text></View>)}</>;}

/** 终态页所有原因和奖励来自已领取实例，不展示设计中的固定日期/金额。 */
export function TaskRecordsScreen({state, session}: {state: ActivityState; session: ActivitySession}) {
  const records = state.tasks.filter(ended);
  return <><View style={[s.card, {backgroundColor: c.blue, minHeight: 110}]}><Text style={s.section}>查看已结束的任务</Text><Text style={s.bodyText}>连续任务中断后，本活动周期内无法重新领取。任务和领奖均有独立截止时间。</Text></View>
    {records.map(task => <View key={task.instanceId} style={[s.card, {backgroundColor: c.blue, minHeight: 138}]}><View style={s.row}><Text style={[s.section, s.grow, {fontWeight: '500'}]}>{task.title}</Text><Text style={[s.bodyText, {color: c.gold}]}>{task.rewardCoins} 金币</Text></View>
      <Text style={s.small}>{errorText(task.status)}</Text><View style={s.row}><Text style={[s.small, s.grow]}>{taskLabel(task)}</Text><ActivityButton label={taskLabel(task)} disabled style={{width: 104}} /></View>
    </View>)}<ActivityButton label="返回活动，查看新任务" onPress={() => session.home()} /></>;
}

/** 页面尺寸采用确认稿最小高度；内容增加时允许滚动，避免窄屏或大字体裁切。 */
const styles = StyleSheet.create({
  sectionRow: {minHeight: 22, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'}, stretch: {alignSelf: 'stretch'},
  statePanel: {minHeight: 276, alignItems: 'center'}, stateMark: {width: 64, height: 64, borderRadius: 16, backgroundColor: '#E5F0E8', justifyContent: 'center'},
  markText: {fontSize: 32, lineHeight: 48, fontWeight: '700', textAlign: 'center', color: c.forest, includeFontPadding: false},
  detailRoute: {minHeight: 290, backgroundColor: c.purple, gap: 12}, detailArt: {width: 128, height: 94, borderRadius: 12},
  success: {minHeight: 440, padding: 20, gap: 8, borderRadius: 24, backgroundColor: c.yellow, alignItems: 'center'},
  successAmount: {fontSize: 32, lineHeight: 45, fontWeight: '700', color: c.orange, textAlign: 'center', includeFontPadding: false},
  feedback: {minHeight: 300, backgroundColor: c.pink}, feedbackAmount: {fontSize: 32, lineHeight: 48, fontWeight: '700', color: c.gold, includeFontPadding: false},
});
