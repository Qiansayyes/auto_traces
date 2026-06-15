import { createRouter, createWebHistory } from 'vue-router'

import LayoutView from '@/views/layout/index.vue'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'index',
      component: LayoutView,
      redirect: '/email',
      children: [
        {
          path: '/email',
          name: 'email',
          component: () => import('@/views/email/index.vue'),
        },
        {
          path: '/sender-groups',
          name: 'sender-groups',
          component: () => import('@/views/sender-groups/index.vue'),
        },
        {
          path: '/template-manager',
          name: 'template-manager',
          component: () => import('@/views/template-manager/index.vue'),
        },
        {
          path: '/project-api',
          name: 'project-api',
          component: () => import('@/views/project-api/index.vue'),
        },
        {
          path: '/project-mailboxes',
          name: 'project-mailboxes',
          component: () => import('@/views/project-mailboxes/index.vue'),
        },
        {
          path: '/api-docs',
          name: 'api-docs',
          component: () => import('@/views/api-docs/index.vue'),
        },
      ],
    },
  ],
})

export default router
