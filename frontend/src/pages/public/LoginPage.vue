<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import Password from 'primevue/password'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { signIn, signInErrorMessage } from '@/api/rest'
import ProviderButtons from '@/components/ProviderButtons.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/**
 * Customer sign-in, through a provider or with the email and password of a contact of the account. A provider
 * sign-in that fails returns here with the failure's code in {@code error}.
 */
const toast = useToast()
const route = useRoute()
const router = useRouter()

const email = ref('')
const password = ref('')
const loading = ref(false)
const emailInput = ref<any>()

onMounted(() => {
  const error = route.query.error as string | undefined
  if (error) {
    toast.add({ severity: 'error', summary: 'Sign-in failed', detail: signInErrorMessage(error), life: 12000 })
  }
  nextTick(() => emailInput.value?.$el?.focus?.())
})

async function submit() {
  // Enter in a field bypasses the button's loading state
  if (loading.value) {
    return
  }
  if (!email.value.includes('@') || !password.value) {
    displayError('Enter your email and password')
    return
  }
  loading.value = true
  try {
    await signIn(email.value.trim(), password.value)
    // open the realtime connection, authenticated by the session cookie the server just set
    await SESSION_STATE.login()
    await router.push((route.query.referer as string | undefined) || '/account')
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The email or password is not valid')
    password.value = ''
  } finally {
    loading.value = false
  }
}

function displayError(text: string) {
  toast.add({ severity: 'error', summary: 'Error', detail: text, life: 10000 })
}

const refererQuery = () => (route.query.referer ? { referer: route.query.referer } : {})
</script>

<template>
  <AuthPageShell>
    <div class="login-form">
      <div class="login-form__step">
        <p class="app-muted-text mb-2 text-center text-sm">
          Sign in to your Kinotic license account.
        </p>
        <ProviderButtons intent="sign-in" :referer="route.query.referer as string | undefined" />
        <IconField class="login-field">
          <InputText ref="emailInput" v-model="email" class="login-input" placeholder="Email" type="email" autocomplete="email"
                     @keyup.enter="submit" />
        </IconField>
        <IconField class="login-field">
          <Password v-model="password" class="login-password w-full" input-class="login-input w-full" placeholder="Password"
                    :feedback="false" toggleMask autocomplete="current-password" @keyup.enter="submit" />
        </IconField>
        <Button label="Sign in" class="login-submit" :loading="loading" @click="submit" />
        <p class="app-muted-text mt-4 text-center text-sm">
          <RouterLink :to="{ path: '/password/forgot', query: { ...refererQuery(), ...(email.includes('@') ? { email: email.trim() } : {}) } }" class="login-link">Forgot your password?</RouterLink>
        </p>
        <p class="app-muted-text mt-2 text-center text-sm">
          No account yet? <RouterLink :to="{ path: '/signup', query: refererQuery() }" class="login-link">Create one</RouterLink>
        </p>
      </div>
    </div>
  </AuthPageShell>
</template>
