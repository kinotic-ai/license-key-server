<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { requestSignUp } from '@/api/rest'
import ProviderButtons from '@/components/ProviderButtons.vue'

/**
 * Starting a license account, through a provider or with the email address only. The link emailed to the address
 * opens the form that names the organization and sets the password; a provider leads to the same form with no
 * password to set.
 */
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
    displayError('Enter a valid email address')
    return
  }
  loading.value = true
  try {
    const submitted = email.value.trim()
    await requestSignUp(submitted, route.query.referer as string | undefined)
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
          Create a Kinotic license account to buy a plan or request a free license.
        </p>
        <ProviderButtons intent="sign-up" :referer="route.query.referer as string | undefined" />
        <IconField class="login-field">
          <InputText ref="emailInput" v-model="email" class="login-input" placeholder="Your email" type="email" autocomplete="email"
                     @keyup.enter="submit" />
        </IconField>
        <Button label="Sign up" class="login-submit" :loading="loading" @click="submit" />
        <p class="app-muted-text mt-4 text-center text-sm">
          Have an account? <RouterLink :to="{ path: '/login', query: route.query.referer ? { referer: route.query.referer } : {} }" class="login-link">Sign in</RouterLink>
        </p>
      </div>
    </div>
    <div v-else class="login-form">
      <div class="text-center pt-4 pb-2">
        <h2 class="text-2xl font-semibold mb-3">Check your email</h2>
        <p class="my-1">We've sent a link to</p>
        <p class="mt-1 mb-4 font-semibold break-all">{{ sentTo }}</p>
        <p class="my-1">Open it to continue. The email says what the link does and how long it lasts.</p>
        <Button label="Use another address" severity="secondary" text size="small" class="mt-2" @click="sentTo = null" />
      </div>
    </div>
  </AuthPageShell>
</template>
