<script setup lang="ts">
import { onMounted, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { showErrorToast } from '@kinotic-ai/frontend-common'
import { requestSignInCode, verifySignInCode } from '@/api/rest'

/**
 * Confirms an email address before a sign-up: sends it a code when shown, and reports whether the confirmed address
 * already belongs to an account, in which case the page is now signed in to it.
 */
const props = defineProps<{
  email: string
}>()

const emit = defineEmits<{
  (e: 'verified', account: boolean): void
  (e: 'cancel'): void
}>()

const toast = useToast()
const code = ref('')
const busy = ref(false)

onMounted(send)

async function send() {
  busy.value = true
  try {
    await requestSignInCode(props.email)
  } catch (err) {
    showErrorToast(toast, 'The code could not be sent', err)
  } finally {
    busy.value = false
  }
}

async function confirm() {
  // Enter in the code field bypasses the button's disabled state
  if (busy.value || !code.value.trim()) {
    return
  }
  busy.value = true
  try {
    const result = await verifySignInCode(props.email, code.value.trim())
    emit('verified', result.account)
  } catch (err) {
    showErrorToast(toast, 'The code is not valid', err)
    code.value = ''
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <section class="app-card-surface flex flex-col gap-4 rounded-xl border p-5">
    <h2 class="app-heading-text font-medium">Confirm your email</h2>
    <p class="app-muted-text text-sm">
      We sent a code to <strong class="app-heading-text">{{ email }}</strong>. Enter it to continue; it works for ten minutes.
    </p>
    <div class="flex flex-wrap items-end gap-3">
      <div>
        <label for="email-code" class="mb-2 block text-sm font-medium">Code</label>
        <InputText id="email-code" v-model="code" class="w-48 font-mono" inputmode="numeric" autocomplete="one-time-code"
                   @keyup.enter="confirm" />
      </div>
      <Button label="Confirm" :disabled="!code.trim()" :loading="busy" @click="confirm" />
      <Button label="Send another code" severity="secondary" text :disabled="busy" @click="send" />
      <Button label="Use another address" severity="secondary" text :disabled="busy" @click="emit('cancel')" />
    </div>
  </section>
</template>
