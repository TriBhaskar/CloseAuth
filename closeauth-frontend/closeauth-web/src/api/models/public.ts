// ── Public / Home Page Models ──────────────────────────────────────────────────

export interface HomeFeature {
  iconName: string
  title: string
  description: string
  bgClass: string
  textClass: string
}

export interface HomeStat {
  value: string
  label: string
}

export interface HomePageData {
  features: HomeFeature[]
  stats: HomeStat[]
  checklist: string[]
}
