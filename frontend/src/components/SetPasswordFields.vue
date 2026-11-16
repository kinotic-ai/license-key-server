<script setup lang="ts">
import { computed } from 'vue'
import IconField from 'primevue/iconfield'
import Password from 'primevue/password'

/**
 * A new password and its confirmation, for the pages that set one. Bind with {@code v-model:password} and
 * {@code v-model:confirm}; {@code submit} is emitted on Enter in the confirmation.
 */
const props = defineProps<{ password: string, confirm: string }>()
defineEmits<{
  (e: 'update:password', value: string): void
  (e: 'update:confirm', value: string): void
  (e: 'submit'): void
}>()

const mismatch = computed(() => !!props.confirm && props.confirm !== props.password)
</script>

<template>
  <IconField class="login-field">
    <Password :modelValue="password" class="login-password w-full" input-class="login-input w-full" placeholder="Password, at least 12 characters"
              :feedback="false" toggleMask autocomplete="new-password" @update:modelValue="$emit('update:password', $event ?? '')" />
  </IconField>
  <IconField class="login-field">
    <Password :modelValue="confirm" class="login-password w-full" input-class="login-input w-full" placeholder="Confirm password"
              :feedback="false" toggleMask autocomplete="new-password" :invalid="mismatch"
              @update:modelValue="$emit('update:confirm', $event ?? '')" @keyup.enter="$emit('submit')" />
  </IconField>
</template>
