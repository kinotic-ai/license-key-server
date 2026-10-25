<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { Plan, PlanInfo } from '@/api/model'
import { fetchPlans, startCheckout } from '@/api/rest'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import { formatMoney } from '@/util/format'

/**
 * Buying a plan: the legal name and billing contact, the click-through acceptance, then Stripe Checkout, where the
 * payment details are entered.
 */
const route = useRoute()
const toast = useToast()

const plan = ref<PlanInfo | null>(null)
const legalName = ref('')
const contactName = ref('')
const contactEmail = ref('')
const accepted = ref(false)
const submitting = ref(false)

const planId = computed(() => String(route.query.plan ?? '').toUpperCase() as Plan)
const ready = computed(() => legalName.value.trim() && contactName.value.trim() && contactEmail.value.includes('@') && accepted.value)

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
  submitting.value = true
  try {
    const url = await startCheckout({
      plan: plan.value.id,
      legalName: legalName.value.trim(),
      contactName: contactName.value.trim(),
      contactEmail: contactEmail.value.trim(),
      acceptTerms: accepted.value
    })
    // payment details are entered on Stripe's page, never here
    window.location.assign(url)
  } catch (err) {
    showErrorToast(toast, 'The purchase could not be started', err)
    submitting.value = false
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
      <section class="app-card-surface flex flex-col gap-5 rounded-xl border p-5">
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
            <InputText id="contact-email" v-model="contactEmail" class="w-full" type="email" autocomplete="email" />
            <p class="app-subtle-text mt-1 text-xs">The keys, receipts and sign-in codes go here.</p>
          </div>
        </div>
      </section>
      <TermsAcceptance v-model="accepted" />
      <div class="flex justify-end">
        <Button type="submit" label="Continue to payment" icon="pi pi-arrow-right" icon-pos="right" :disabled="!ready" :loading="submitting" />
      </div>
    </form>
  </div>
</template>
