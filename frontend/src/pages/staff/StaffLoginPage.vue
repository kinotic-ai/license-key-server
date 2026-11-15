<script setup lang="ts">
import { onMounted } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell, apiUrl } from '@kinotic-ai/frontend-common'

/** Staff sign-in: a redirect through Minds Ignited's Microsoft Entra tenant, which returns to the page asked for. */
const route = useRoute()
const toast = useToast()

onMounted(() => {
  const error = route.query.error as string | undefined
  if (error) {
    toast.add({ severity: 'error', summary: 'Sign-in failed', detail: error, life: 12000 })
  }
})

function signIn() {
  const referer = (route.query.referer as string | undefined) || '/customers'
  window.location.assign(apiUrl(`/api/auth/staff/login?referer=${encodeURIComponent(referer)}`))
}
</script>

<template>
  <AuthPageShell>
    <div class="login-form">
      <div class="login-form__step">
        <p class="app-muted-text mb-2 text-center text-sm">The license key server's staff dashboard.</p>
        <Button label="Sign in with Microsoft" class="login-submit" icon="pi pi-microsoft" @click="signIn" />
      </div>
    </div>
  </AuthPageShell>
</template>
