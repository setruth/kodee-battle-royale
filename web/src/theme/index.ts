import type { GlobalThemeOverrides } from 'naive-ui'

/** Kotlin 紫主题，覆盖 naive-ui 暗色主题 */
export const themeOverrides: GlobalThemeOverrides = {
  common: {
    primaryColor: '#7f52ff',
    primaryColorHover: '#9d78ff',
    primaryColorPressed: '#6a3fe0',
    primaryColorSuppl: '#7f52ff',
    bodyColor: '#12101c',
    cardColor: '#1b1829',
    modalColor: '#1b1829',
    popoverColor: '#241f38',
    borderRadius: '10px',
    fontFamily: `'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, sans-serif`,
  },
  Button: {
    textColorPrimary: '#ffffff',
    textColorHoverPrimary: '#ffffff',
    textColorPressedPrimary: '#ffffff',
    textColorFocusPrimary: '#ffffff',
  },
  Tabs: {
    tabTextColorLine: '#9a93b8',
    tabTextColorActiveLine: '#a78bfa',
    barColor: '#7f52ff',
  },
}
