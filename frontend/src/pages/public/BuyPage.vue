<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import Button from 'primevue/button'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { Plan, PlanInfo } from '@/api/model'
import { customerAccountService } from '@/api/services'
import PlanCard from '@/components/PlanCard.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'
import { SITE } from '@/states/site'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import { formatMoney } from '@/util/format'

/**
 * Buying a plan for the signed-in customer's account: the plan, named by the link that led here or picked from the
 * plans, the click-through acceptance, then Stripe Checkout, where the payment details are entered.
 */
const route = useRoute()
const toast = useToast()

const plans = ref<PlanInfo[]>([])
const loading = ref(true)
const accepted = ref(false)
const submitting = ref(false)

const planId = computed(() => String(route.query.plan ?? '').toUpperCase() as Plan)
const plan = computed(() => plans.value.find(p => p.id === planId.value && !p.requiresApproval) ?? null)

onMounted(async () => {
  try {
    plans.value = await customerAccountService.findPlans()
  } catch (err) {
    showErrorToast(toast, 'The plans could not be loaded', err)
  } finally {
    loading.value = false
  }
})

async function submit() {
  if (!plan.value || !accepted.value || submitting.value) {
    return
  }
  submitting.value = true
  try {
    // payment details are entered on Stripe's page, never here
    window.location.assign(await customerAccountService.startCheckout(plan.value.id, SITE.termsVersion))
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
    <div v-if="loading" class="app-muted-text text-sm">Loading plans…</div>
    <div v-else-if="!plan" class="grid gap-6 md:grid-cols-2">
      <PlanCard v-for="choice in plans" :key="choice.id" :plan="choice" />
    </div>
    <form v-else class="flex flex-col gap-5" @submit.prevent="submit">
      <section class="app-card-surface rounded-xl border p-5 text-sm">
        <h2 class="app-heading-text font-medium">The licensee</h2>
        <p class="app-muted-text mt-2">
          Your account, with {{ SESSION_STATE.participant?.name }} &lt;{{ SESSION_STATE.participant?.email }}&gt; as the
          contact accepting the terms.
        </p>
      </section>
      <TermsAcceptance v-model="accepted" />
      <div class="flex justify-end">
        <Button type="submit" label="Continue to payment" icon="pi pi-arrow-right" icon-pos="right" :disabled="!accepted" :loading="submitting" />
      </div>
    </form>
  </div>
</template>
