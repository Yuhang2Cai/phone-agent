// 打包配置：SVG 使用原始 Figma 文件，由官方兼容转换器生成原生矢量组件。
const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');
const defaults = getDefaultConfig(__dirname);
module.exports = mergeConfig(defaults, {
  transformer: {babelTransformerPath: require.resolve('react-native-svg-transformer/react-native')},
  resolver: {
    assetExts: defaults.resolver.assetExts.filter(extension => extension !== 'svg'),
    sourceExts: [...defaults.resolver.sourceExts, 'svg'],
  },
});
