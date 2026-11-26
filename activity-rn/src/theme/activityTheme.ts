// 活动视觉令牌：来自已确认 Figma r2；原生底栏使用同一套颜色和矢量资产。
import {StyleSheet} from 'react-native';
/** 语义色不承载业务事实，状态和余额仍由接口决定。 */
export const colors = {cream: '#FFF9EC', ink: '#3D3152', muted: '#74677C', yellow: '#FFF0B5', orange: '#C64B22',
  peach: '#FFE5D7', blue: '#DFF1FF', purple: '#ECE3FF', violet: '#7652B8', mint: '#DDF3E6', green: '#327957',
  gold: '#A3630A', pink: '#FADCE7', white: '#FFFFFF', disabled: '#EBEDE8', forest: '#092E29', secondary: '#6B7585'};
/** 公共排版保持设计字号与行高；字体使用 Android 系统中文回退，关闭额外字体内边距。 */
export const common = StyleSheet.create({
  page: {flex: 1, backgroundColor: colors.cream}, body: {padding: 16, gap: 12}, homeBody: {paddingHorizontal: 16, paddingVertical: 8, gap: 8},
  card: {borderRadius: 20, padding: 16, gap: 8}, row: {flexDirection: 'row', alignItems: 'center', gap: 8}, grow: {flex: 1, minWidth: 0},
  title: {fontSize: 22, lineHeight: 33, fontWeight: '700', color: colors.ink, includeFontPadding: false},
  section: {fontSize: 16, lineHeight: 22, fontWeight: '700', color: colors.ink, includeFontPadding: false},
  bodyText: {fontSize: 14, lineHeight: 21, color: colors.secondary, includeFontPadding: false},
  small: {fontSize: 12, lineHeight: 18, color: colors.muted, includeFontPadding: false},
  tiny: {fontSize: 11, lineHeight: 15, color: colors.muted, includeFontPadding: false},
  center: {textAlign: 'center'}, link: {fontSize: 13, lineHeight: 18, color: colors.orange, fontWeight: '700', includeFontPadding: false},
});
