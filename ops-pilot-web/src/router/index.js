import { createRouter, createWebHistory } from 'vue-router'

// 路由懒加载：各页面独立 chunk，首屏只加载当前页
export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: () => import('../views/Overview.vue') },
    { path: '/faults', name: 'faults', component: () => import('../views/Faults.vue') },
    { path: '/incidents', name: 'incidents', component: () => import('../views/Incidents.vue') },
    { path: '/agent', name: 'agent', component: () => import('../views/Agent.vue') },
    { path: '/eval', name: 'eval', component: () => import('../views/Eval.vue') }
  ]
})
