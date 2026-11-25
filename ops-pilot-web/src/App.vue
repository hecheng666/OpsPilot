<template>
  <div class="layout">
    <header class="topbar">
      <div class="brand">
        <span class="logo">OP</span>
        <span class="name">OpsPilot</span>
        <span class="sub">AI 运维值班台</span>
      </div>
      <nav class="nav">
        <router-link to="/" class="nav-item" :class="{ active: $route.path === '/' }">总览</router-link>
        <router-link to="/faults" class="nav-item" :class="{ active: $route.path === '/faults' }">故障演练</router-link>
        <router-link to="/incidents" class="nav-item" :class="{ active: $route.path === '/incidents' }">事故中心</router-link>
        <router-link to="/agent" class="nav-item" :class="{ active: $route.path === '/agent' }">Agent 排查</router-link>
        <router-link to="/eval" class="nav-item" :class="{ active: $route.path === '/eval' }">评测中心</router-link>
      </nav>
      <div class="right mono">{{ now }}</div>
    </header>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'

const now = ref('')
let timer
const tick = () => {
  now.value = new Date().toLocaleString('zh-CN', { hour12: false })
}
onMounted(() => { tick(); timer = setInterval(tick, 1000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.layout { min-height: 100vh; display: flex; flex-direction: column; }
.topbar {
  height: 52px; display: flex; align-items: center; gap: 24px;
  padding: 0 20px; background: #12161c; border-bottom: 1px solid #232a33;
}
.brand { display: flex; align-items: center; gap: 10px; }
.logo {
  width: 28px; height: 28px; border-radius: 6px; display: inline-flex;
  align-items: center; justify-content: center;
  background: linear-gradient(135deg, #4fd1c5, #38b2ac);
  color: #0f1216; font-weight: 700; font-size: 13px;
}
.name { font-weight: 700; font-size: 16px; }
.sub { color: #8a94a3; font-size: 12px; }
.nav { display: flex; gap: 4px; }
.nav-item {
  color: #8a94a3; text-decoration: none; font-size: 14px;
  padding: 6px 14px; border-radius: 6px;
}
.nav-item.active, .nav-item:hover { color: #4fd1c5; background: rgba(79, 209, 197, 0.08); }
.right { margin-left: auto; color: #8a94a3; font-size: 13px; }
.content { flex: 1; padding: 20px; }
</style>
