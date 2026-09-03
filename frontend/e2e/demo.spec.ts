import { expect, test } from '@playwright/test'

test('demo user completes the purchase and ticket flow', async ({ page }) => {
  await page.goto('/')

  await expect(page.getByLabel('用户名或邮箱', { exact: true })).toHaveValue('admin')
  await expect(page.getByLabel('密码', { exact: true })).toHaveValue('Password123')

  const username = `e2e_${Date.now()}`
  await page.getByText('注册', { exact: true }).click()
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('邮箱', { exact: true }).fill(`${username}@example.com`)
  await page.getByLabel('密码', { exact: true }).fill('Password123')
  await page.getByRole('button', { name: '注册并登录', exact: true }).click()
  await expect(page.getByText(`${username} · USER`)).toBeVisible()

  await page.getByRole('tab', { name: '活动购票', exact: true }).first().click()
  await page.locator('.event-card').first().click()
  await page.getByRole('button', { name: '选择座位' }).first().click()
  await page.locator('.seat-grid button:not(:disabled)').first().click()
  await page.getByRole('button', { name: /锁定 1 个座位并创建订单/ }).click()
  await expect(page.getByText('锁座成功，请在10分钟内完成支付')).toBeVisible()
  await page.locator('.el-drawer__close-btn').click()

  await page.getByRole('tab', { name: '我的订单', exact: true }).click()
  await page.getByRole('button', { name: '模拟支付' }).first().click()
  await expect(page.getByText('模拟支付成功，电子票已生成')).toBeVisible()

  await page.getByRole('tab', { name: '我的电子票', exact: true }).click()
  await expect(page.locator('.ticket-card svg').first()).toBeVisible()
})
