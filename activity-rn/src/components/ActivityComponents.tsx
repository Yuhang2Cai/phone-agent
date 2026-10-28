// 活动展示组件：逐块对应 r2 的乐园插画、金币账户、票券和路线；输入只读服务端投影。
import React from 'react';
import {Image, Pressable, ScrollView, StyleSheet, Text, View, type StyleProp, type ViewStyle} from 'react-native';
import type {ActivityTask} from '../api/ActivityModels';
import {colors as c, common as s} from '../theme/activityTheme';
import Coin28 from '../assets/80fe4.svg';
import Coin32 from '../assets/2a49f.svg';
import Coin44 from '../assets/9d141.svg';
/** 唯一装饰插画直接使用 Figma 导出的资源，不把整屏截图当作页面。 */
export const parkImage = require('../assets/6ce53.png');

/** 所有按钮至少44dp高；disabled 同时阻止点击与无障碍动作，副作用由调用方提供。 */
export function ActivityButton({label, onPress, secondary = false, disabled = false, style, testID}: {
  label: string; onPress?: () => void; secondary?: boolean; disabled?: boolean; style?: StyleProp<ViewStyle>; testID?: string;
}) {
  return <Pressable testID={testID} accessibilityRole="button" accessibilityState={{disabled}} accessibilityLabel={label}
    disabled={disabled} onPress={onPress} style={({pressed}) => [styles.button, {backgroundColor: disabled ? c.disabled : secondary ? c.purple : c.orange, opacity: pressed ? 0.82 : 1}, style]}>
    <Text style={[styles.buttonText, {color: disabled ? c.secondary : secondary ? c.violet : c.white}]}>{label}</Text>
  </Pressable>;
}

/** 首页250dp横幅和52dp账户条；游客余额为破折号，不能伪造为零。 */
export function ParkHero({balance, guest, onWallet}: {balance: number | null; guest: boolean; onWallet: () => void}) {
  return <View style={styles.hero}>
    <View style={styles.heroTop}><View style={styles.heroCopy}>
      <Text style={styles.tagline}>快乐集币计划</Text><Text style={styles.heroTitle}>金币游乐园</Text><Text style={s.small}>做任务 · 集金币</Text>
    </View><Image source={parkImage} resizeMode="cover" style={styles.heroArt} accessible={false} /></View>
    <Pressable onPress={onWallet} accessibilityRole="button" accessibilityLabel={guest ? '登录后集金币' : '查看金币明细'} style={styles.walletStrip}>
      <Coin28 /><Text style={styles.stripBalance} numberOfLines={1} adjustsFontSizeToFit>{balance ?? '—'}</Text>
      <Text style={[s.small, s.grow]}>{guest ? '登录后集金币' : '我的金币'}</Text><Text style={s.link}>{guest ? '待登录' : '明细 ›'}</Text>
    </Pressable>
  </View>;
}

/** 状态页136dp横幅与设计同槽位，图片144×112且保持cover裁切。 */
export function ParkBanner() {
  return <View style={styles.banner}><View style={[s.grow, styles.bannerCopy]}><Text style={[s.title, {lineHeight: 31}]}>金币游乐园</Text><Text style={s.small}>收集日常的小奖励</Text></View>
    <Image source={parkImage} style={styles.bannerArt} resizeMode="cover" accessible={false} /></View>;
}

/** 明细和反馈页的账户摘要；只有可导航时才显示点击语义。 */
export function CoinWallet({balance, onPress}: {balance: number | null; onPress?: () => void}) {
  return <Pressable onPress={onPress} disabled={!onPress} accessibilityRole={onPress ? 'button' : undefined} accessibilityLabel={onPress ? '查看金币明细' : undefined} style={styles.wallet}>
    <View style={[s.grow, {gap: 3}]}><Text style={s.small}>我的金币</Text><View style={s.row}><Coin32 /><Text style={styles.walletBalance} numberOfLines={1} adjustsFontSizeToFit>{balance ?? '—'}</Text></View><Text style={[s.link, {fontSize: 12}]}>查看金币明细 ›</Text></View>
    <Image source={parkImage} style={styles.walletArt} resizeMode="cover" accessible={false} />
  </Pressable>;
}

/** 状态中文只映射服务端有限枚举；未知状态采用不可写提示。 */
export function taskLabel(task: ActivityTask): string {
  return ({AVAILABLE: '未领取', IN_PROGRESS: '进行中', READY: '已达成', CLAIMED: '已领取', REVERSED: '奖励已撤回', INTERRUPTED: '已中断', EXPIRED: '任务已过期', CLAIM_EXPIRED: '领奖已过期'} as Record<string, string>)[task.status] ?? '暂不可用';
}
/** 主动作只由 allowedActions 选择，不根据本地进度猜测可领奖。 */
export function actionLabel(task: ActivityTask): string {
  if (task.allowedActions.includes('LOGIN')) return '登录领取';
  if (task.allowedActions.includes('CLAIM')) return '领取金币';
  if (task.allowedActions.includes('ACCEPT')) return '领取任务';
  if (task.allowedActions.includes('COMPLETE')) return '去完成';
  return taskLabel(task);
}
/** 票券标题保持服务端原文；宽屏复现设计，窄屏缩小文字列而不覆盖操作区。 */
export function TaskCard({task, index, guest, onAction, onDetail}: {task: ActivityTask; index: number; guest: boolean; onAction: () => void; onDetail: () => void}) {
  const writable = task.allowedActions.some(action => ['LOGIN', 'CLAIM', 'ACCEPT', 'COMPLETE'].includes(action));
  const ready = task.status === 'READY';
  return <View style={[styles.ticket, {backgroundColor: index % 2 ? c.blue : c.peach}]} testID={`task-${task.definitionId}`}>
    <Pressable style={styles.ticketLeft} onPress={onDetail} accessibilityRole="button" accessibilityLabel={`${task.title}，查看任务详情`}>
      <View style={styles.ticketIcon}><Coin44 /></View><View style={styles.ticketCopy}>
        <Text style={styles.ticketTitle} numberOfLines={1}>{task.title}</Text>
        <Text style={s.tiny} numberOfLines={1}>{task.status === 'AVAILABLE' ? '先领取，再完成支付' : `${task.completed ?? 0}/${task.required ?? task.targetCount} ${ready ? '已完成，奖励等你拿' : '已完成'}`}</Text>
        <Text style={[s.tiny, {fontWeight: '700', color: ready ? c.green : c.orange}]} numberOfLines={1}>{guest ? '登录后查看进度' : `${ready ? '● ● ●' : '○'}  ${taskLabel(task)}`}</Text>
      </View>
    </Pressable><View style={styles.ticketRight}><Text style={styles.reward} numberOfLines={1}>+{task.rewardCoins} 金币</Text>
      <ActivityButton label={actionLabel(task)} onPress={onAction} disabled={!writable} secondary={index % 2 === 0 && !ready && task.status === 'AVAILABLE'} style={styles.action} />
    </View>
  </View>;
}

/** 路线按服务端天数生成，超出3站时在卡内横向滚动，不越过屏幕。 */
export function StreakDays({task, detail = false}: {task: ActivityTask; detail?: boolean}) {
  const count = Math.max(1, Math.min(31, task.dayCount));
  const content = Array.from({length: count}, (_, index) => {
    const day = task.days?.[index];const completed = day?.completed === true;
    return <React.Fragment key={day?.date ?? index}>
      {index > 0 && !detail && <Text style={styles.connector}>—</Text>}
      <View style={[detail ? styles.dayDetail : styles.dayPill, count <= 3 ? s.grow : {width: detail ? 103 : 99}, {backgroundColor: completed || (!day && index < count - 1) ? c.mint : c.yellow}]}>
        <Text style={[detail ? styles.daySymbol : styles.dayText, {color: completed || (!day && index < count - 1) ? c.green : c.gold}]}>{detail ? completed ? '✓' : `${index + 1}` : `${completed ? '✓ ' : ''}第${index + 1}天`}</Text>
        {detail && <><Text style={[s.small, s.center, {fontWeight: '700'}]}>{day ? shortDate(day.date) : `第${index + 1}天`}</Text><Text style={[s.tiny, s.center]}>{completed ? '已完成' : day ? `${day.count}/${day.target} 单` : '待开始'}</Text></>}
      </View>
    </React.Fragment>;
  });
  return count <= 3 ? <View style={[styles.days, detail && {gap: 10, height: 102}]}>{content}</View> : <ScrollView horizontal showsHorizontalScrollIndicator contentContainerStyle={[styles.days, detail && {gap: 10, height: 102}]}>{content}</ScrollView>;
}

/** 连续任务卡整块详情入口与独立动作分开，防止点主按钮时冒泡成第二次请求。 */
export function StreakCard({task, onAction, onDetail}: {task: ActivityTask; onAction: () => void; onDetail: () => void}) {
  const writable = task.allowedActions.some(action => ['LOGIN', 'CLAIM', 'ACCEPT', 'COMPLETE'].includes(action));
  // 标题与路线共用大于44dp的点击区，保持确认稿间距且避免独立小目标难以点击。
  return <View style={styles.streak}><Pressable onPress={onDetail} accessibilityRole="button" accessibilityLabel={`${task.title}，查看连续任务`} style={{gap: 4, minHeight: 58}}><View style={s.row}>
    <Text style={[styles.ticketTitle, s.grow]} numberOfLines={1}>{task.title}</Text><Text style={[styles.reward, {color: c.violet}]}>+{task.rewardCoins} 金币</Text>
  </View><StreakDays task={task} /></Pressable>
    <View style={[s.row, {minHeight: 44}]}><Text style={[s.tiny, s.grow]}>{task.status === 'AVAILABLE' ? '领取当天开始计算' : `已完成 ${task.completed ?? 0}/${task.dayCount} 天 · ${taskLabel(task)}`}</Text>
      <ActivityButton label={actionLabel(task)} onPress={onAction} disabled={!writable} style={styles.action} /></View>
  </View>;
}

/** 日期文本是后端业务日期，直接拆分，避免手机所在时区将日期移动到前一天。 */
export function shortDate(date: string): string {const parts = date.split('-');return parts.length === 3 ? `${Number(parts[1])}月${Number(parts[2])}日` : date;}
/** 时间展示固定北京时间；只格式化，不参与资格判断。 */
export function businessTime(value?: string): string {
  if (!value) return '—';const date = new Date(value);if (!Number.isFinite(date.getTime())) return '—';
  return new Intl.DateTimeFormat('zh-CN', {timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false}).format(date);
}

/** 对照 Figma 高度和间距，使用最小高度保留大字体可访问性，不裁掉动态业务文字。 */
const styles = StyleSheet.create({
  button: {minHeight: 44, borderRadius: 12, paddingHorizontal: 8, alignItems: 'center', justifyContent: 'center'},
  buttonText: {fontSize: 14, lineHeight: 21, fontWeight: '500', textAlign: 'center', includeFontPadding: false},
  hero: {height: 250, borderRadius: 20, overflow: 'hidden', backgroundColor: c.yellow}, heroTop: {height: 198, padding: 12, flexDirection: 'row'},
  heroCopy: {width: 127, justifyContent: 'center', gap: 2}, heroArt: {height: 174, flex: 1, borderRadius: 12},
  tagline: {fontSize: 10, lineHeight: 14, fontWeight: '700', color: c.orange, includeFontPadding: false}, heroTitle: {fontSize: 25, lineHeight: 35, fontWeight: '700', color: c.ink, includeFontPadding: false},
  walletStrip: {height: 52, paddingHorizontal: 12, backgroundColor: c.peach, flexDirection: 'row', gap: 8, alignItems: 'center'}, stripBalance: {width: 70, fontSize: 25, lineHeight: 35, fontWeight: '700', color: c.ink, includeFontPadding: false},
  banner: {height: 136, borderRadius: 20, paddingHorizontal: 16, paddingVertical: 12, flexDirection: 'row', gap: 8, backgroundColor: c.yellow}, bannerCopy: {justifyContent: 'center', gap: 4}, bannerArt: {width: 144, height: 112, borderRadius: 12},
  wallet: {height: 108, borderRadius: 20, paddingHorizontal: 16, paddingVertical: 12, flexDirection: 'row', gap: 8, backgroundColor: c.peach}, walletBalance: {fontSize: 27, lineHeight: 38, fontWeight: '700', color: c.ink, flex: 1, includeFontPadding: false}, walletArt: {width: 100, height: 84, borderRadius: 12},
  ticket: {minHeight: 92, borderRadius: 20, padding: 12, gap: 12, flexDirection: 'row', alignItems: 'center'}, ticketLeft: {flex: 1, minWidth: 0, flexDirection: 'row', gap: 10, alignItems: 'center', minHeight: 68},
  ticketIcon: {width: 44, height: 52}, ticketCopy: {flex: 1, minWidth: 0, gap: 3, minHeight: 64}, ticketTitle: {fontSize: 15, lineHeight: 21, fontWeight: '700', color: c.ink, includeFontPadding: false},
  ticketRight: {width: 104, gap: 3}, reward: {fontSize: 14, lineHeight: 20, fontWeight: '700', color: c.orange, textAlign: 'center', includeFontPadding: false}, action: {width: 104},
  streak: {minHeight: 130, paddingHorizontal: 12, paddingVertical: 10, gap: 4, borderRadius: 20, backgroundColor: c.purple},
  days: {flexDirection: 'row', height: 32, alignItems: 'center'}, dayPill: {height: 30, padding: 4, borderRadius: 15, justifyContent: 'center'},
  dayText: {fontSize: 12, lineHeight: 17, fontWeight: '700', textAlign: 'center', includeFontPadding: false}, connector: {width: 20, textAlign: 'center', color: c.violet},
  dayDetail: {height: 102, padding: 8, gap: 4, borderRadius: 16}, daySymbol: {fontSize: 24, lineHeight: 34, fontWeight: '700', textAlign: 'center', includeFontPadding: false},
});
