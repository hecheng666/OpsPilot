<template>
  <div class="timeline">
    <el-timeline>
      <el-timeline-item
        v-for="(s, i) in steps"
        :key="i"
        :type="dotType(s)"
        :hollow="s.type === 'THOUGHT'"
        size="normal"
      >
        <div class="step-head">
          <el-tag size="small" :type="tagType(s)" effect="dark">
            {{ s.type === 'TOOL_CALL' ? s.toolName : s.type }}
          </el-tag>
          <span v-if="s.stepNo" class="mono dim">step {{ s.stepNo }}</span>
          <el-tag v-if="s.evidenceId" size="small" type="success" effect="plain">{{ s.evidenceId }}</el-tag>
          <span v-if="s.elapsedMs" class="mono dim">{{ s.elapsedMs }}ms</span>
          <el-tag v-if="s.status === 'FAIL'" size="small" type="danger" effect="dark">FAIL</el-tag>
        </div>
        <div class="step-body" :class="{ failed: s.status === 'FAIL' }">{{ s.summary }}</div>
      </el-timeline-item>
      <el-timeline-item v-if="!steps.length" type="info" hollow>等待 Agent 启动…</el-timeline-item>
    </el-timeline>
  </div>
</template>

<script setup>
defineProps({ steps: { type: Array, default: () => [] } })

const dotType = (s) => {
  if (s.status === 'FAIL') return 'danger'
  if (s.type === 'REPORT') return 'success'
  if (s.type === 'TOOL_CALL') return 'primary'
  return 'info'
}
const tagType = (s) => {
  if (s.status === 'FAIL') return 'danger'
  if (s.type === 'REPORT') return 'success'
  if (s.type === 'TOOL_CALL') return 'primary'
  return 'info'
}
</script>

<style scoped>
.timeline { padding-left: 4px; }
.step-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.step-body {
  margin-top: 6px; font-size: 13px; line-height: 1.6; color: var(--text);
  background: #12171d; border: 1px solid var(--panel-border); border-radius: 6px; padding: 8px 10px;
  word-break: break-all;
}
.step-body.failed { color: var(--danger); border-color: rgba(248, 113, 113, 0.4); }
.dim { color: var(--text-dim); font-size: 11px; }
</style>
