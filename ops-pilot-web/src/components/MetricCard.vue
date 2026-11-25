<template>
  <div class="metric-card panel">
    <div class="label">{{ label }}</div>
    <div class="value mono" :style="{ color: valueColor }">
      {{ formatted }}<span class="unit">{{ unit }}</span>
    </div>
    <div v-if="hint" class="hint">{{ hint }}</div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  label: String,
  value: { type: Number, default: 0 },
  unit: { type: String, default: '' },
  hint: { type: String, default: '' },
  // 阈值着色：good < warn < bad（reverse 时方向相反）
  warnAt: { type: Number, default: null },
  badAt: { type: Number, default: null },
  reverse: { type: Boolean, default: false }
})

const formatted = computed(() => {
  const v = props.value
  if (v >= 1000) return Math.round(v).toLocaleString()
  return Number.isInteger(v) ? String(v) : v.toFixed(1)
})

const valueColor = computed(() => {
  const v = props.value
  if (props.warnAt == null) return 'var(--text)'
  const overWarn = props.reverse ? v < props.warnAt : v > props.warnAt
  const overBad = props.reverse ? v < props.badAt : v > props.badAt
  if (props.badAt != null && overBad) return 'var(--danger)'
  if (overWarn) return 'var(--warning)'
  return 'var(--primary)'
})
</script>

<style scoped>
.metric-card { min-width: 0; }
.label { font-size: 12px; color: var(--text-dim); margin-bottom: 8px; }
.value { font-size: 26px; font-weight: 700; }
.unit { font-size: 12px; color: var(--text-dim); margin-left: 4px; }
.hint { font-size: 11px; color: var(--text-dim); margin-top: 6px; }
</style>
