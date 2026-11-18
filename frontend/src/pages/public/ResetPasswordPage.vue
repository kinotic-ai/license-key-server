<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { resetPassword } from '@/api/rest'
import SetPasswordFields from '@/components/SetPasswordFields.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/** The form the emailed password reset link opens: a new password, which signs the contact in. */
const toast = useToast()
const route = useRoute()
const router = useRouter()

const token = String(route.query.token ?? '')
const password = ref('')
const confirm = ref('')
const loading = ref(false)

const ready = computed(() => password.value.length >= 12 && password.value === confirm.value)

onMounted(() => {
  if (!token) {
    displayError('This page needs the link from your password reset email')
  }
})

async function submit() {
  // Enter in a field bypasses the button's disabled and loading states
  if (loading.value || !ready.value) {
    return
  }
  loading.value = true
  try {
    await resetPassword(token, password.value)
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The password could not be set')
    loading.value = false
    return
  }
  try {
    // open the realtime connection, authenticated by the session cookie the server just set
    await SESSION_STATE.login()
    await router.push((route.query.referer as string | undefined) || '/account')
  } catch {
    // the link is used by now, so the page sends the contact to sign in rather than letting it retry
    toast.add({ severity: 'warn', summary: 'Sign in to continue', detail: 'Your password is set. Sign in with your email and password.', life: 10000 })
    await router.push({ path: '/login', query: route.query.referer ? { referer: route.query.referer } : {} })
  } finally {
    loading.value = false
  }
}

function displayError(text: string) {
  toast.add({ severity: 'error', summary: 'Error', detail: text, life: 10000 })
}
</script>

<template>
  <AuthPageShell>
    <div class="login-form">
      <div class="login-form__step">
        <p class="app-muted-text mb-2 text-center text-sm">
          Set a new password for your Kinotic license account.
        </p>
        <SetPasswordFields v-model:password="password" v-model:confirm="confirm" @submit="submit" />
        <Button label="Set password" class="login-submit" :disabled="!ready" :loading="loading" @click="submit" />
        <p class="app-muted-text mt-4 text-center text-sm">
          Link expired? <RouterLink :to="{ path: '/password/forgot', query: route.query.referer ? { referer: route.query.referer } : {} }" class="login-link">Ask for a new one</RouterLink>
        </p>
      </div>
    </div>
  </AuthPageShell>
</template>
