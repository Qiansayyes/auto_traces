<template>
  <div class="layout-shell" :class="{ 'layout-shell--embedded': embedded }">
    <aside v-if="!embedded" class="desktop-sidebar">
      <div class="sidebar-brand">
        <span class="brand-mark">M</span>
        <span class="brand-text">邮箱工具</span>
      </div>
      <el-menu :default-active="activeIndex" class="side-menu" @select="handleSelect">
        <el-menu-item v-for="item in menuItems" :key="item.index" :index="item.index">
          <el-icon>
            <component :is="item.icon" />
          </el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <el-drawer
      v-if="!embedded"
      v-model="mobileDrawerVisible"
      direction="ltr"
      :with-header="false"
      size="280px"
      class="mobile-sidebar-drawer"
    >
      <div class="sidebar-brand mobile-brand">
        <span class="brand-mark">M</span>
        <span class="brand-text">邮箱工具</span>
      </div>
      <el-menu :default-active="activeIndex" class="side-menu mobile-menu" @select="handleSelect">
        <el-menu-item v-for="item in menuItems" :key="item.index" :index="item.index">
          <el-icon>
            <component :is="item.icon" />
          </el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-drawer>

    <main class="layout-main">
      <el-alert
        v-if="!embedded && !loggedIn"
        title="请先在 Auto Traces 主控制台登录，再使用邮箱管理功能。"
        type="warning"
        show-icon
        :closable="false"
        class="login-alert"
      />
      <button
        v-if="!embedded"
        class="mobile-menu-trigger"
        type="button"
        aria-label="打开菜单"
        @click="mobileDrawerVisible = true"
      >
        <el-icon><MenuIcon /></el-icon>
      </button>
      <router-view />
    </main>
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import {
  Collection,
  Connection,
  Document,
  Key,
  Link,
  Menu as MenuIcon,
  Message,
  Tickets,
  User,
} from '@element-plus/icons-vue'
import router from '@/router'
import { isEmbedded, isLoggedIn } from '@/utils/auth'

const embedded = computed(() => isEmbedded())
const loggedIn = computed(() => isLoggedIn())

interface MenuItem {
  index: string
  title: string
  routeName: string
  icon: unknown
}

const menuItems = ref<MenuItem[]>([
  { index: '1', title: '邮箱管理', routeName: 'email', icon: Message },
  { index: '8', title: '令牌邮箱管理', routeName: 'token-mailboxes', icon: Key },
  { index: '7', title: '待注册TK账号', routeName: 'tk-pending', icon: User },
  { index: '2', title: '发件人分类', routeName: 'sender-groups', icon: Connection },
  { index: '3', title: '模板管理', routeName: 'template-manager', icon: Tickets },
  { index: '4', title: '项目接口', routeName: 'project-api', icon: Link },
  { index: '5', title: '项目接码邮箱', routeName: 'project-mailboxes', icon: Collection },
  { index: '6', title: '接口文档', routeName: 'api-docs', icon: Document },
])

const route = useRoute()
const mobileDrawerVisible = ref(false)

const activeIndex = computed(() => {
  const currentMenuItem = menuItems.value.find(item => item.routeName === route.name)
  return currentMenuItem?.index || '1'
})

const handleSelect = (key: string) => {
  const selectedItem = menuItems.value.find(item => item.index === key)
  if (selectedItem?.routeName) {
    mobileDrawerVisible.value = false
    router.push({ name: selectedItem.routeName })
  }
}
</script>

<style scoped>
.layout-shell {
  min-height: 100vh;
  background: #f5f7fb;
}

.layout-shell--embedded {
  min-height: 100%;
  background: transparent;
}

.desktop-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  z-index: 30;
  width: 236px;
  padding: 18px 14px;
  background: #fff;
  border-right: 1px solid #e4e7ed;
  box-shadow: 8px 0 24px rgba(20, 35, 66, 0.05);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 44px;
  padding: 0 10px 18px;
  margin-bottom: 8px;
  border-bottom: 1px solid #edf0f5;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: #fff;
  font-weight: 700;
  background: #2364d2;
}

.brand-text {
  font-weight: 700;
  color: #1f2d3d;
}

.side-menu {
  border-right: none;
}

.side-menu :deep(.el-menu-item) {
  height: 44px;
  border-radius: 8px;
  margin: 4px 0;
}

.side-menu :deep(.el-menu-item.is-active) {
  color: #2364d2;
  background: #eef5ff;
}

.layout-main {
  min-width: 0;
  min-height: 100vh;
  margin-left: 236px;
}

.layout-shell--embedded .layout-main {
  min-height: 100%;
  margin-left: 0;
}

.mobile-menu-trigger {
  display: none;
}

@media (max-width: 900px) {
  .desktop-sidebar {
    display: none;
  }

  .layout-main {
    margin-left: 0;
    padding-top: 52px;
  }

  .layout-shell--embedded .layout-main {
    padding-top: 0;
  }

  .mobile-menu-trigger {
    position: fixed;
    top: 10px;
    left: 12px;
    z-index: 40;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 40px;
    height: 40px;
    border: 1px solid #dcdfe6;
    border-radius: 10px;
    color: #303133;
    background: rgba(255, 255, 255, 0.96);
    box-shadow: 0 8px 24px rgba(20, 35, 66, 0.12);
  }

  .mobile-brand {
    padding-top: 4px;
  }
}

:deep(.mobile-sidebar-drawer .el-drawer__body) {
  padding: 16px 14px;
}
</style>
