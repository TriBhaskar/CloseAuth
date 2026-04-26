import { ref, watchEffect } from 'vue'

const isDark = ref(
  window.matchMedia('(prefers-color-scheme: dark)').matches
)

watchEffect(() => {
  document.documentElement.classList.toggle('dark', isDark.value)
})

export function useColorScheme() {
  const toggle = () => { isDark.value = !isDark.value }
  return { isDark, toggle }
}

