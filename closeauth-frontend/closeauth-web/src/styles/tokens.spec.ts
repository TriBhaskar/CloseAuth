/// <reference types="node" />
// Narrowly-scoped reference rather than adding "node" to tsconfig.app.json's
// global types — this is the one test file in the app that touches real
// Node APIs (fs/path/process, to read tokens.css as text); the rest of the
// browser app shouldn't have ambient Node globals available to accidentally
// reference.
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'

// FE-1.1d: the token-completeness gate from spec §3.8's "Enforcement" list —
// "asserts every token declared in :root has a [data-theme="dark"]
// counterpart. A token defined in one mode and not the other is exactly how
// a component ends up working in light and broken in dark." Reads
// tokens.css as text (it's CSS, not a JS module) rather than importing it.
// Path is cwd-relative (vitest's cwd is this package's root) rather than
// import.meta.url-derived — vitest's module transform doesn't guarantee
// import.meta.url is a real file:// URL for every test file.
const source = readFileSync(join(process.cwd(), 'src/styles/tokens.css'), 'utf8')

function extractBlock(css: string, selector: string): string {
  const start = css.indexOf(selector)
  if (start === -1) throw new Error(`selector not found: ${selector}`)
  const openBrace = css.indexOf('{', start)
  let depth = 1
  let i = openBrace + 1
  while (depth > 0 && i < css.length) {
    if (css[i] === '{') depth++
    if (css[i] === '}') depth--
    i++
  }
  return css.slice(openBrace + 1, i - 1)
}

function propertyNames(block: string): Set<string> {
  const names = new Set<string>()
  for (const match of block.matchAll(/(--ca-[a-z-]+)\s*:/g)) {
    names.add(match[1]!)
  }
  return names
}

describe('tokens.css completeness', () => {
  const lightBlock = extractBlock(source, "[data-theme='light']")
  const darkBlock = extractBlock(source, "[data-theme='dark']")
  const lightProps = propertyNames(lightBlock)
  const darkProps = propertyNames(darkBlock)

  it('declares at least one --ca-* token in each mode (sanity check the extraction itself works)', () => {
    expect(lightProps.size).toBeGreaterThan(0)
    expect(darkProps.size).toBeGreaterThan(0)
  })

  it('every light-mode --ca-* token has a dark-mode counterpart', () => {
    const missing = [...lightProps].filter((p) => !darkProps.has(p))
    expect(missing, `declared in light but not dark: ${missing.join(', ')}`).toEqual([])
  })

  it('every dark-mode --ca-* token has a light-mode counterpart', () => {
    const missing = [...darkProps].filter((p) => !lightProps.has(p))
    expect(missing, `declared in dark but not light: ${missing.join(', ')}`).toEqual([])
  })
})
