<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { requestSignInCode, verifySignInCode } from '@/api/rest'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/** Customer sign-in: a code emailed to a contact of the account, entered within ten minutes. */
const toast = useToast()
const route = useRoute()
const router = useRouter()

const email = ref('')
const code = ref('')
const codeRequested = ref(false)
const loading = ref(false)
const emailInput = ref<any>()

onMounted(() => {
  nextTick(() => emailInput.value?.$el?.focus?.())
})

async function requestCode() {
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
    await requestSignInCode(email.value.trim())
    codeRequested.value = true
    toast.add({ severity: 'info', summary: 'Check your email', detail: 'A code is on its way to that address.', life: 8000 })
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The code could not be sent')
  } finally {
    loading.value = false
  }
}

async function verify() {
  if (loading.value) {
    return
  }
  if (!code.value.trim()) {
    displayError('Enter the code from the email')
    return
  }
  loading.value = true
  try {
    const result = await verifySignInCode(email.value.trim(), code.value.trim())
    if (result.account) {
      // open the realtime connection, authenticated by the session cookie the server just set
      await SESSION_STATE.login()
      await router.push((route.query.referer as string | undefined) || '/account')
    } else {
      toast.add({ severity: 'info', summary: 'No account yet', detail: 'This address has no Kinotic license account. Buy a plan or request a free license to create one.', life: 10000 })
      await router.push('/')
    }
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The code is not valid')
    code.value = ''
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
          Sign in to your Kinotic license account with a code emailed to a contact of your account.
        </p>
        <IconField class="login-field">
          <InputText ref="emailInput" v-model="email" class="login-input" placeholder="Email" type="email" :disabled="codeRequested"
                     @keyup.enter="codeRequested ? verify() : requestCode()" />
        </IconField>
        <IconField v-if="codeRequested" class="login-field">
          <InputText v-model="code" class="login-input" placeholder="Code from the email" inputmode="numeric" autocomplete="one-time-code"
                     @keyup.enter="verify" />
        </IconField>
        <Button v-if="!codeRequested" label="Email me a code" class="login-submit" :loading="loading" @click="requestCode" />
        <Button v-else label="Sign in" class="login-submit" :loading="loading" @click="verify" />
        <Button v-if="codeRequested" label="Use another address" severity="secondary" text size="small" class="mt-2"
                @click="codeRequested = false; code = ''" />
      </div>
    </div>
  </AuthPageShell>
</template>
