<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import { ThemeToggleButton, isDark } from '@kinotic-ai/frontend-common'
import headerLogo from '@/assets/header-logo.svg'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/** The public site's chrome: the header with the buy, free license and account links, and the footer. */
const router = useRouter()
const signedIn = computed(() => SESSION_STATE.isAuthenticated())

async function logout() {
  try {
    await SESSION_STATE.logout()
  } finally {
    await router.push('/')
  }
}
</script>

<template>
  <div :class="['min-h-screen transition-colors', isDark ? 'bg-surface-900' : 'bg-surface-0']">
    <header class="app-surface-header sticky top-0 z-40 border-b">
      <div class="mx-auto flex h-16 max-w-6xl items-center justify-between gap-4 px-4 md:px-8">
        <RouterLink to="/" class="flex items-center gap-3">
          <span class="flex h-9 w-9 items-center justify-center rounded-[10px] bg-surface-950 dark:ring-1 dark:ring-surface-700">
            <img :src="headerLogo" class="h-[18px] w-5" alt="Kinotic" />
          </span>
          <span class="app-heading-text text-sm font-medium">Kinotic <span class="app-muted-text">Licenses</span></span>
        </RouterLink>
        <nav class="flex items-center gap-1 md:gap-2">
          <RouterLink to="/buy" class="app-muted-text px-3 py-2 text-sm hover:text-primary-500">Buy</RouterLink>
          <RouterLink to="/free" class="app-muted-text px-3 py-2 text-sm hover:text-primary-500">Free license</RouterLink>
          <RouterLink to="/account" class="app-muted-text px-3 py-2 text-sm hover:text-primary-500">Account</RouterLink>
          <ThemeToggleButton />
          <Button v-if="signedIn" label="Sign out" severity="secondary" text size="small" @click="logout" />
          <RouterLink v-else to="/login"><Button label="Sign in" severity="secondary" outlined size="small" /></RouterLink>
        </nav>
      </div>
    </header>
    <div class="mx-auto max-w-6xl px-4 py-8 md:px-8 md:py-12">
      <router-view />
    </div>
    <footer class="app-surface-divider mx-auto max-w-6xl border-t px-4 py-8 text-xs md:px-8">
      <p class="app-subtle-text">
        Kinotic Enterprise is licensed by Minds Ignited under the Kinotic Enterprise License. Payments are processed by
        Stripe; this site never sees your card details.
      </p>
    </footer>
  </div>
</template>
