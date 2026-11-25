<template>
  <div v-if="report" class="report">
    <div class="summary">{{ report.summary }}</div>

    <div class="section">
      <div class="label">根因定位</div>
      <div class="root">
        <div class="root-title">{{ report.root_cause?.title }}</div>
        <div class="conf">
          <el-progress
            :percentage="Math.round((report.root_cause?.confidence || 0) * 100)"
            :stroke-width="8" :color="confColor(report.root_cause?.confidence)"
          />
        </div>
      </div>
      <div v-if="report.root_cause?.evidence_ids?.length" class="chips">
        <el-tag v-for="id in report.root_cause.evidence_ids" :key="id" size="small" type="success" effect="plain">
          {{ id }}
        </el-tag>
      </div>
    </div>

    <div v-if="report.alternatives?.length" class="section">
      <div class="label">备选根因</div>
      <div v-for="(a, i) in report.alternatives" :key="i" class="alt">
        <span>{{ a.title }}</span>
        <span class="mono dim">{{ Math.round((a.confidence || 0) * 100) }}%</span>
      </div>
    </div>

    <div v-if="report.impact" class="section">
      <div class="label">影响面</div>
      <div class="text">{{ report.impact }}</div>
    </div>

    <div v-if="report.actions?.length" class="section">
      <div class="label">处置建议（需人工审批）</div>
      <div v-for="a in report.actions" :key="a.actionId" class="action">
        <el-tag size="small" type="warning" effect="dark">动作 #{{ a.actionId }}</el-tag>
        <el-tag size="small" :type="riskTag(a.risk)" effect="plain">{{ a.risk }}</el-tag>
        <span class="dim">{{ a.status }}</span>
      </div>
    </div>

    <div v-if="report.prevention?.length" class="section">
      <div class="label">预防建议</div>
      <ul class="list">
        <li v-for="(p, i) in report.prevention" :key="i">{{ p }}</li>
      </ul>
    </div>
  </div>
  <div v-else class="empty dim">暂无报告</div>
</template>

<script setup>
defineProps({ report: { type: Object, default: null } })

const confColor = (c) => (c >= 0.8 ? '#4fd1c5' : c >= 0.5 ? '#f59e0b' : '#f87171')
const riskTag = (r) => (r === 'HIGH' ? 'danger' : r === 'LOW' ? 'success' : 'warning')
</script>

<style scoped>
.report { display: flex; flex-direction: column; gap: 14px; }
.summary {
  font-size: 14px; line-height: 1.7; padding: 10px 12px;
  background: rgba(79, 209, 197, 0.06); border-left: 3px solid var(--primary); border-radius: 6px;
}
.section .label { font-size: 12px; color: var(--text-dim); margin-bottom: 8px; }
.root { display: flex; align-items: center; gap: 16px; }
.root-title { flex: 1; font-size: 14px; font-weight: 600; }
.conf { width: 160px; }
.chips { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.alt { display: flex; justify-content: space-between; font-size: 13px; padding: 4px 0; }
.action { display: flex; align-items: center; gap: 8px; font-size: 13px; padding: 4px 0; }
.list { margin: 0; padding-left: 18px; font-size: 13px; line-height: 1.8; color: var(--text); }
.text { font-size: 13px; line-height: 1.7; }
.dim { color: var(--text-dim); }
.empty { font-size: 13px; padding: 24px 0; text-align: center; }
</style>
