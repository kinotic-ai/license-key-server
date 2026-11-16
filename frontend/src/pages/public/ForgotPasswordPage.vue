<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { requestPasswordReset } from '@/api/rest'

/** Asking for a link to set a new password, emailed to a contact of the account. */
const toast = useToast()
const route = useRoute()

const email = ref(String(route.query.email ?? ''))
const sentTo = ref<string | null>(null)
const loading = ref(false)
const emailInput = ref<any>()

onMounted(() => {
  nextTick(() => emailInput.value?.$el?.focus?.())
})

async function submit() {
  // Enter in a field bypasses the button's loading state
  if (loading.value) {
    return
  }
  if (!email.value.includes('@')) {
    displayError('Enter the email address of a contact of your account')
    return
  }
  loading.value = true
  try {
    const submitted = email.value.trim()
    await requestPasswordReset(submitted, route.query.referer as string | undefined)
    sentTo.value = submitted
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The link could not be sent')
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
    <div v-if="!sentTo" class="login-form">
      <div class="login-form__step">
        <p class="app-muted-text mb-2 text-center text-sm">
          Enter your email and we'll send you a link to set a new password.
        </p>
        <IconField class="login-field">
          <InputText ref="emailInput" v-model="email" class="login-input" placeholder="Email" type="email" autocomplete="email"
                     @keyup.enter="submit" />
        </IconField>
        <Button label="Email me a link" class="login-submit" :loading="loading" @click="submit" />
        <p class="app-muted-text mt-4 text-center text-sm">
          <RouterLink :to="{ path: '/login', query: route.query.referer ? { referer: route.query.referer } : {} }" class="underline">Back to sign in</RouterLink>
        </p>
      </div>
    </div>
    <div v-else class="login-form">
      <div class="text-center pt-4 pb-2">
        <h2 class="text-2xl font-semibold mb-3">Check your email</h2>
        <p class="my-1">If <span class="font-semibold break-all">{{ sentTo }}</span> belongs to an account, we've sent it a link to set a new password.</p>
        <p class="mt-4 app-muted-text text-sm">The link expires in 1 hour.</p>
      </div>
    </div>
  </AuthPageShell>
</template>
