import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import AdminPagination from './AdminPagination.vue'

// Stage UI-3b: paging driven by a real PageView — no client-side page-count
// guessing, so these tests build props straight from a PageView-shaped
// fixture rather than deriving them.

describe('AdminPagination', () => {
  it('renders the range summary and page indicator from a PageView', () => {
    const wrapper = mount(AdminPagination, {
      props: { page: 0, size: 20, totalElements: 45, totalPages: 3 },
    })

    expect(wrapper.find('#admin-pagination-summary').text()).toBe('Showing 1–20 of 45')
    expect(wrapper.text()).toContain('1 / 3')
  })

  it('first page: previous is disabled, next is not', () => {
    const wrapper = mount(AdminPagination, {
      props: { page: 0, size: 20, totalElements: 45, totalPages: 3 },
    })

    expect((wrapper.find('#admin-pagination-prev').element as HTMLButtonElement).disabled).toBe(true)
    expect((wrapper.find('#admin-pagination-next').element as HTMLButtonElement).disabled).toBe(false)
  })

  it('last page: next is disabled, previous is not', () => {
    const wrapper = mount(AdminPagination, {
      props: { page: 2, size: 20, totalElements: 45, totalPages: 3 },
    })

    expect((wrapper.find('#admin-pagination-next').element as HTMLButtonElement).disabled).toBe(true)
    expect((wrapper.find('#admin-pagination-prev').element as HTMLButtonElement).disabled).toBe(false)
  })

  it('emits update:page with the target page on next/previous clicks', async () => {
    const wrapper = mount(AdminPagination, {
      props: { page: 1, size: 20, totalElements: 45, totalPages: 3 },
    })

    await wrapper.find('#admin-pagination-next').trigger('click')
    expect(wrapper.emitted('update:page')?.[0]).toEqual([2])

    await wrapper.find('#admin-pagination-prev').trigger('click')
    expect(wrapper.emitted('update:page')?.[1]).toEqual([0])
  })

  it('empty result set: shows zero-based summary without dividing by zero', () => {
    const wrapper = mount(AdminPagination, {
      props: { page: 0, size: 20, totalElements: 0, totalPages: 0 },
    })

    expect(wrapper.find('#admin-pagination-summary').text()).toBe('Showing 0–0 of 0')
    expect(wrapper.text()).toContain('0 / 0')
  })
})
