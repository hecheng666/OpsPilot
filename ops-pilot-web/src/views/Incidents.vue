<template>
  <div class="incidents">
    <div class="panel-bar">
      <div>
        <div class="page-title">事故中心</div>
        <div class="page-sub">告警合并后的事故、Agent 排查报告、证据链与待审批处置</div>
      </div>
      <div class="filters">
        <el-radio-group v-model="statusFilter" size="small" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="OPEN">进行中</el-radio-button>
          <el-radio-button value="CLOSED">已关闭</el-radio-button>
        </el-radio-group>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table :data="rows" size="small" @row-click="open" row-class-name="clickable">
        <el-table-column prop="id" label="#" width="60" />
        <el-table-column label="严重度" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.severity === 'P1' ? 'danger' : 'warning'" effect="dark">
              {{ row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="告警标题" min-width="300" show-overflow-tooltip />
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'info'" effect="plain">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="rootCause" label="Agent 根因" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <span :class="{ dim: !row.rootCause }">{{ row.rootCause || '未排查' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="startedAt" label="开始时间" width="170" />
        <el-table-column label="操作" width="150">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click.stop="goAgent(row.id)">Agent 排查</el-button>
            <el-button link size="small" @click.stop="open(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-drawer v-model="drawer" size="720px" :title="'事故 #' + (detail.incident?.id || '')" destroy-on-close>
      <div v-if="detail.incident" class="drawer-body">
        <div class="inc-head">
          <div>
            <div class="inc-title">{{ detail.incident.title }}</div>
            <div class="inc-meta mono">
              {{ detail.incident.severity }} · {{ detail.incident.status }} ·
              开始 {{ detail.incident.startedAt }}
              <span v-if="detail.incident.resolvedAt"> · 恢复 {{ detail.incident.resolvedAt }}</span>
            </div>
          </div>
          <el-button v-if="detail.incident.status === 'OPEN'" size="small" type="success" plain
                     @click="closeIncident">关闭事故</el-button>
        </div>

        <div class="section">
          <div class="section-title">待审批处置</div>
          <div v-if="!detail.actions?.length" class="dim">暂无处置动作</div>
          <div v-for="a in detail.actions" :key="a.id" class="action-row">
            <el-tag size="small" type="warning" effect="dark">#{{ a.id }}</el-tag>
            <span class="mono action-type">{{ a.actionType }}</span>
            <el-tag size="small" :type="riskTag(a.riskLevel)" effect="plain">{{ a.riskLevel }}</el-tag>
            <span class="action-reason">{{ a.reason }}</span>
            <span class="status-tag">
              <el-tag size="small" :type="statusTagType(a.status)" effect="plain">{{ a.status }}</el-tag>
            </span>
            <template v-if="a.status === 'PENDING'">
              <el-button size="small" type="success" @click="approve(a)">批准并执行</el-button>
              <el-button size="small" type="danger" plain @click="reject(a)">驳回</el-button>
            </template>
          </div>
        </div>

        <div class="section">
          <div class="section-title">Agent 报告</div>
          <ReportView :report="latestReport" />
        </div>

        <div class="section">
          <div class="section-title">证据链（最近一次排查）</div>
          <div v-if="!detail.evidences?.length" class="dim">暂无证据，点击「Agent 排查」生成</div>
          <EvidenceCard v-for="e in detail.evidences" :key="e.id" :evidence="e" />
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import ReportView from '../components/ReportView.vue'
import EvidenceCard from '../components/EvidenceCard.vue'
import api from '../api'

const router = useRouter()
const rows = ref([])
const statusFilter = ref('')
const drawer = ref(false)
const detail = ref({})

const load = async () => {
  try {
    const res = await api.incidents(statusFilter.value || undefined)
    rows.value = res.data || []
  } catch (e) {
    ElMessage.error('加载事故列表失败')
  }
}

const open = async (row) => {
  drawer.value = true
  detail.value = {}
  try {
    const res = await api.incident(row.id)
    detail.value = res.data || {}
  } catch (e) {
    ElMessage.error('加载事故详情失败')
  }
}

const latestReport = computed(() => {
  const runs = detail.value.runs || []
  const run = runs.find(r => r.report)
  if (!run) return null
  try { return JSON.parse(run.report) } catch (e) { return null }
})

const goAgent = (id) => router.push({ path: '/agent', query: { incidentId: id } })

const riskTag = (r) => (r === 'HIGH' ? 'danger' : r === 'LOW' ? 'success' : 'warning')
const statusTagType = (s) => ({
  PENDING: 'warning', APPROVED: 'primary', EXECUTED: 'success',
  REJECTED: 'info', FAILED: 'danger'
}[s] || 'info')

const approve = async (a) => {
  try {
    await ElMessageBox.confirm(`批准并执行动作 #${a.id}（${a.actionType}）？`, '人工审批', { type: 'warning' })
    const res = await api.approveAction(a.id)
    if (res.data?.success) {
      ElMessage.success(res.data.execute?.summary || '已批准并执行')
      await open(detail.value.incident)
    } else {
      ElMessage.error(res.data?.summary || '操作失败')
    }
  } catch (e) { /* 用户取消 */ }
}

const reject = async (a) => {
  try {
    const { value } = await ElMessageBox.prompt('请填写驳回原因', '驳回处置', { inputPlaceholder: '如：影响面过大，需评估' })
    await api.rejectAction(a.id, value || '')
    ElMessage.success('已驳回')
    await open(detail.value.incident)
  } catch (e) { /* 用户取消 */ }
}

const closeIncident = async () => {
  try {
    await api.closeIncident(detail.value.incident.id)
    ElMessage.success('事故已关闭')
    await open(detail.value.incident)
    await load()
  } catch (e) {
    ElMessage.error('关闭失败')
  }
}

onMounted(load)
</script>

<style scoped>
.incidents { display: flex; flex-direction: column; gap: 16px; }
.panel-bar { display: flex; align-items: flex-end; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.page-title { font-size: 18px; font-weight: 700; }
.page-sub { font-size: 12px; color: var(--text-dim); margin-top: 4px; }
.filters { display: flex; align-items: center; gap: 10px; }
:deep(.clickable) { cursor: pointer; }
.drawer-body { display: flex; flex-direction: column; gap: 18px; }
.inc-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
.inc-title { font-size: 15px; font-weight: 700; }
.inc-meta { font-size: 12px; color: var(--text-dim); margin-top: 6px; }
.section-title { font-size: 13px; color: var(--text-dim); margin-bottom: 10px; }
.action-row {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  padding: 8px 10px; background: #12171d; border: 1px solid var(--panel-border);
  border-radius: 6px; margin-bottom: 8px; font-size: 12px;
}
.action-type { font-weight: 600; }
.action-reason { flex: 1; color: var(--text-dim); min-width: 160px; }
.dim { color: var(--text-dim); font-size: 12px; }
</style>
