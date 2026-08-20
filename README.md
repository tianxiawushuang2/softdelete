# Logic Delete Tool

用于分析 MyBatis `*.opengauss.xml` 中逻辑删除改造的影响范围，并在测试运行期间记录实际执行的 sqlId，最终生成覆盖差集。它是范围分析和测试辅助工具；**不会**修改 SQL、连接数据库、阻断执行、证明业务结果正确，也不提供 CI 或 Web 平台。

## 构建与快速体验

要求 JDK 8+ 和 Maven 3.6+：

```bash
mvn clean package
./scan.sh examples/sample-project examples/logic-delete-tables.yml target/sample-report
java -jar logic-delete-analyzer/target/logic-delete-analyzer.jar coverage \
  --affected target/sample-report/affected-sql-ids.txt \
  --runtime-log examples/sample-runtime-log/sql-runtime-trace.log \
  --static-detail target/sample-report/logic-delete-detail.csv \
  --output target/coverage-report
```

扫描生成 summary/detail/risk CSV、JSON、HTML 和 `affected-sql-ids.txt`。覆盖命令生成 covered、uncovered、runtime SQL ID CSV、汇总 CSV 和 HTML。

## 配置

参见 [`examples/logic-delete-tables.yml`](examples/logic-delete-tables.yml)。`tables` 声明 schema、表名、删除字段和值；`whitelist.physicalDelete` 可按 sqlId、负责人和到期日声明临时物理删除白名单；`scan` 配置 mapper 模式与排除路径。XML 默认使用 `compatible` 模式（允许 DOCTYPE 但禁止网络 DTD），严格拒绝 DOCTYPE 可传 `--xml-mode safe`。

## Runtime Starter

```xml
<dependency>
  <groupId>com.company.logicdelete</groupId>
  <artifactId>logic-delete-runtime-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
logic-delete:
  runtime:
    enabled: true
    app-name: order-service
    env: test
    output-path: ./logs/sql-runtime-trace.log
    sql-output-mode: HASH_AND_SAMPLE # NONE / HASH_ONLY / HASH_AND_SAMPLE / FULL
    sample-rate: 1.0
    collect-http-entry: true
```

Starter 自动注册 MyBatis Executor 插件和 HTTP Filter。RPC、MQ、JOB 可用 `LogicDeleteTrace.runWithEntry(type, name, runnable)` 或 `callWithEntry` 标注入口。日志为 JSON Lines，单条包含入口、sqlId、命令类型、SQL hash/可选样本、耗时和时间。

## 报告与风险

明细报告包括文件及行号、命令类型、引用表、受控表、删除字段/物理删除/动态 SQL 标志、风险级别、风险码和 SQL 证据。常见风险：`PHYSICAL_DELETE_ON_CONTROLLED_TABLE`、`MISSING_DELETE_FLAG`、`DYNAMIC_TABLE_OR_COLUMN`、`INCLUDE_NOT_FOUND`、`INSERT_MISSING_DELETE_FLAG` 和 `MULTI_TABLE_DELETE_FLAG_NEED_REVIEW`。

`containsDeleteFlag=true` 只代表文本中出现删除字段。动态 SQL、LEFT JOIN、复杂子查询和 UNKNOWN 结果必须人工复核；覆盖率也只代表 sqlId 曾被触发。
