<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import SelectButton from 'primevue/selectbutton'
import Textarea from 'primevue/textarea'
import { useToast } from 'primevue/usetoast'
import { BadgePercent, Building2, Lock, MailCheck, Megaphone } from '@lucide/vue'
import { DashboardSection, PageHeader, TINTS, showErrorToast } from '@kinotic-ai/frontend-common'
import type { BillingPeriod, Plan } from '@/api/model'
import { customerAccountService } from '@/api/services'
import CompanyFiguresFields from '@/components/CompanyFiguresFields.vue'
import NoticeCard from '@/components/NoticeCard.vue'
import NumberedSteps from '@/components/NumberedSteps.vue'
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

const TERMS = [
  { icon: BadgePercent, tint: TINTS.green, title: 'Half off for three years', detail: 'On any paid plan, from the day your subscription starts.' },
  { icon: Lock, tint: TINTS.blue, title: 'Then your locked price', detail: 'On the same terms, for as long as your license stays active.' },
  { icon: Megaphone, tint: TINTS.purple, title: 'In return, your story', detail: 'We tell people how you use Kinotic, and you approve everything before it goes out.' }
]
const STEPS = [
  { title: 'Apply', detail: 'Give your figures and what you use Kinotic for.' },
  { title: 'We review it', detail: 'Every application is reviewed; the result comes by email.' },
  { title: 'Start your subscription', detail: 'The email links to payment at half off.' }
]
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
  <div class="max-w-5xl">
    <PageHeader title="Apply to be a design partner"
                description="Design partners get half off any paid plan for three years and let us tell people how they use Kinotic. We review every application." />
    <NoticeCard v-if="submittedPlan" :icon="MailCheck" :tint="TINTS.green" title="Thank you, your application is in.">
      You applied on the {{ planName(submittedPlan) }} plan. We will email {{ SESSION_STATE.participant?.email }} once we have
      reviewed it, with the link to start your subscription if it is approved.
      <template #actions>
        <RouterLink to="/account"><Button label="Back to your licenses" severity="secondary" outlined /></RouterLink>
      </template>
    </NoticeCard>
    <div v-else class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <form class="flex min-w-0 flex-col gap-5" @submit.prevent="submit">
        <DashboardSection :icon="Building2" :tint="TINTS.blue" title="Your company" description="Your figures set the paid plan your application is for.">
          <div class="flex flex-col gap-5 p-5">
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
          </div>
        </DashboardSection>
        <TermsAcceptance v-model="accepted" publicity="DESIGN_PARTNER" />
        <div class="flex justify-end">
          <Button type="submit" label="Apply" :disabled="!ready" :loading="submitting" />
        </div>
      </form>

      <aside class="app-card-surface flex flex-col gap-5 rounded-xl border p-5 text-sm">
        <div>
          <h2 class="app-heading-text font-medium">What a design partner gets</h2>
          <ul class="mt-3 flex flex-col gap-4">
            <li v-for="term in TERMS" :key="term.title" class="flex gap-3">
              <span :class="['flex h-8 w-8 shrink-0 items-center justify-center rounded-lg', term.tint]">
                <component :is="term.icon" :size="16" :stroke-width="1.75" aria-hidden="true" />
              </span>
              <div>
                <div class="app-heading-text font-medium">{{ term.title }}</div>
                <p class="app-muted-text mt-1">{{ term.detail }}</p>
              </div>
            </li>
          </ul>
        </div>
        <div class="app-surface-divider border-t pt-5">
          <h2 class="app-heading-text font-medium">How it works</h2>
          <NumberedSteps :steps="STEPS" class="mt-3 flex flex-col gap-4" />
        </div>
      </aside>
    </div>
  </div>
</template>
