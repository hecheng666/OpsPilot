import { createRouter, createWebHistory } from 'vue-router'
import Overview from '../views/Overview.vue'
import Faults from '../views/Faults.vue'
import Incidents from '../views/Incidents.vue'
import Agent from '../views/Agent.vue'
import Eval from '../views/Eval.vue'

export default createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'overview', component: Overview },
    { path: '/faults', name: 'faults', component: Faults },
    { path: '/incidents', name: 'incidents', component: Incidents },
    { path: '/agent', name: 'agent', component: Agent },
    { path: '/eval', name: 'eval', component: Eval }
  ]
})
