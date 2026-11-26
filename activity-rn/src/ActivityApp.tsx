// 活动 RN 根页面：连接原生会话与生命周期，渲染确认稿页面；系统栏和五个Tab仍由原生负责。
import React, {useEffect, useState, useSyncExternalStore} from 'react';
import {AppState, BackHandler, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View} from 'react-native';
import {activityHost, activitySession as session, observeAndroidHost, connectAndroidHost} from './host/AndroidActivityHost';
import {ActivityButton} from './components/ActivityComponents';
import {ActivityHomeScreen, TaskDetailScreen, CoinHistoryScreen, ActivityRulesScreen, RewardFeedbackScreen, TaskRecordsScreen} from './screens/ActivityScreens';
import {colors as c, common as s} from './theme/activityTheme';

/** 页面订阅状态机；返回键先处理RN内部薄路由，首页返回时交还宿主。 */
export default function ActivityApp() {
  const state = useSyncExternalStore(session.subscribe, session.getSnapshot);
  const [laidOut, setLaidOut] = useState(false);
  // 以实际布局后的两帧作为本地结构可交互终点，不等待网络数据，也不把JS启动当作渲染完成。
  useEffect(() => {
    if (!laidOut || !state.host.focused || !state.host.entryId) return;
    let second = 0;
    const first = requestAnimationFrame(() => {second = requestAnimationFrame(() => activityHost.reportInteractive(state.host.generation, state.host.entryId!));});
    return () => {cancelAnimationFrame(first);cancelAnimationFrame(second);};
  }, [laidOut, state.host.focused, state.host.generation, state.host.entryId]);
  useEffect(() => {
    const stop = observeAndroidHost();
    const back = BackHandler.addEventListener('hardwareBackPress', () => session.getSnapshot().host.focused && session.back());
    const lifecycle = AppState.addEventListener('change', next => {if (next === 'active') void connectAndroidHost(true);});
    return () => {stop();back.remove();lifecycle.remove();};
  }, []);
  const root = state.route === 'home' || (state.route === 'feedback' && state.feedback.phase === 'success');
  const title = ({home: '活动', detail: state.detail?.type === 'DAILY_ORDER_COUNT' ? '任务详情' : '连续任务', history: '金币明细', rules: '活动规则', records: '任务记录',
    feedback: state.feedback.phase === 'success' ? '活动' : state.feedback.action === 'accept' ? '领取任务' : '领取金币'})[state.route];
  const compact = state.route === 'home' && !state.error && state.tasks.length > 0;
  return <View style={s.page} testID="activity-rn-root" onLayout={() => setLaidOut(true)}>
    <View style={styles.header}>
      {root ? <Text style={s.title}>{title}</Text> : <Pressable accessibilityRole="button" accessibilityLabel={`返回，${title}`} style={styles.back} onPress={() => {if (!session.back()) session.home();}}><Text style={s.title}>‹  {title}</Text></Pressable>}
      {root && <ActivityButton label="规则" secondary style={styles.rules} onPress={() => session.open('rules')} />}
    </View>
    <ScrollView key={`${state.host.generation}:${state.route}`} testID="activity-scroll" contentContainerStyle={compact ? s.homeBody : s.body}
      contentOffset={{x: 0, y: state.route === 'home' ? session.scrollOffset : 0}} scrollEventThrottle={32}
      onScroll={event => {if (state.route === 'home') session.scrollOffset = event.nativeEvent.contentOffset.y;}}
      refreshControl={state.route === 'home' ? <RefreshControl refreshing={state.loading && state.tasks.length > 0} tintColor={c.orange} colors={[c.orange]} onRefresh={() => {void connectAndroidHost(true);}} /> : undefined}>
      {state.route === 'home' && <ActivityHomeScreen state={state} session={session} />}
      {state.route === 'detail' && <TaskDetailScreen state={state} session={session} />}
      {state.route === 'history' && <CoinHistoryScreen state={state} session={session} />}
      {state.route === 'rules' && <ActivityRulesScreen />}
      {state.route === 'feedback' && <RewardFeedbackScreen state={state} session={session} />}
      {state.route === 'records' && <TaskRecordsScreen state={state} session={session} />}
    </ScrollView>
  </View>;
}

/** 标题区沿用r2的56dp高度；返回与规则按钮点击区域均不小于44dp。 */
const styles = StyleSheet.create({header: {height: 56, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between'},
  back: {minHeight: 44, justifyContent: 'center', flex: 1}, rules: {width: 64}});
