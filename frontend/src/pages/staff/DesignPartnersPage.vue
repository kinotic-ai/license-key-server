<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import Button from 'primevue/button'
import InputText from 'primevue/inputtext'
import Select from 'primevue/select'
import Tag from 'primevue/tag'
import { useToast } from 'primevue/usetoast'
import { FormDrawer, PageHeader, showErrorToast } from '@kinotic-ai/frontend-common'
import type { CaseStudyStatus, DesignPartner, DesignPartnerPublication, PublicationType } from '@/api/model'
import { staffDesignPartnerService } from '@/api/services'
import { formatDate } from '@/util/format'

/**
 * Every design partner: its license, approval and the end of its discount, each thing that may be published about it
 * with whether the customer approved it, and its case study, published only once the customer approves it.
 */
const router = useRouter()
const toast = useToast()

const PUBLICATION_LABELS: Record<PublicationType, string> = { LOGO: 'Name and logo', USE_DESCRIPTION: 'Use description', CASE_STUDY: 'Case study' }
const CASE_STUDY_OPTIONS: { label: string, value: CaseStudyStatus }[] = [
  { label: 'Not started', value: 'NOT_STARTED' },
  { label: 'In progress', value: 'IN_PROGRESS' },
  { label: 'Published', value: 'PUBLISHED' }
]

const partners = ref<DesignPartner[]>([])
const loading = ref(true)

// the publication whose approval by the customer is being recorded, and who at the customer approved it
const approving = ref<DesignPartnerPublication | null>(null)
const approvedBy = ref('')
const saving = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  try {
    partners.value = await staffDesignPartnerService.findDesignPartners()
  } catch (err) {
    showErrorToast(toast, 'The design partners could not be loaded', err)
  } finally {
    loading.value = false
  }
}

function replace(updated: DesignPartner) {
  partners.value = partners.value.map(partner => partner.id === updated.id ? updated : partner)
}

async function setCaseStudy(partner: DesignPartner, status: CaseStudyStatus) {
  try {
    replace(await staffDesignPartnerService.setCaseStudyStatus(partner.id, status))
  } catch (err) {
    showErrorToast(toast, 'The case study status could not be set', err, { life: 8000 })
    await load()
  }
}

async function recordApproval() {
  if (!approving.value || !approvedBy.value.trim()) {
    return
  }
  saving.value = true
  try {
    replace(await staffDesignPartnerService.approvePublication(approving.value.id, approvedBy.value.trim()))
    approving.value = null
  } catch (err) {
    showErrorToast(toast, 'The approval could not be recorded', err, { life: 8000 })
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <PageHeader title="Design partners"
                description="Each design partner, what may be published about it once the customer approves it, and its case study." />
    <div v-if="loading" class="app-muted-text text-sm">Loading…</div>
    <p v-else-if="!partners.length" class="app-muted-text text-sm">No design partners yet.</p>
    <div v-else class="flex flex-col gap-5">
      <section v-for="partner in partners" :key="partner.id" class="app-card-surface rounded-xl border p-5">
        <div class="flex flex-wrap items-center justify-between gap-3">
          <a href="#" class="app-heading-text text-lg font-medium hover:underline" @click.prevent="router.push(`/customers/${partner.customerId}`)">
            {{ partner.organization }}
          </a>
          <Select :model-value="partner.caseStudyStatus" :options="CASE_STUDY_OPTIONS" option-label="label" option-value="value"
                  aria-label="Case study" @update:model-value="setCaseStudy(partner, $event)" />
        </div>
        <dl class="mt-4 grid gap-4 text-sm sm:grid-cols-3">
          <div><dt class="app-muted-text">Approved</dt><dd class="app-heading-text mt-1">{{ formatDate(partner.approvedAt) }}</dd></div>
          <div>
            <dt class="app-muted-text">License</dt>
            <dd class="app-heading-text mt-1">{{ partner.licenseId ? 'Bought' : 'Subscription not started' }}</dd>
          </div>
          <div><dt class="app-muted-text">Discount ends</dt><dd class="app-heading-text mt-1">{{ formatDate(partner.discountEndsAt) }}</dd></div>
        </dl>
        <table class="mt-4 w-full text-left text-sm">
          <thead class="app-muted-text">
            <tr><th class="py-1 pr-4 font-medium">Publication</th><th class="py-1 pr-4 font-medium">Customer approval</th><th class="py-1" /></tr>
          </thead>
          <tbody>
            <tr v-for="publication in partner.publications" :key="publication.id" class="app-surface-divider border-t">
              <td class="py-2 pr-4">{{ PUBLICATION_LABELS[publication.type] }}</td>
              <td class="py-2 pr-4">
                <Tag v-if="publication.approvedAt" severity="success" :value="`Approved by ${publication.approvedBy}, ${formatDate(publication.approvedAt)}`" />
                <Tag v-else severity="secondary" value="Not approved" />
              </td>
              <td class="py-2 text-right">
                <Button v-if="!publication.approvedAt" label="Record approval" size="small" severity="secondary" outlined
                        @click="approving = publication; approvedBy = ''" />
              </td>
            </tr>
          </tbody>
        </table>
      </section>
    </div>

    <FormDrawer :visible="approving !== null" title="Record the customer's approval"
                description="Record who at the customer approved this publication. It may go out once approved." @close="approving = null">
      <form id="approval-form" class="flex flex-col gap-3" @submit.prevent="recordApproval">
        <label for="approved-by" class="text-sm font-medium">Approved by</label>
        <InputText id="approved-by" v-model="approvedBy" placeholder="Name and role at the customer" />
      </form>
      <template #footer>
        <Button type="button" label="Cancel" severity="secondary" outlined @click="approving = null" />
        <Button type="submit" form="approval-form" label="Record approval" :disabled="!approvedBy.trim()" :loading="saving" />
      </template>
    </FormDrawer>
  </div>
</template>
