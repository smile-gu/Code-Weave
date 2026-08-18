import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

/** 在每条组件测试后清理 DOM，避免跨用例状态污染。 */
afterEach(() => cleanup())
