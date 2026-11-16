<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Button from 'primevue/button'
import IconField from 'primevue/iconfield'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { AuthPageShell } from '@kinotic-ai/frontend-common'
import { completeSignUp } from '@/api/rest'
import SetPasswordFields from '@/components/SetPasswordFields.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'

/** The form the emailed sign-up link opens: the organization, the contact's name and a password create the account. */
const toast = useToast()
const route = useRoute()
const router = useRouter()

const token = String(route.query.token ?? '')
const organization = ref('')
const contactName = ref('')
const password = ref('')
const confirm = ref('')
const loading = ref(false)
const organizationInput = ref<any>()

const ready = computed(() => organization.value.trim() && contactName.value.trim()
    && password.value.length >= 12 && password.value === confirm.value)

onMounted(() => {
  if (!token) {
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
    await completeSignUp(token, organization.value.trim(), contactName.value.trim(), password.value)
    // open the realtime connection, authenticated by the session cookie the server just set
    await SESSION_STATE.login()
    await router.push((route.query.referer as string | undefined) || '/account')
  } catch (err) {
    displayError(err instanceof Error ? err.message : 'The account could not be created')
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
          Name your organization and set a password to finish creating your account.
        </p>
        <IconField class="login-field">
          <InputText ref="organizationInput" v-model="organization" class="login-input" placeholder="Organization's legal name"
                     autocomplete="organization" @keyup.enter="submit" />
        </IconField>
        <IconField class="login-field">
          <InputText v-model="contactName" class="login-input" placeholder="Your name" autocomplete="name" @keyup.enter="submit" />
        </IconField>
        <SetPasswordFields v-model:password="password" v-model:confirm="confirm" @submit="submit" />
        <Button label="Create account" class="login-submit" :disabled="!ready" :loading="loading" @click="submit" />
      </div>
    </div>
  </AuthPageShell>
</template>
