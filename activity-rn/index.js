// 原生 Fragment 按固定注册名挂载活动页面，避免在 JS 中重复创建底部导航。
import {AppRegistry} from 'react-native';
import ActivityApp from './src/ActivityApp';
AppRegistry.registerComponent('FandaziActivity', () => ActivityApp);
