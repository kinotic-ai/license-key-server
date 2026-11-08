<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { Plan, PlanInfo } from '@/api/model'
import { fetchPlans, startCheckout } from '@/api/rest'
import EmailCodeStep from '@/components/EmailCodeStep.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import { formatMoney } from '@/util/format'

/**
 * Buying a plan: the legal name and billing contact, the click-through acceptance, the code that confirms the billing
 * contact's address, then Stripe Checkout, where the payment details are entered. A signed-in customer buys for its own
 * account, so names no licensee; an address that turns out to have an account buys for that account.
 */
const route = useRoute()
const toast = useToast()
const confirming = ref(false)
const signedIn = computed(() => SESSION_STATE.isAuthenticated())

const plan = ref<PlanInfo | null>(null)
const legalName = ref('')
const contactName = ref('')
const contactEmail = ref('')
const accepted = ref(false)
const submitting = ref(false)

const planId = computed(() => String(route.query.plan ?? '').toUpperCase() as Plan)
const ready = computed(() => accepted.value
    && (signedIn.value || (legalName.value.trim() && contactName.value.trim() && contactEmail.value.includes('@'))))

onMounted(async () => {
  try {
    plan.value = (await fetchPlans()).find(p => p.id === planId.value && !p.requiresApproval) ?? null
  } catch (err) {
    showErrorToast(toast, 'The plan could not be loaded', err)
  }
})

async function submit() {
  if (!plan.value || !ready.value) {
    return
  }
  if (!signedIn.value) {
    confirming.value = true
    return
  }
  await checkout()
}

async function onVerified(account: boolean) {
  confirming.value = false
  if (account) {
    await SESSION_STATE.login()
    toast.add({ severity: 'info', summary: 'Welcome back', detail: 'This address already has an account, so the plan is bought for it.', life: 8000 })
  }
  await checkout()
}

async function checkout() {
  if (!plan.value) {
    return
  }
  submitting.value = true
  try {
    const url = await startCheckout(signedIn.value
        ? { plan: plan.value.id, acceptTerms: accepted.value }
        : { plan: plan.value.id, legalName: legalName.value.trim(), contactName: contactName.value.trim(), acceptTerms: accepted.value })
    // payment details are entered on Stripe's page, never here
    window.location.assign(url)
  } catch (err) {
    submitting.value = false
    showErrorToast(toast, 'The purchase could not be started', err)
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader :title="plan ? `Buy the ${plan.name} plan` : 'Buy a plan'"
                :description="plan ? `${formatMoney(plan.monthlyPrice, plan.currency)} per month, billed by Stripe. Cancel any time; the key you hold works until it expires.` : undefined" />
    <div v-if="!plan" class="app-muted-text text-sm">
      Pick a plan from the <RouterLink to="/" class="underline">plans page</RouterLink>.
    </div>
    <form v-else class="flex flex-col gap-5" @submit.prevent="submit">
      <section v-if="signedIn" class="app-card-surface rounded-xl border p-5 text-sm">
        <h2 class="app-heading-text font-medium">The licensee</h2>
        <p class="app-muted-text mt-2">
          Your account, with {{ SESSION_STATE.participant?.name }} &lt;{{ SESSION_STATE.participant?.email }}&gt; as the
          contact accepting the terms.
        </p>
      </section>
      <section v-else class="app-card-surface flex flex-col gap-5 rounded-xl border p-5">
        <h2 class="app-heading-text font-medium">The licensee</h2>
        <div>
          <label for="legal-name" class="mb-2 block text-sm font-medium">Organization's legal name</label>
          <InputText id="legal-name" v-model="legalName" class="w-full" autocomplete="organization" />
        </div>
        <div class="grid gap-5 sm:grid-cols-2">
          <div>
            <label for="contact-name" class="mb-2 block text-sm font-medium">Billing contact's name</label>
            <InputText id="contact-name" v-model="contactName" class="w-full" autocomplete="name" />
          </div>
          <div>
            <label for="contact-email" class="mb-2 block text-sm font-medium">Billing contact's email</label>
            <InputText id="contact-email" v-model="contactEmail" class="w-full" type="email" autocomplete="email" :disabled="confirming" />
            <p class="app-subtle-text mt-1 text-xs">The keys, receipts and sign-in codes go here; we send it a code to confirm it.</p>
          </div>
        </div>
      </section>
      <TermsAcceptance v-model="accepted" />
      <EmailCodeStep v-if="confirming" :email="contactEmail.trim()" @verified="onVerified" @cancel="confirming = false" />
      <div v-else class="flex justify-end">
        <Button type="submit" label="Continue to payment" icon="pi pi-arrow-right" icon-pos="right" :disabled="!ready" :loading="submitting" />
      </div>
    </form>
  </div>
</template>
