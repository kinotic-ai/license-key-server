<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { completeProviderSignUp, completeSignUp, fetchPendingProviderSignUp } from '@/api/rest'
import SetPasswordFields from '@/components/SetPasswordFields.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/**
 * The form that creates the account: the organization and the contact's name. The emailed sign-up link opens it with
 * its {@code token}, and it sets a password too. A sign-up through a provider opens it with {@code provider}, with the
 * name the provider gives filled in and no password to set.
 */
const toast = useToast()
const route = useRoute()
const router = useRouter()

const token = String(route.query.token ?? '')
const throughProvider = !!route.query.provider
/** The address a provider sign-up creates the account for, once the server has answered it. */
const providerEmail = ref<string | null>(null)
const organization = ref('')
const contactName = ref('')
const password = ref('')
const confirm = ref('')
const loading = ref(false)
const organizationInput = ref<any>()

const ready = computed(() => organization.value.trim() && contactName.value.trim()
    && (throughProvider ? !!providerEmail.value : password.value.length >= 12 && password.value === confirm.value))

const refererQuery = () => (route.query.referer ? { referer: route.query.referer } : {})

onMounted(async () => {
  if (throughProvider) {
    try {
      const pending = await fetchPendingProviderSignUp()
      providerEmail.value = pending.email
      contactName.value = pending.name ?? ''
    } catch (err) {
      displayError(err instanceof Error ? err.message : 'The sign-up has expired; start again')
      await router.push({ path: '/signup', query: refererQuery() })
      return
    }
  } else if (!token) {
    displayError('This page needs the link from your sign-up email')
  }
  nextTick(() => organizationInput.value?.$el?.focus?.())
})

async function submit() {
  // Enter in a field bypasses the button's disabled and loading states
  if (loading.value || !ready.value) {
    return
  }
  loading.value = true
  try {
    if (throughProvider) {
      await completeProviderSignUp(providerEmail.value!, organization.value.trim(), contactName.value.trim())
    } else {
      await completeSignUp(token, organization.value.trim(), contactName.value.trim(), password.value)
    }
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The account could not be created')
    loading.value = false
    return
  }
  try {
    // open the realtime connection, authenticated by the session cookie the server just set
    await SESSION_STATE.login()
    await router.push((route.query.referer as string | undefined) || '/account')
  } catch {
    // the account exists by now, so the page sends the contact to sign in rather than letting it retry
    toast.add({ severity: 'warn', summary: 'Sign in to continue', detail: 'Your account is created. Sign in to continue.', life: 10000 })
    await router.push({ path: '/login', query: refererQuery() })
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
        <p v-if="throughProvider" class="app-muted-text mb-2 text-center text-sm">
          Name your organization to finish creating your account<template v-if="providerEmail"> for
          <span class="font-semibold break-all">{{ providerEmail }}</span></template>.
        </p>
        <p v-else class="app-muted-text mb-2 text-center text-sm">
          Name your organization and set a password to finish creating your account.
        </p>
        <IconField class="login-field">
          <InputText ref="organizationInput" v-model="organization" class="login-input" placeholder="Organization's legal name"
                     autocomplete="organization" @keyup.enter="submit" />
        </IconField>
        <IconField class="login-field">
          <InputText v-model="contactName" class="login-input" placeholder="Your name" autocomplete="name" @keyup.enter="submit" />
        </IconField>
        <SetPasswordFields v-if="!throughProvider" v-model:password="password" v-model:confirm="confirm" @submit="submit" />
        <Button label="Create account" class="login-submit" :disabled="!ready" :loading="loading" @click="submit" />
      </div>
    </div>
  </AuthPageShell>
</template>
