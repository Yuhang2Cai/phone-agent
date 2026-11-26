// Figma SVG 由 Metro 转换为原生矢量组件；保留源文件尺寸，不以截图替代交互界面。
declare module '*.svg' {
  import type {FC} from 'react';
  import type {SvgProps} from 'react-native-svg';
  const Component: FC<SvgProps>;
  export default Component;
}
