<template>
  <div class="eval-page">
    <div class="panel-bar">
      <div>
        <div class="page-title">评测中心</div>
        <div class="page-sub">20 场景 × 3 基线（规则 / RAG / 全工具），评测流程自动注入故障、等待采集、跑基线、评分并恢复</div>
      </div>
      <el-button size="small" :disabled="!detail.run" @click="exportJson">导出评测报告 JSON</el-button>
    </div>

    <div class="layout">
      <div class="left">
        <div class="panel">
          <div class="panel-title">评测配置</div>
          <div class="field">
            <div class="field-label">基线</div>
            <el-radio-group v-model="baseline" size="small">
              <el-radio-button value="RULE">RULE</el-radio-button>
              <el-radio-button value="RAG_ONLY">RAG_ONLY</el-radio-button>
              <el-radio-button value="FULL_AGENT">FULL_AGENT</el-radio-button>
            </el-radio-group>
          </div>
          <div class="field">
            <div class="field-label">单场景等待（秒）</div>
            <el-slider v-model="waitSeconds" :min="8" :max="30" :step="2" size="small" />
          </div>
          <div class="field">
            <div class="field-label">
              场景（{{ selected.length }}/{{ scenarios.length }}）
              <span class="links">
                <el-button link type="primary" size="small" @click="selectAll">全选</el-button>
                <el-button link size="small" @click="selectSample">抽样 3 条</el-button>
                <el-button link size="small" @click="selected = []">清空</el-button>
              </span>
            </div>
            <div class="scenario-list">
              <el-checkbox-group v-model="selected">
                <div v-for="s in scenarios" :key="s.id" class="scenario-row">
                  <el-checkbox :value="s.id">
                    <span class="scenario-name">{{ s.name }}</span>
                  </el-checkbox>
                  <span class="mono dim">{{ s.faultType }}</span>
                </div>
              </el-checkbox-group>
            </div>
          </div>
          <el-button type="primary" style="width: 100%" :loading="starting" :disabled="running" @click="start">
            {{ running ? '评测进行中…' : '启动评测' }}
          </el-button>
          <div v-if="progress && running" class="progress">
            <el-progress :percentage="progressPct" :stroke-width="8" :color="'#4fd1c5'" />
            <div class="progress-text">
              {{ progress.current }}/{{ progress.total }} · {{ progress.scenarioName }} · {{ progress.message }}
            </div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-title">历史评测</div>
          <div v-for="r in runs" :key="r.id" class="run-row" :class="{ selected: detail.run?.id === r.id }"
               @click="loadRun(r.id)">
            <span class="mono">#{{ r.id }}</span>
            <el-tag size="small" effect="dark" :type="baselineTag(r.baseline)">{{ r.baseline }}</el-tag>
            <el-tag size="small" effect="plain" :type="r.status === 'DONE' ? 'success' : r.status === 'RUNNING' ? 'warning' : 'danger'">
              {{ r.status }}
            </el-tag>
            <span class="mono dim">{{ r.hitTop1 }}/{{ r.total }}</span>
          </div>
          <div v-if="!runs.length" class="dim">暂无评测记录</div>
        </div>
      </div>

      <div class="right">
        <div class="panel" v-if="detail.run">
          <div class="panel-title">
            评测 #{{ detail.run.id }} · {{ detail.run.baseline }} · {{ detail.run.status }}
            <span class="dim mono"> {{ detail.run.startedAt }} → {{ detail.run.finishedAt || '…' }}</span>
          </div>
          <div class="stat-grid">
            <div class="stat"><div class="stat-label">Top-1 命中率</div><div class="stat-value">{{ pct(detail.run.hitTop1, detail.run.total) }}</div></div>
            <div class="stat"><div class="stat-label">Top-3 命中率</div><div class="stat-value">{{ pct(detail.run.hitTop3, detail.run.total) }}</div></div>
            <div class="stat"><div class="stat-label">平均步数</div><div class="stat-value">{{ detail.run.avgSteps }}</div></div>
            <div class="stat"><div class="stat-label">平均耗时</div><div class="stat-value">{{ detail.run.avgLatencyMs }}<span class="unit">ms</span></div></div>
            <div class="stat"><div class="stat-label">证据准确率</div><div class="stat-value">{{ Math.round((detail.run.evidenceAccuracy || 0) * 100) }}%</div></div>
            <div class="stat"><div class="stat-label">报告完整率</div><div class="stat-value">{{ Math.round((detail.run.reportCompleteness || 0) * 100) }}%</div></div>
            <div class="stat"><div class="stat-label">未授权写</div><div class="stat-value" :class="{ ok: detail.unauthorizedWrites === 0 }">{{ detail.unauthorizedWrites ?? 0 }}<span class="unit">次</span></div></div>
          </div>
        </div>

        <div class="panel">
          <div class="panel-title">基线对比（各基线最近一次完成的评测）</div>
          <div ref="chartEl" class="chart"></div>
        </div>

        <div class="panel">
          <div class="panel-title">逐场景结果</div>
          <el-table :data="detail.results || []" size="small" max-height="460">
            <el-table-column prop="scenarioName" label="场景" min-width="180" show-overflow-tooltip />
            <el-table-column prop="faultType" label="故障" width="180">
              <template #default="{ row }"><span class="mono dim">{{ row.faultType }}</span></template>
            </el-table-column>
            <el-table-column label="期望根因关键词" min-width="170">
              <template #default="{ row }">
                <span class="dim">{{ parseKeywords(row.expectedRootCause) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="detectedRootCause" label="检测根因" min-width="260" show-overflow-tooltip />
            <el-table-column label="命中" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="rankTag(row.hitRank)" effect="dark">{{ rankText(row.hitRank) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="steps" label="步数" width="70" />
            <el-table-column prop="latencyMs" label="耗时" width="90">
              <template #default="{ row }"><span class="mono">{{ row.latencyMs }}ms</span></template>
            </el-table-column>
            <el-table-column label="证据" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="row.evidenceOk ? 'success' : 'info'" effect="plain">
                  {{ row.evidenceOk ? '有' : '无' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="报告分" width="80">
              <template #default="{ row }"><span class="mono">{{ row.reportScore }}</span></template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import ElMessage from 'element-plus/es/components/message/index'
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

// 按需注册：柱状图 + 直角坐标系/提示框/图例 + Canvas 渲染
echarts.use([BarChart, GridComponent, TooltipComponent, LegendComponent, CanvasRenderer])
import api from '../api'

const scenarios = ref([])
const selected = ref([])
const baseline = ref('FULL_AGENT')
const waitSeconds = ref(12)
const runs = ref([])
const detail = ref({})
const progress = ref(null)
const running = ref(false)
const starting = ref(false)
const chartEl = ref(null)
let chart = null
let pollTimer = null

const progressPct = computed(() => {
  if (!progress.value?.total) return 0
  return Math.round((progress.value.current / progress.value.total) * 100)
})

const loadScenarios = async () => {
  const res = await api.evalScenarios()
  scenarios.value = res.data || []
  selected.value = scenarios.value.map(s => s.id)
}
const loadRuns = async () => {
  const res = await api.evalRuns(30)
  runs.value = res.data || []
  if (!detail.value.run && runs.value.length) await loadRun(runs.value[0].id)
  renderChart()
}
const loadRun = async (id) => {
  const res = await api.evalRun(id)
  detail.value = res.data || {}
  progress.value = detail.value.progress || null
  if (detail.value.run?.status === 'RUNNING') startPolling(id)
  await nextTick()
  renderChart()
}

const start = async () => {
  starting.value = true
  try {
    const res = await api.startEval({
      baseline: baseline.value,
      scenarioIds: selected.value,
      waitSeconds: waitSeconds.value
    })
    if (!res.data?.success) {
      ElMessage.error(res.data?.summary || '启动失败')
      return
    }
    ElMessage.success('评测已启动：#' + res.data.evalRunId)
    running.value = true
    await loadRun(res.data.evalRunId)
    startPolling(res.data.evalRunId)
  } catch (e) {
    ElMessage.error('启动失败：' + (e.response?.data?.summary || e.message))
  } finally {
    starting.value = false
  }
}

const startPolling = (id) => {
  clearInterval(pollTimer)
  pollTimer = setInterval(async () => {
    try {
      const res = await api.evalRun(id)
      detail.value = res.data || {}
      progress.value = detail.value.progress || null
      if (detail.value.run?.status !== 'RUNNING') {
        clearInterval(pollTimer)
        running.value = false
        await loadRuns()
        await loadRun(id)
      }
    } catch (e) { /* ignore */ }
  }, 3000)
}

const renderChart = () => {
  if (!chartEl.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  const baselines = ['RULE', 'RAG_ONLY', 'FULL_AGENT']
  const colors = { RULE: '#8a94a3', RAG_ONLY: '#7ea6f4', FULL_AGENT: '#4fd1c5' }
  const latest = {}
  for (const b of baselines) {
    const r = runs.value.find(x => x.baseline === b && x.status === 'DONE' && x.total > 0)
    if (r) latest[b] = r
  }
  const series = baselines.filter(b => latest[b]).map(b => {
    const r = latest[b]
    return {
      name: b, type: 'bar', barWidth: 18,
      itemStyle: { color: colors[b], borderRadius: [4, 4, 0, 0] },
      data: [pctNum(r.hitTop1, r.total), pctNum(r.hitTop3, r.total)]
    }
  })
  chart.setOption({
    backgroundColor: 'transparent',
    grid: { left: 44, right: 16, top: 36, bottom: 24 },
    tooltip: { trigger: 'axis', backgroundColor: '#1c232b', borderColor: '#2a323c', textStyle: { color: '#d6dde6', fontSize: 12 } },
    legend: { textStyle: { color: '#8a94a3', fontSize: 12 }, top: 0 },
    xAxis: { type: 'category', data: ['Top-1 命中率', 'Top-3 命中率'], axisLine: { lineStyle: { color: '#2a323c' } }, axisLabel: { color: '#8a94a3' } },
    yAxis: { type: 'value', max: 100, splitLine: { lineStyle: { color: '#232a33' } }, axisLabel: { color: '#8a94a3', formatter: '{value}%' } },
    series
  }, true)
}

const pctNum = (a, b) => (b ? Math.round((a / b) * 1000) / 10 : 0)
const pct = (a, b) => (b ? Math.round((a / b) * 100) + '%' : '0%')
const baselineTag = (b) => ({ RULE: 'info', RAG_ONLY: 'primary', FULL_AGENT: 'success' }[b] || 'info')
const rankText = (r) => (r === 1 ? 'Top-1' : r === 2 ? 'Top-2' : r === 3 ? 'Top-3' : '未命中')
const rankTag = (r) => (r === 1 ? 'success' : r === 2 ? 'primary' : r === 3 ? 'warning' : 'danger')
const parseKeywords = (json) => {
  try { return JSON.parse(json).join(' / ') } catch (e) { return json }
}

const selectAll = () => { selected.value = scenarios.value.map(s => s.id) }
const selectSample = () => { selected.value = scenarios.value.filter(s => [1, 6, 11].includes(s.id)).map(s => s.id) }

const exportJson = () => {
  if (!detail.value.run) return
  const blob = new Blob([JSON.stringify(detail.value, null, 2)], { type: 'application/json' })
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = `ops-pilot-eval-${detail.value.run.id}-${detail.value.run.baseline}.json`
  a.click()
  URL.revokeObjectURL(a.href)
}

const resize = () => chart?.resize()
onMounted(async () => {
  await Promise.all([loadScenarios(), loadRuns()])
  window.addEventListener('resize', resize)
  if (detail.value.run?.status === 'RUNNING') {
    running.value = true
    startPolling(detail.value.run.id)
  }
})
onUnmounted(() => {
  clearInterval(pollTimer)
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
watch(() => detail.value.run?.status, (s) => { running.value = s === 'RUNNING' })
</script>

<style scoped>
.eval-page { display: flex; flex-direction: column; gap: 16px; }
.panel-bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; }
.page-sub { font-size: 12px; color: var(--text-dim); margin-top: 4px; }
.layout { display: grid; grid-template-columns: 380px 1fr; gap: 16px; align-items: start; }
.left, .right { display: flex; flex-direction: column; gap: 16px; }
.field { margin-bottom: 14px; }
.field-label { font-size: 12px; color: var(--text-dim); margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; }
.links { display: flex; gap: 2px; }
.scenario-list { max-height: 300px; overflow: auto; border: 1px solid var(--panel-border); border-radius: 6px; padding: 8px 10px; }
.scenario-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 2px 0; }
.scenario-name { font-size: 12px; }
.progress { margin-top: 12px; }
.progress-text { font-size: 12px; color: var(--text-dim); margin-top: 6px; }
.run-row { display: flex; align-items: center; gap: 8px; font-size: 12px; padding: 6px 4px; cursor: pointer; border-bottom: 1px dashed var(--panel-border); }
.run-row.selected { background: rgba(79, 209, 197, 0.08); }
.stat-grid { display: grid; grid-template-columns: repeat(7, 1fr); gap: 10px; }
.stat { background: #12171d; border: 1px solid var(--panel-border); border-radius: 6px; padding: 10px; }
.stat-label { font-size: 11px; color: var(--text-dim); }
.stat-value { font-size: 20px; font-weight: 700; font-family: var(--mono); margin-top: 6px; }
.stat-value.ok { color: var(--primary); }
.unit { font-size: 11px; color: var(--text-dim); margin-left: 3px; }
.chart { width: 100%; height: 240px; }
.dim { color: var(--text-dim); font-size: 12px; }
@media (max-width: 1400px) {
  .layout { grid-template-columns: 1fr; }
  .stat-grid { grid-template-columns: repeat(4, 1fr); }
}
</style>
