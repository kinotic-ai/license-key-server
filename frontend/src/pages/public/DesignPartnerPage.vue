<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import SelectButton from 'primevue/selectbutton'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { BillingPeriod, Plan } from '@/api/model'
import { customerAccountService } from '@/api/services'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import TermsAcceptance from '@/components/TermsAcceptance.vue'
import { SESSION_STATE } from '@/states/LicenseSessionState'
import { SITE } from '@/states/site'
import { planName } from '@/util/format'

/**
 * Applying to be a design partner for the signed-in customer's account: the company's figures, which set its paid plan,
 * the billing period, what it uses Kinotic for, and the click-through with the design partner publicity. A company that
 * qualifies for Free is offered Free instead.
 */
const toast = useToast()

const PERIODS: { label: string, value: BillingPeriod }[] = [{ label: 'Monthly', value: 'MONTHLY' }, { label: 'Annual (save 10%)', value: 'ANNUAL' }]

const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
const billingPeriod = ref<BillingPeriod>('MONTHLY')
const intendedUse = ref('')
const accepted = ref(false)
const submitting = ref(false)
const submittedPlan = ref<Plan | null>(null)
const qualifiesForFree = ref(false)

watch([annualRevenue, capitalRaised], () => { qualifiesForFree.value = false })

const figuresGiven = computed(() => annualRevenue.value !== null && (annualRevenue.value >= 1_000_000 || capitalRaised.value !== null))
const ready = computed(() => figuresGiven.value && intendedUse.value.trim() !== '' && accepted.value)

async function submit() {
  if (!ready.value || submitting.value) {
    return
  }
  submitting.value = true
  try {
    const figures = { annualRevenue: annualRevenue.value!, capitalRaised: capitalRaised.value }
    const plan = await customerAccountService.findPlanFor(figures)
    if (plan === 'FREE') {
      qualifiesForFree.value = true
    } else {
      const application = await customerAccountService.applyAsDesignPartner(
          { figures, billingPeriod: billingPeriod.value, intendedUse: intendedUse.value.trim() }, SITE.termsVersion)
      submittedPlan.value = application.plan
    }
  } catch (err) {
    showErrorToast(toast, 'The application could not be submitted', err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="Apply to be a design partner"
                description="Design partners get half off any paid plan for three years, then their locked price on the same terms for as long as their license stays active. In return, they let us tell people how they use Kinotic. We review every application." />
    <div v-if="submittedPlan" class="app-card-surface rounded-xl border p-5 text-sm">
      <p class="app-heading-text font-medium">Thank you, your application is in.</p>
      <p class="app-muted-text mt-2">
        You applied on the {{ planName(submittedPlan) }} plan. We will email {{ SESSION_STATE.participant?.email }} once we have
        reviewed it, with the link to start your subscription if it is approved.
      </p>
    </div>
    <form v-else class="flex flex-col gap-5" @submit.prevent="submit">
      <section class="app-card-surface flex flex-col gap-5 rounded-xl border p-5">
        <h2 class="app-heading-text font-medium">Your company</h2>
        <CompanyFiguresFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" />
        <Message v-if="qualifiesForFree" severity="info">
          Your company qualifies for a free license, so it is not a design partner on a paid plan.
          <RouterLink to="/free" class="font-medium underline">Request a free license</RouterLink> instead.
        </Message>
        <div>
          <span class="mb-2 block text-sm font-medium">Billing</span>
          <SelectButton v-model="billingPeriod" :options="PERIODS" option-label="label" option-value="value" :allow-empty="false" />
        </div>
        <div>
          <label for="intended-use" class="mb-2 block text-sm font-medium">What do you use Kinotic for?</label>
          <Textarea id="intended-use" v-model="intendedUse" class="w-full" rows="4" auto-resize />
        </div>
      </section>
      <TermsAcceptance v-model="accepted" publicity="DESIGN_PARTNER" />
      <div class="flex justify-end">
        <Button type="submit" label="Apply" :disabled="!ready" :loading="submitting" />
      </div>
    </form>
  </div>
</template>
