import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 组件按需深路径导入：绕开 element-plus 根入口的全量 installer，保证 tree-shaking 生效
import ElButton from 'element-plus/es/components/button/index'
import ElInput from 'element-plus/es/components/input/index'
import ElSelect, { ElOption } from 'element-plus/es/components/select/index'
import ElTable, { ElTableColumn } from 'element-plus/es/components/table/index'
import ElTag from 'element-plus/es/components/tag/index'
import ElProgress from 'element-plus/es/components/progress/index'
import ElCollapse, { ElCollapseItem } from 'element-plus/es/components/collapse/index'
import ElTimeline, { ElTimelineItem } from 'element-plus/es/components/timeline/index'
import ElDrawer from 'element-plus/es/components/drawer/index'
import ElSlider from 'element-plus/es/components/slider/index'
import ElCheckbox, { ElCheckboxGroup } from 'element-plus/es/components/checkbox/index'
import { ElRadioGroup, ElRadioButton } from 'element-plus/es/components/radio/index'
import ElIcon from 'element-plus/es/components/icon/index'
import ElConfigProvider from 'element-plus/es/components/config-provider/index'
import ElMessage from 'element-plus/es/components/message/index'
import ElMessageBox from 'element-plus/es/components/message-box/index'

// 按需引入组件样式：每个入口会自动带上其依赖组件的样式
import 'element-plus/es/components/button/style/css'
import 'element-plus/es/components/input/style/css'
import 'element-plus/es/components/select/style/css'
import 'element-plus/es/components/table/style/css'
import 'element-plus/es/components/tag/style/css'
import 'element-plus/es/components/progress/style/css'
import 'element-plus/es/components/collapse/style/css'
import 'element-plus/es/components/collapse-item/style/css'
import 'element-plus/es/components/timeline/style/css'
import 'element-plus/es/components/timeline-item/style/css'
import 'element-plus/es/components/drawer/style/css'
import 'element-plus/es/components/slider/style/css'
import 'element-plus/es/components/checkbox/style/css'
import 'element-plus/es/components/checkbox-group/style/css'
import 'element-plus/es/components/radio-group/style/css'
import 'element-plus/es/components/radio-button/style/css'
import 'element-plus/es/components/icon/style/css'
import 'element-plus/es/components/message/style/css'
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import router from './router'
import './styles.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)

// 只注册实际用到的组件，避免 Element Plus 全量打包
const components = [
  ElButton,
  ElCheckbox,
  ElCheckboxGroup,
  ElCollapse,
  ElCollapseItem,
  ElConfigProvider,
  ElDrawer,
  ElIcon,
  ElInput,
  ElOption,
  ElProgress,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElSlider,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTimeline,
  ElTimelineItem
]
components.forEach((c) => app.component(c.name, c))

// 注册服务式组件，让 ElMessage / ElMessageBox 能读到 ElConfigProvider 的语言配置
app.use(ElMessage)
app.use(ElMessageBox)

app.mount('#app')
