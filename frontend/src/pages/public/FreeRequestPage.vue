<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import Button from 'primevue/button'
import Message from 'primevue/message'
import { useToast } from 'primevue/usetoast'
import { CircleCheck, MailCheck, ShieldCheck } from '@lucide/vue'
import { DashboardSection, PageHeader, TINTS, showErrorToast } from '@kinotic-ai/frontend-common'
import type { Plan } from '@/api/model'
import { customerAccountService } from '@/api/services'
import { planName } from '@/util/format'
import { SESSION_STATE } from '@/states/LicenseSessionState'
import { SITE } from '@/states/site'
import CertificationFields from '@/components/CertificationFields.vue'
import NoticeCard from '@/components/NoticeCard.vue'
import NumberedSteps from '@/components/NumberedSteps.vue'
import TermsAcceptance from '@/components/TermsAcceptance.vue'

/**
 * Requesting a free license for the signed-in customer's account: the figures it certifies and the click-through.
 */
const QUALIFICATIONS = ['Under $1M in annual revenue, including any companies yours owns', 'Under $5M raised in total']
const STEPS = [
  { title: 'Send your request', detail: 'Certify your figures and say what you will build.' },
  { title: 'We review it', detail: 'Within a few business days; the result comes by email.' },
  { title: 'Add your cluster', detail: 'Its key turns on every Enterprise feature.' }
]

const toast = useToast()

const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
const intendedUse = ref('')
const accepted = ref(false)
const submitting = ref(false)
const submitted = ref(false)

// the paid plan the figures set, when they do not qualify for Free
const paidPlan = ref<Plan | null>(null)
watch([annualRevenue, capitalRaised], () => { paidPlan.value = null })

const ready = computed(() => annualRevenue.value !== null && capitalRaised.value !== null && intendedUse.value.trim() !== '' && accepted.value)

async function submit() {
  if (!ready.value || submitting.value) {
    return
  }
  submitting.value = true
  try {
    const figures = { annualRevenue: annualRevenue.value ?? 0, capitalRaised: capitalRaised.value ?? 0 }
    const plan = await customerAccountService.findPlanFor(figures)
    if (plan !== 'FREE') {
      paidPlan.value = plan
    } else {
      await customerAccountService.requestFreeLicense({ figures, intendedUse: intendedUse.value.trim() }, SITE.termsVersion)
      submitted.value = true
    }
  } catch (err) {
    showErrorToast(toast, 'The request could not be submitted', err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="max-w-5xl">
    <PageHeader title="Request a free license"
                description="Companies under $1M in annual revenue that have raised under $5M in total can use Kinotic Enterprise free. Minds Ignited reviews each request." />
    <NoticeCard v-if="submitted" :icon="MailCheck" :tint="TINTS.green" title="Thank you, your request is in.">
      We review requests within a few business days and email the result to {{ SESSION_STATE.participant?.email }}. Once
      approved, add your cluster under Licenses to get its key.
      <template #actions>
        <RouterLink to="/account"><Button label="Back to your licenses" severity="secondary" outlined /></RouterLink>
      </template>
    </NoticeCard>
    <div v-else class="grid items-start gap-5 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <form class="flex min-w-0 flex-col gap-5" @submit.prevent="submit">
        <DashboardSection :icon="ShieldCheck" :tint="TINTS.purple" title="What you certify"
                          :description="`${SESSION_STATE.participant?.name} <${SESSION_STATE.participant?.email}> certifies these for your organization.`">
          <div class="flex flex-col gap-5 p-5">
            <CertificationFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" v-model:intended-use="intendedUse" />
            <Message v-if="paidPlan" severity="warn">
              Your company's figures fit the {{ planName(paidPlan) }} plan, which is bought rather than requested.
              <RouterLink :to="`/buy?plan=${paidPlan.toLowerCase()}&billing=monthly`" class="font-medium underline">Buy {{ planName(paidPlan) }}</RouterLink>
            </Message>
          </div>
        </DashboardSection>
        <TermsAcceptance v-model="accepted" publicity="CUSTOMER_NAME" />
        <div class="flex justify-end">
          <Button type="submit" label="Submit request" :disabled="!ready" :loading="submitting" />
        </div>
      </form>

      <aside class="app-card-surface flex flex-col gap-5 rounded-xl border p-5 text-sm">
        <div>
          <h2 class="app-heading-text font-medium">Who qualifies</h2>
          <ul class="mt-3 flex flex-col gap-2">
            <li v-for="qualification in QUALIFICATIONS" :key="qualification" class="flex gap-2">
              <CircleCheck :size="16" :stroke-width="1.75" class="mt-0.5 shrink-0 text-green-600 dark:text-green-400" aria-hidden="true" />
              <span class="app-muted-text">{{ qualification }}</span>
            </li>
          </ul>
        </div>
        <div class="app-surface-divider border-t pt-5">
          <h2 class="app-heading-text font-medium">How it works</h2>
          <NumberedSteps :steps="STEPS" class="mt-3 flex flex-col gap-4" />
        </div>
        <p class="app-subtle-text app-surface-divider border-t pt-4 text-xs">
          An approved license renews each year once you confirm your figures.
        </p>
      </aside>
    </div>
  </div>
</template>
