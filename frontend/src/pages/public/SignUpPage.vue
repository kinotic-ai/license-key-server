<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { requestSignInCode, signUp, verifySignInCode } from '@/api/rest'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/**
 * Creating a license account: the organization, its contact and the contact's email, which a code emailed to it
 * confirms before the account is created. An address that already has an account is signed in to it instead.
 */
const toast = useToast()
const route = useRoute()
const router = useRouter()

const organization = ref('')
const contactName = ref('')
const email = ref(String(route.query.email ?? ''))
const code = ref('')
const codeRequested = ref(false)
const loading = ref(false)
const organizationInput = ref<any>()

const detailsReady = computed(() => organization.value.trim() && contactName.value.trim() && email.value.includes('@'))
const destination = computed(() => (route.query.referer as string | undefined) || '/account')

onMounted(() => {
  nextTick(() => organizationInput.value?.$el?.focus?.())
})

async function requestCode() {
  // Enter in a field bypasses the button's disabled and loading states
  if (loading.value || !detailsReady.value) {
    return
  }
  loading.value = true
  try {
    await requestSignInCode(email.value.trim())
    codeRequested.value = true
    toast.add({ severity: 'info', summary: 'Check your email', detail: 'A code is on its way to confirm the address.', life: 8000 })
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The code could not be sent')
  } finally {
    loading.value = false
  }
}

async function createAccount() {
  if (loading.value || !code.value.trim()) {
    return
  }
  loading.value = true
  try {
    const result = await verifySignInCode(email.value.trim(), code.value.trim())
    if (result.account) {
      toast.add({ severity: 'info', summary: 'Welcome back', detail: 'This address already has an account, so you are signed in to it.', life: 8000 })
    } else {
      await signUp(organization.value.trim(), contactName.value.trim())
    }
    // open the realtime connection, authenticated by the session cookie the server just set
    await SESSION_STATE.login()
    await router.push(destination.value)
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The account could not be created')
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
          Create a Kinotic license account to buy a plan or request a free license.
        </p>
        <IconField class="login-field">
          <InputText ref="organizationInput" v-model="organization" class="login-input" placeholder="Organization's legal name"
                     autocomplete="organization" :disabled="codeRequested" @keyup.enter="requestCode" />
        </IconField>
        <IconField class="login-field">
          <InputText v-model="contactName" class="login-input" placeholder="Your name" autocomplete="name" :disabled="codeRequested"
                     @keyup.enter="requestCode" />
        </IconField>
        <IconField class="login-field">
          <InputText v-model="email" class="login-input" placeholder="Your email" type="email" autocomplete="email" :disabled="codeRequested"
                     @keyup.enter="requestCode" />
        </IconField>
        <IconField v-if="codeRequested" class="login-field">
          <InputText v-model="code" class="login-input" placeholder="Code from the email" inputmode="numeric" autocomplete="one-time-code"
                     @keyup.enter="createAccount" />
        </IconField>
        <Button v-if="!codeRequested" label="Email me a code" class="login-submit" :disabled="!detailsReady" :loading="loading" @click="requestCode" />
        <Button v-else label="Create account" class="login-submit" :disabled="!code.trim()" :loading="loading" @click="createAccount" />
        <Button v-if="codeRequested" label="Change the details" severity="secondary" text size="small" class="mt-2"
                @click="codeRequested = false; code = ''" />
        <p class="app-muted-text mt-4 text-center text-sm">
          Have an account? <RouterLink :to="{ path: '/login', query: route.query.referer ? { referer: route.query.referer } : {} }" class="underline">Sign in</RouterLink>
        </p>
      </div>
    </div>
  </AuthPageShell>
</template>
