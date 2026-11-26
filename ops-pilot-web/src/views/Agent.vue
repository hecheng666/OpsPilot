<template>
  <div class="agent-page">
    <div class="panel-bar">
      <div>
        <div class="page-title">Agent 排查</div>
        <div class="page-sub">ReAct 多步循环：工具调用 → 证据（E-xxxx）→ 结构化根因报告，危险动作只提议不执行</div>
      </div>
      <div class="tools-line">
        <span class="dim">工具</span>
        <el-tag v-for="t in tools" :key="t.name" size="small" effect="plain"
                :type="t.riskLevel === 'CRITICAL' ? 'danger' : t.riskLevel === 'WRITE' ? 'warning' : 'success'">
          {{ t.name }}
        </el-tag>
      </div>
    </div>

    <div class="layout">
      <div class="left">
        <div class="panel">
          <div class="panel-title">发起排查</div>
          <el-select v-model="incidentId" placeholder="选择关联告警（可选）" clearable size="default" style="width: 100%">
            <el-option v-for="i in incidents" :key="i.id" :label="`#${i.id} ${i.title}`" :value="i.id" />
          </el-select>
          <el-input v-model="input" type="textarea" :rows="4" class="mt"
                    placeholder="补充排查线索，如：14:20 起订单列表接口变慢，DB RT 飙升" />
          <el-button type="primary" class="mt" :loading="running" style="width: 100%" @click="start">
            {{ running ? '排查中…' : '开始排查（SSE 实时）' }}
          </el-button>
          <div v-if="degraded" class="degraded">
            模型不可用（未拉取 {{ chatHint }}），本次为规则引擎降级报告
          </div>
        </div>

        <div class="panel">
          <div class="panel-title">运行历史</div>
          <div v-for="r in runs" :key="r.id" class="run-row" @click="loadRun(r.id)">
            <span class="mono">#{{ r.id }}</span>
            <el-tag size="small" :type="r.status === 'DONE' ? 'success' : r.status === 'RUNNING' ? 'warning' : 'danger'"
                    effect="plain">{{ r.status }}</el-tag>
            <el-tag v-if="r.degraded" size="small" type="info" effect="plain">降级</el-tag>
            <span class="dim mono">{{ r.latencyMs }}ms</span>
            <span class="dim">{{ (r.input || '').substring(0, 18) }}</span>
          </div>
          <div v-if="!runs.length" class="dim">暂无运行记录</div>
        </div>
      </div>

      <div class="right">
        <div class="panel">
          <div class="panel-title">执行步骤</div>
          <StepTimeline :steps="steps" />
        </div>

        <div class="panel">
          <div class="panel-title">诊断报告</div>
          <ReportView :report="report" />
        </div>

        <div class="panel">
          <div class="panel-title">证据链（{{ evidences.length }}）</div>
          <EvidenceCard v-for="e in evidences" :key="e.id" :evidence="e" />
          <div v-if="!evidences.length" class="dim">排查完成后展示证据</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import ElMessage from 'element-plus/es/components/message/index'
import StepTimeline from '../components/StepTimeline.vue'
import ReportView from '../components/ReportView.vue'
import EvidenceCard from '../components/EvidenceCard.vue'
import api from '../api'

const route = useRoute()
const incidents = ref([])
const tools = ref([])
const runs = ref([])
const incidentId = ref(null)
const input = ref('')
const steps = ref([])
const report = ref(null)
const evidences = ref([])
const running = ref(false)
const degraded = ref(false)
const chatHint = ref('qwen2.5:3b')
let es = null

const loadBasics = async () => {
  try {
    const [inc, tl, rn] = await Promise.all([api.incidents('OPEN'), api.tools(), api.agentRuns()])
    incidents.value = inc.data || []
    tools.value = tl.data || []
    runs.value = rn.data || []
  } catch (e) { /* ignore */ }
  const q = route.query.incidentId
  if (q) incidentId.value = Number(q)
}

const start = () => {
  if (running.value) return
  steps.value = []
  report.value = null
  evidences.value = []
  degraded.value = false
  running.value = true

  const params = new URLSearchParams()
  if (incidentId.value) params.set('incidentId', incidentId.value)
  if (input.value.trim()) params.set('input', input.value.trim())
  es = new EventSource('/api/agent/investigate/stream?' + params.toString())
  let finished = false

  es.addEventListener('step', (ev) => {
    try { steps.value.push(JSON.parse(ev.data)) } catch (e) { /* ignore */ }
  })
  es.addEventListener('report', (ev) => {
    try { report.value = JSON.parse(ev.data) } catch (e) { /* ignore */ }
  })
  es.addEventListener('done', async (ev) => {
    finished = true
    try {
      const d = JSON.parse(ev.data)
      degraded.value = !!d.degraded
      if (d.runId) await loadRun(d.runId, true)
    } catch (e) { /* ignore */ }
    running.value = false
    es?.close()
    loadBasics()
  })
  es.addEventListener('error', () => {
    if (!finished) {
      ElMessage.error('排查流中断，请重试')
      running.value = false
    }
    es?.close()
  })
}

const loadRun = async (id, quiet = false) => {
  try {
    const res = await api.agentRun(id)
    const d = res.data || {}
    if (d.steps?.length) {
      steps.value = d.steps.map(s => ({
        stepNo: s.stepNo, type: s.type, toolName: s.toolName,
        summary: s.result, status: s.status, evidenceId: s.evidenceId, elapsedMs: s.elapsedMs
      }))
    }
    evidences.value = d.evidences || []
    if (d.report) report.value = d.report
    if (d.run) {
      degraded.value = d.run.degraded === 1
      if (!quiet) ElMessage.success('已加载运行 #' + id)
    }
  } catch (e) {
    ElMessage.error('加载运行记录失败')
  }
}

onMounted(() => {
  loadBasics()
  const rid = Number(route.query.runId)
  if (rid) loadRun(rid, true)
})
onUnmounted(() => es?.close())
</script>

<style scoped>
.agent-page { display: flex; flex-direction: column; gap: 16px; }
.panel-bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.page-title { font-size: 18px; font-weight: 700; }
.page-sub { font-size: 12px; color: var(--text-dim); margin-top: 4px; }
.tools-line { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; max-width: 60%; }
.layout { display: grid; grid-template-columns: 360px 1fr; gap: 16px; align-items: start; }
.left, .right { display: flex; flex-direction: column; gap: 16px; }
.mt { margin-top: 10px; }
.run-row {
  display: flex; align-items: center; gap: 8px; font-size: 12px;
  padding: 6px 4px; border-bottom: 1px dashed var(--panel-border); cursor: pointer;
}
.run-row:hover { background: rgba(79, 209, 197, 0.05); }
.degraded {
  margin-top: 10px; font-size: 12px; color: var(--warning); line-height: 1.6;
  background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.35);
  border-radius: 6px; padding: 8px 10px;
}
.dim { color: var(--text-dim); font-size: 12px; }
@media (max-width: 1200px) {
  .layout { grid-template-columns: 1fr; }
  .tools-line { max-width: 100%; }
}
</style>
