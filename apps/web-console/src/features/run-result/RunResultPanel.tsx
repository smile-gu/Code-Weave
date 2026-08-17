import { AlertTriangle, CheckCircle2, Code2, FlaskConical, ShieldCheck } from 'lucide-react'
import type { RunResult } from '../../shared/api/types'

/** 展示 Coding、路径策略和 Maven 验证的结构化结论。 */
export function RunResultPanel({ result }: { result: RunResult }) {
  const passed = result.status === 'COMPLETED'
  return (
    <div className="result-content">
      <div className={`result-verdict ${passed ? 'verdict-success' : 'verdict-failed'}`}>
        {passed ? <CheckCircle2 size={22} /> : <AlertTriangle size={22} />}
        <div>
          <strong>{passed ? 'Coding Run 已通过' : 'Coding Run 未通过'}</strong>
          <p>{result.message}</p>
        </div>
      </div>

      <div className="result-grid">
        <section>
          <div className="result-label"><Code2 size={16} /> Coding</div>
          <strong>{result.coding?.status ?? '未执行'}</strong>
          <small>{result.coding?.initialized ? 'Claude Runtime 已初始化' : '未完成 Runtime 初始化'}</small>
        </section>
        <section>
          <div className="result-label"><ShieldCheck size={16} /> 路径门禁</div>
          <strong>{result.changePolicy ? (result.changePolicy.compliant ? '通过' : '存在违规') : '未执行'}</strong>
          <small>{result.changePolicy?.violations.length ?? 0} 条路径违规</small>
        </section>
        <section>
          <div className="result-label"><FlaskConical size={16} /> Maven</div>
          <strong>{result.maven?.status ?? '未执行'}</strong>
          <small>{result.maven?.message ?? '没有 Maven 验证结果'}</small>
        </section>
      </div>

      {result.maven?.tests && (
        <section className="test-summary">
          <div className="section-heading"><span>测试结果</span><small>{result.maven.tests.durationSeconds.toFixed(2)} 秒</small></div>
          <div className="metrics">
            <span><b>{result.maven.tests.total}</b> 总计</span>
            <span className="metric-pass"><b>{result.maven.tests.passed}</b> 通过</span>
            <span className="metric-fail"><b>{result.maven.tests.failures + result.maven.tests.errors}</b> 失败</span>
            <span><b>{result.maven.tests.skipped}</b> 跳过</span>
          </div>
          {result.maven.tests.failedTests.map((failure, index) => (
            <div className="test-failure" key={`${failure.testClass}-${failure.testName}-${index}`}>
              <strong>{failure.testClass ?? 'UnknownTest'} · {failure.testName ?? 'unknown'}</strong>
              <p>{failure.message ?? failure.type ?? '测试执行失败'}</p>
            </div>
          ))}
        </section>
      )}

      {!!result.changePolicy?.violations.length && (
        <section className="violation-list">
          <div className="section-heading"><span>路径违规</span><small>需要调整任务边界或代码修改</small></div>
          {result.changePolicy.violations.map((violation) => (
            <div key={`${violation.path}-${violation.violationType}`}>
              <code>{violation.path}</code>
              <span>{violation.message}</span>
            </div>
          ))}
        </section>
      )}
    </div>
  )
}
