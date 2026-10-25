<script setup lang="ts">
import { computed, ref } from 'vue'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import { useToast } from 'primevue/usetoast'
import { PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import { submitFreeRequest } from '@/api/rest'
import CertificationFields from '@/components/CertificationFields.vue'
import TermsAcceptance from '@/components/TermsAcceptance.vue'

/** Requesting a free license: the organization, its contact, the figures it certifies, and the click-through. */
const toast = useToast()

const organization = ref('')
const contactName = ref('')
const contactEmail = ref('')
const annualRevenue = ref<number | null>(null)
const capitalRaised = ref<number | null>(null)
const intendedUse = ref('')
const accepted = ref(false)
const submitting = ref(false)
const submitted = ref(false)

const ready = computed(() => organization.value.trim() && contactName.value.trim() && contactEmail.value.includes('@')
    && annualRevenue.value !== null && capitalRaised.value !== null && intendedUse.value.trim() && accepted.value)

async function submit() {
  if (!ready.value) {
    return
  }
  submitting.value = true
  try {
    await submitFreeRequest({
      organization: organization.value.trim(),
      contactName: contactName.value.trim(),
      contactEmail: contactEmail.value.trim(),
      annualRevenue: annualRevenue.value ?? 0,
      capitalRaised: capitalRaised.value ?? 0,
      intendedUse: intendedUse.value.trim(),
      acceptTerms: accepted.value
    })
    submitted.value = true
  } catch (err) {
    showErrorToast(toast, 'The request could not be submitted', err)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-2xl">
    <PageHeader title="Request a free license"
                description="Small organizations, below about $1M in annual revenue and about $3M raised, can use Kinotic Enterprise free. Minds Ignited reviews each request; an approved license is renewed yearly by re-certifying." />
    <div v-if="submitted" class="app-card-surface rounded-xl border p-5 text-sm">
      <p class="app-heading-text font-medium">Thank you, your request is in.</p>
      <p class="app-muted-text mt-2">
        We review requests within a few business days and email the result to {{ contactEmail }}. An approved license's key
        arrives in that email and in <RouterLink to="/account" class="underline">your account</RouterLink>.
      </p>
    </div>
    <form v-else class="flex flex-col gap-5" @submit.prevent="submit">
      <section class="app-card-surface flex flex-col gap-5 rounded-xl border p-5">
        <h2 class="app-heading-text font-medium">The organization</h2>
        <div>
          <label for="organization" class="mb-2 block text-sm font-medium">Organization's legal name</label>
          <InputText id="organization" v-model="organization" class="w-full" autocomplete="organization" />
        </div>
        <div class="grid gap-5 sm:grid-cols-2">
          <div>
            <label for="contact-name" class="mb-2 block text-sm font-medium">Contact's name</label>
            <InputText id="contact-name" v-model="contactName" class="w-full" autocomplete="name" />
          </div>
          <div>
            <label for="contact-email" class="mb-2 block text-sm font-medium">Contact's email</label>
            <InputText id="contact-email" v-model="contactEmail" class="w-full" type="email" autocomplete="email" />
          </div>
        </div>
      </section>
      <section class="app-card-surface flex flex-col gap-5 rounded-xl border p-5">
        <h2 class="app-heading-text font-medium">What you certify</h2>
        <CertificationFields v-model:annual-revenue="annualRevenue" v-model:capital-raised="capitalRaised" v-model:intended-use="intendedUse" />
      </section>
      <TermsAcceptance v-model="accepted" />
      <div class="flex justify-end">
        <Button type="submit" label="Submit request" :disabled="!ready" :loading="submitting" />
      </div>
    </form>
  </div>
</template>
