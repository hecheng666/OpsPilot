<template>
  <div class="evidence-card">
    <div class="head">
      <el-tag size="small" type="success" effect="dark">{{ evidence.id }}</el-tag>
      <el-tag size="small" effect="plain" :type="sourceTypeTag">{{ evidence.sourceType }}</el-tag>
      <span class="title">{{ evidence.title }}</span>
    </div>
    <div class="summary">{{ evidence.summary }}</div>
    <el-collapse v-if="evidence.payload" class="payload">
      <el-collapse-item title="查看原始数据">
        <pre class="mono">{{ pretty }}</pre>
      </el-collapse-item>
    </el-collapse>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ evidence: { type: Object, required: true } })

const sourceTypeTag = computed(() => ({
  METRIC: 'primary', LOG: 'warning', CHANGE: 'info',
  RUNBOOK: 'success', TOPOLOGY: 'info', INCIDENT: 'danger', ACTION: 'warning'
}[props.evidence.sourceType] || 'info'))

const pretty = computed(() => {
  try {
    return JSON.stringify(JSON.parse(props.evidence.payload), null, 2)
  } catch (e) {
    return props.evidence.payload
  }
})
</script>

<style scoped>
.evidence-card {
  background: #12171d; border: 1px solid var(--panel-border); border-radius: 8px;
  padding: 10px 12px; margin-bottom: 10px;
}
.head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.title { font-size: 13px; font-weight: 600; }
.summary { font-size: 12px; color: var(--text-dim); margin-top: 6px; line-height: 1.6; word-break: break-all; }
.payload { margin-top: 6px; }
.payload pre {
  font-size: 11px; color: #9fb0c3; max-height: 240px; overflow: auto;
  background: #0f1216; padding: 8px; border-radius: 6px; margin: 0;
}
:deep(.el-collapse-item__header) { height: 28px; line-height: 28px; font-size: 12px; background: transparent; }
:deep(.el-collapse-item__wrap) { background: transparent; border: none; }
</style>
