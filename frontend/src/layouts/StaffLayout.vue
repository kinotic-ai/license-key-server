<script setup lang="ts">
import { computed, markRaw, ref } from 'vue'
import { useRouter } from 'vue-router'
import { LogOut, Shield } from '@lucide/vue'
import type { MenuItem } from 'primevue/menuitem'
import { SideBar, SidebarScope, SidebarUserMenu, ThemeToggleButton, avatarInitials, isDark } from '@kinotic-ai/frontend-common'
import headerLogo from '@/assets/header-logo.svg'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/** The staff dashboard's shell: a header, the sidebar built from the route meta, and the signed-in staff member. */
const router = useRouter()
const sidebarRef = ref<InstanceType<typeof SideBar> | null>(null)
const navOpen = ref(false)

const name = computed(() => SESSION_STATE.participant?.name ?? 'Staff')
const email = computed(() => SESSION_STATE.participant?.email ?? '')
const initials = computed(() => avatarInitials(name.value, email.value))
const collapsed = computed(() => sidebarRef.value?.collapsed ?? false)

// lucideIcon is rendered by SidebarUserMenu's item slot
const accountMenuItems: MenuItem[] = [
  { label: 'Log out', lucideIcon: markRaw(LogOut), command: logout }
]

async function logout() {
  try {
    await SESSION_STATE.logout()
  } finally {
    await router.push('/login')
  }
}
</script>

<template>
  <div :class="['h-screen w-screen transition-colors', isDark ? 'bg-surface-900' : 'bg-surface-0']">
    <div class="fixed top-0 left-0 right-0 z-50 h-[64px]">
      <div class="app-surface-header flex h-16 items-center justify-between border-b px-4 md:pl-[18px] md:pr-6">
        <div class="flex items-center gap-3">
          <button type="button" class="flex h-9 w-9 items-center justify-center rounded-full text-surface-600 md:hidden dark:text-surface-300"
                  aria-label="Open navigation" @click="navOpen = !navOpen">
            <span class="pi pi-bars" />
          </button>
          <RouterLink to="/customers" class="flex h-9 w-9 items-center justify-center rounded-[10px] bg-surface-950 dark:ring-1 dark:ring-surface-700">
            <img :src="headerLogo" class="h-[18px] w-5" alt="Kinotic" />
          </RouterLink>
          <span class="app-heading-text text-sm font-medium">License key server <span class="app-muted-text">Staff</span></span>
        </div>
        <ThemeToggleButton />
      </div>
    </div>
    <SideBar ref="sidebarRef" :mobile-open="navOpen" @close="navOpen = false">
      <template #scope="{ collapsed: isCollapsed, toggle }">
        <SidebarScope :collapsed="isCollapsed" name="Minds Ignited" kind="Staff" :icon="Shield" @toggle="toggle" />
      </template>
      <template #footer="{ collapsed: isCollapsed }">
        <SidebarUserMenu :collapsed="isCollapsed" :name="name" :detail="email" :initials="initials" :items="accountMenuItems" />
      </template>
    </SideBar>
    <div :class="['pt-[64px] transition-[padding]', collapsed ? 'md:pl-[73px]' : 'md:pl-[256px]']">
      <div class="h-[calc(100vh-64px)] overflow-y-auto px-4 py-4 md:px-8 md:py-6">
        <router-view class="min-w-0 flex-1" />
      </div>
    </div>
  </div>
</template>
