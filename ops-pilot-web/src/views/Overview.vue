<template>
  <div class="overview">
    <!-- 活跃事故横幅 -->
    <div v-if="data.incidents?.length" class="incident-banner">
      <el-icon class="blink"><WarningFilled /></el-icon>
      <span v-for="inc in data.incidents" :key="inc.id" class="incident-item">
        <el-tag type="danger" size="small" effect="dark">{{ inc.severity }}</el-tag>
        #{{ inc.id }} {{ inc.title }}
      </span>
    </div>

    <!-- 服务状态 + 当前故障 -->
    <div class="status-row">
      <div class="panel service-panel">
        <div class="panel-title">服务状态</div>
        <div class="service-line">
          <el-tag :type="data.service?.status === 'UP' ? 'success' : 'danger'" effect="dark">
            {{ data.service?.status || 'UNKNOWN' }}
          </el-tag>
          <span class="svc-name mono">{{ data.service?.name }}</span>
          <span class="svc-url mono">{{ data.service?.baseUrl }}</span>
        </div>
        <div v-if="data.faults?.length" class="fault-line">
          <span class="dim">演练中故障：</span>
          <el-tag v-for="f in data.faults" :key="f.type" type="warning" effect="dark" size="small">
            {{ f.type }} · 强度{{ f.intensity }}
          </el-tag>
        </div>
        <div v-else class="fault-line dim">无进行中故障演练</div>
      </div>
    </div>

    <!-- KPI 卡片 -->
    <div class="kpi-grid">
      <MetricCard label="QPS" :value="num('qps')" unit="req/s" :warn-at="20" :bad-at="40" />
      <MetricCard label="P95 延迟" :value="num('p95')" unit="ms" :warn-at="500" :bad-at="800" />
      <MetricCard label="错误率" :value="num('errorRate')" unit="%" :warn-at="2" :bad-at="5" />
      <MetricCard label="缓存命中率" :value="num('cacheHitRate')" unit="%" :warn-at="80" :bad-at="60" reverse />
      <MetricCard label="DB 平均 RT" :value="num('dbRtMs')" unit="ms" :warn-at="300" :bad-at="500" />
      <MetricCard label="线程池" :value="num('threadActive')" :unit="'/' + num('threadMax')" hint="活跃/最大" :warn-at="13" :bad-at="16" />
    </div>

    <!-- 时序图 -->
    <div class="chart-row">
      <div class="panel">
        <div class="panel-title">延迟与错误率（近 30 分钟）</div>
        <TimeSeriesChart :series-list="latencySeries" />
      </div>
      <div class="panel">
        <div class="panel-title">QPS 与缓存命中率（近 30 分钟）</div>
        <TimeSeriesChart :series-list="qpsSeries" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { WarningFilled } from '@element-plus/icons-vue'
import MetricCard from '../components/MetricCard.vue'
import TimeSeriesChart from '../components/TimeSeriesChart.vue'
import api from '../api'

const data = ref({})
let timer

const load = async () => {
  try {
    const res = await api.overview()
    data.value = res.data
  } catch (e) {
    console.error('overview load failed', e)
  }
}
onMounted(() => { load(); timer = setInterval(load, 5000) })
onUnmounted(() => clearInterval(timer))

const num = (k) => data.value?.latest?.[k] ?? 0

const latencySeries = computed(() => [
  { name: 'P95 (ms)', color: '#f59e0b', data: data.value?.series?.p95 || [] },
  { name: '错误率 (%)', color: '#f87171', data: data.value?.series?.errorRate || [] }
])
const qpsSeries = computed(() => [
  { name: 'QPS', color: '#4fd1c5', data: data.value?.series?.qps || [] },
  { name: '缓存命中率 (%)', color: '#7ea6f4', data: data.value?.series?.cacheHitRate || [] }
])
</script>

<style scoped>
.overview { display: flex; flex-direction: column; gap: 16px; }
.incident-banner {
  display: flex; align-items: center; gap: 12px; flex-wrap: wrap;
  background: rgba(248, 113, 113, 0.1); border: 1px solid rgba(248, 113, 113, 0.4);
  border-radius: 8px; padding: 10px 16px; color: #f87171; font-size: 13px;
}
.incident-item { display: inline-flex; align-items: center; gap: 6px; }
.blink { animation: blink 1.2s infinite; }
@keyframes blink { 50% { opacity: 0.3; } }
.kpi-grid {
  display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px;
}
.chart-row { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.service-line { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.svc-name { font-weight: 700; }
.svc-url { color: var(--text-dim); font-size: 12px; }
.fault-line { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.dim { color: var(--text-dim); font-size: 13px; }
@media (max-width: 1280px) {
  .kpi-grid { grid-template-columns: repeat(3, 1fr); }
  .chart-row { grid-template-columns: 1fr; }
}
</style>
