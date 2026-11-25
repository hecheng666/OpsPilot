<template>
  <div class="faults">
    <div class="panel-bar">
      <div>
        <div class="page-title">故障演练台</div>
        <div class="page-sub">向被观测服务注入故障，验证 Agent 的发现 → 取证 → 定位 → 处置闭环</div>
      </div>
      <div class="active-line">
        <span class="dim">进行中：</span>
        <el-tag v-for="f in activeFaults" :key="f.type" type="warning" effect="dark" size="small">
          {{ f.type }} · 强度 {{ f.intensity }} · {{ f.durationSeconds }}s
        </el-tag>
        <span v-if="!activeFaults.length" class="dim">无</span>
        <el-button size="small" type="danger" plain :disabled="!activeFaults.length" @click="recoverAll">
          全部恢复
        </el-button>
      </div>
    </div>

    <div class="fault-grid">
      <div v-for="f in faults" :key="f.type" class="panel fault-card">
        <div class="fault-head">
          <div>
            <div class="fault-name">{{ f.label }}</div>
            <div class="fault-type mono">{{ f.type }}</div>
          </div>
          <el-tag :type="isActive(f.type) ? 'warning' : 'info'" effect="dark" size="small">
            {{ isActive(f.type) ? '演练中' : '未注入' }}
          </el-tag>
        </div>
        <div class="fault-desc">{{ f.desc }}</div>
        <ul class="effect">
          <li v-for="e in f.effects" :key="e">{{ e }}</li>
        </ul>
        <div class="controls">
          <div class="ctl-row">
            <span class="ctl-label">强度</span>
            <el-slider v-model="form[f.type].intensity" :min="1" :max="10" :step="1" size="small" />
            <span class="mono ctl-val">{{ form[f.type].intensity }}</span>
          </div>
          <div class="ctl-row">
            <span class="ctl-label">时长</span>
            <el-radio-group v-model="form[f.type].durationSeconds" size="small">
              <el-radio-button :value="90">90s</el-radio-button>
              <el-radio-button :value="180">180s</el-radio-button>
              <el-radio-button :value="300">300s</el-radio-button>
            </el-radio-group>
          </div>
        </div>
        <div class="btns">
          <el-button type="warning" :loading="busy === f.type" @click="inject(f.type)">注入故障</el-button>
          <el-button type="success" plain :disabled="!isActive(f.type)" @click="recover(f.type)">恢复</el-button>
        </div>
      </div>
    </div>

    <div class="metrics-row">
      <div class="panel">
        <div class="panel-title">演练效果实时指标（5s 刷新）</div>
        <div class="mini-grid">
          <MetricCard label="QPS" :value="num('qps')" unit="req/s" />
          <MetricCard label="P95" :value="num('p95')" unit="ms" :warn-at="500" :bad-at="800" />
          <MetricCard label="错误率" :value="num('errorRate')" unit="%" :warn-at="2" :bad-at="5" />
          <MetricCard label="缓存命中率" :value="num('cacheHitRate')" unit="%" :warn-at="80" :bad-at="60" reverse />
          <MetricCard label="DB RT" :value="num('dbRtMs')" unit="ms" :warn-at="300" :bad-at="500" />
          <MetricCard label="线程池" :value="num('threadActive')" :unit="'/' + num('threadMax')" :warn-at="13" :bad-at="16" />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import MetricCard from '../components/MetricCard.vue'
import api from '../api'

const faults = [
  {
    type: 'SLOW_SQL', label: '慢 SQL',
    desc: '列表查询退化为无索引 UPPER(remark) LIKE 全表扫描，高强度叠加深分页。',
    effects: ['dbRtMs 峰值升高', 'P95 延迟超过 800ms', '日志出现 SLOW_QUERY']
  },
  {
    type: 'CACHE_BREAKDOWN', label: '缓存击穿',
    desc: '模拟缓存层命中率从 ~95% 掉到 30%，请求穿透回源数据库。',
    effects: ['cacheHitRate 跌破 60%', '回源 DB QPS 放大', '日志出现 CACHE_MISS']
  },
  {
    type: 'THREAD_POOL_EXHAUSTION', label: '线程池打满',
    desc: '订单查询任务变慢，core 8 / max 16 / queue 50 的线程池被占满。',
    effects: ['threadActive 逼近 max', 'queue 积压、出现拒绝', 'P95 与错误率上升']
  },
  {
    type: 'DOWNSTREAM_TIMEOUT', label: '下游超时',
    desc: '内部 mock 下游接口按强度 sleep（300ms × 强度），30% 概率抛错。',
    effects: ['downstreamRtMs 升高', 'errorRate 超过 5%', '日志出现 DOWNSTREAM_ERROR']
  }
]

const form = reactive({})
for (const f of faults) {
  form[f.type] = { intensity: 5, durationSeconds: 180 }
}

const activeFaults = ref([])
const latest = ref({})
const busy = ref('')
let timer

const loadStatus = async () => {
  try {
    const res = await api.faultStatus()
    activeFaults.value = res.data?.faults || []
  } catch (e) { /* demo 不可达时忽略 */ }
}
const loadMetrics = async () => {
  try {
    const res = await api.overview()
    latest.value = res.data?.latest || {}
    if (!activeFaults.value.length) {
      activeFaults.value = res.data?.faults || []
    }
  } catch (e) { /* ignore */ }
}

const isActive = (type) => activeFaults.value.some(f => f.type === type)
const num = (k) => latest.value?.[k] ?? 0

const inject = async (type) => {
  busy.value = type
  try {
    const { intensity, durationSeconds } = form[type]
    const res = await api.injectFault(type, intensity, durationSeconds)
    ElMessage.success(res.data?.message || '注入成功')
    await loadStatus()
  } catch (e) {
    ElMessage.error('注入失败：' + (e.message || ''))
  } finally {
    busy.value = ''
  }
}
const recover = async (type) => {
  try {
    await api.recoverFault(type)
    ElMessage.success('已恢复 ' + type)
    await loadStatus()
  } catch (e) {
    ElMessage.error('恢复失败')
  }
}
const recoverAll = async () => {
  await api.recoverFault()
  ElMessage.success('已恢复全部故障')
  await loadStatus()
}

onMounted(() => {
  loadStatus(); loadMetrics()
  timer = setInterval(() => { loadMetrics(); loadStatus() }, 5000)
})
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.faults { display: flex; flex-direction: column; gap: 16px; }
.panel-bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; }
.page-sub { font-size: 12px; color: var(--text-dim); margin-top: 4px; }
.active-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.fault-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.fault-card { display: flex; flex-direction: column; gap: 10px; }
.fault-head { display: flex; justify-content: space-between; align-items: flex-start; }
.fault-name { font-size: 15px; font-weight: 700; }
.fault-type { font-size: 11px; color: var(--text-dim); margin-top: 2px; }
.fault-desc { font-size: 12px; color: var(--text-dim); line-height: 1.6; min-height: 56px; }
.effect { margin: 0; padding-left: 16px; font-size: 12px; color: #9fb0c3; line-height: 1.7; }
.controls { display: flex; flex-direction: column; gap: 8px; border-top: 1px dashed var(--panel-border); padding-top: 10px; }
.ctl-row { display: flex; align-items: center; gap: 10px; }
.ctl-label { font-size: 12px; color: var(--text-dim); width: 28px; }
.ctl-val { width: 20px; text-align: right; font-size: 12px; color: var(--primary); }
.ctl-row :deep(.el-slider) { flex: 1; }
.btns { display: flex; gap: 8px; margin-top: auto; }
.mini-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.dim { color: var(--text-dim); font-size: 12px; }
@media (max-width: 1400px) {
  .fault-grid { grid-template-columns: repeat(2, 1fr); }
  .mini-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>
