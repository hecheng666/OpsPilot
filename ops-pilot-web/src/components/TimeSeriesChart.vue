<template>
  <div ref="el" class="chart"></div>
</template>

<script setup>
import { ref, watch, onMounted, onUnmounted } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  // [{ name, color, unit, data: [{ts, value}] }]
  seriesList: { type: Array, required: true },
  height: { type: Number, default: 260 }
})

const el = ref(null)
let chart = null

const fmtTime = (ts) => ts.length >= 16 ? ts.substring(11, 19) : ts

function render() {
  if (!chart) return
  chart.setOption({
    backgroundColor: 'transparent',
    grid: { left: 44, right: 16, top: 30, bottom: 24 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: '#1c232b', borderColor: '#2a323c',
      textStyle: { color: '#d6dde6', fontSize: 12 }
    },
    legend: { textStyle: { color: '#8a94a3', fontSize: 12 }, top: 0 },
    xAxis: {
      type: 'category',
      data: (props.seriesList[0]?.data || []).map(p => fmtTime(p.ts)),
      axisLine: { lineStyle: { color: '#2a323c' } },
      axisLabel: { color: '#8a94a3', fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: '#232a33' } },
      axisLabel: { color: '#8a94a3', fontSize: 11 }
    },
    series: props.seriesList.map(s => ({
      name: s.name, type: 'line', smooth: true, showSymbol: false,
      data: s.data.map(p => p.value),
      lineStyle: { color: s.color, width: 2 },
      itemStyle: { color: s.color },
      areaStyle: { color: s.color, opacity: 0.08 }
    }))
  }, true)
}

onMounted(() => {
  chart = echarts.init(el.value)
  render()
  window.addEventListener('resize', resize)
})
onUnmounted(() => {
  window.removeEventListener('resize', resize)
  chart?.dispose()
})
const resize = () => chart?.resize()
watch(() => props.seriesList, render, { deep: true })
</script>

<style scoped>
.chart { width: 100%; height: 100%; min-height: 220px; }
</style>
