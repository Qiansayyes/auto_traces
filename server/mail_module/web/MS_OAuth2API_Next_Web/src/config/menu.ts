export interface MenuItem {
  index: string
  title: string
  routeName?: string
  disabled?: boolean
  children?: MenuItem[]
}

export const menuItems: MenuItem[] = [
  {
    index: '1',
    title: '邮箱管理',
    routeName: 'email'
  }
]