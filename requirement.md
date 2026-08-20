# 逻辑删除影响分析与运行时 SQL 覆盖采集工具技术方案

## 1. 背景

现有 Java Spring Boot 项目运行多年，使用 MyBatis XML 管理大量 SQL。由于数据架构规范要求，原先部分表的物理删除需要改为逻辑删除。相关表已经新增 `delete_flag` 字段，并按规范赋值。

当前痛点：

1. 项目 SQL 复杂，存在大量 `.opengauss.xml` 文件。
2. 已改造 23 张表，后续每月约新增 10 张表。
3. 系统存在反射、RPC、定时任务、MQ、通用 DAO 等调用方式，人工难以完全识别影响接口。
4. 需要帮助开发梳理影响 SQL 范围。
5. 需要帮助测试确认哪些 SQL 在回归过程中被实际触发。
6. 当前明确不采用数据库权限兜底。
7. 当前明确不接入 CI。

本方案目标是开发一个可以实际运行的小工具，而不是一次性建设完整平台。

---

## 2. 总体结论

第一版采用两个独立组件：

```text
组件一：离线扫描工具
logic-delete-analyzer

组件二：运行时采集组件
logic-delete-runtime
```

其中：

```text
logic-delete-analyzer：
负责扫描项目中的 *.opengauss.xml，识别受控表引用点、MyBatis sqlId、SQL 类型、是否存在 delete_flag、是否存在物理 DELETE、是否存在动态 SQL 风险，并生成报告。

logic-delete-runtime：
作为 MyBatis 插件 / Spring Boot Starter 接入应用，在测试环境运行时采集实际触发的 sqlId、真实 SQL、入口信息，并输出运行时覆盖数据。
```

最终使用方式：

```text
离线扫描得到：受影响 sqlId 清单
测试运行得到：实际触发 sqlId 清单
两者对比得到：测试未覆盖 sqlId 清单
```

第一版不做：

```text
1. 不做数据库权限兜底。
2. 不做 CI 阻断。
3. 不做 IDEA 插件。
4. 不做 Web 平台。
5. 不承诺静态调用链 100% 完整。
6. 不自动修改业务 SQL。
```

---

## 3. 技术依据

MyBatis XML Mapper 官方支持 `<select>`、`<insert>`、`<update>`、`<delete>`、`<sql>`、`<include>` 等映射元素，因此离线扫描应以 XML 结构解析为基础，而不是简单全文 grep。

MyBatis 动态 SQL 官方支持 `if`、`choose`、`trim`、`where`、`set`、`foreach`、`bind` 等元素，因此静态工具必须识别动态 SQL 风险，不能把无法完全展开的 SQL 默认判定为安全。

MyBatis `Executor` 接口包含 `query` 和 `update` 方法，运行时采集 SQL 最适合从 Executor 层拦截。

MyBatis `MappedStatement` 提供 `getId()`、`getSqlCommandType()`、`getBoundSql(Object parameterObject)` 等方法，可用于获取 sqlId、SQL 类型和运行时 BoundSql。

Spring Boot 支持将自动配置关联到 starter，并通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 管理自动配置类；配置属性元数据也可放入 jar 的 `META-INF/spring-configuration-metadata.json`。

---

## 4. 交付物

最终交付以下 2 个 jar 包以及项目源代码：

```text
logic-delete-analyzer.jar
logic-delete-runtime-starter.jar
```

同时交付：

```text
README.md
配置样例 logic-delete-tables.yml
运行脚本样例 scan.sh / scan.bat
报告样例
测试用例
```

---

## 5. 整体架构

### 5.1 模块结构

推荐 Maven 多模块结构：

```text
logic-delete-tool
├── pom.xml
│
├── logic-delete-common
│   ├── 通用模型
│   ├── 配置模型
│   ├── JSON / YAML 工具
│   ├── SQL 文本处理工具
│   └── 公共异常定义
│
├── logic-delete-analyzer
│   ├── CLI 启动入口
│   ├── 项目文件扫描
│   ├── MyBatis XML 解析
│   ├── include 展开
│   ├── SQL 表名识别
│   ├── delete_flag 规则检查
│   ├── 静态报告生成
│   └── 覆盖差集报告生成
│
├── logic-delete-runtime-core
│   ├── MyBatis Interceptor
│   ├── SQL 运行时事件模型
│   ├── 入口上下文 EntryContext
│   ├── HTTP Filter
│   ├── 运行时日志输出
│   └── 运行时采集配置
│
└── logic-delete-runtime-starter
    ├── Spring Boot 自动配置
    ├── ConfigurationProperties
    ├── 自动注册 Interceptor
    ├── 自动注册 HTTP Filter
    └── starter 打包
```

---

## 6. 第一版功能边界

### 6.1 离线扫描工具必须支持

```text
1. 读取受控表配置文件。
2. 扫描指定项目目录下所有 *.opengauss.xml 文件。
3. 解析 mapper namespace。
4. 解析 select / insert / update / delete 标签。
5. 解析 sql id。
6. 生成完整 sqlId，即 namespace + "." + id。
7. 解析 <sql> 片段。
8. 尽量展开 <include refid="xxx" />。
9. 忽略大小写匹配受控表。
10. 忽略 SQL 注释中的表名。
11. 忽略字符串字面量中的表名。
12. 识别 SQL 类型。
13. 识别 SQL 中涉及的表。
14. 判断是否引用受控表。
15. 判断是否包含 delete_flag。
16. 判断是否存在物理 DELETE。
17. 识别 ${} 动态 SQL 风险。
18. 识别动态 include 风险。
19. 输出 CSV 报告。
20. 输出 HTML 报告。
21. 输出 affected-sql-ids.txt。
```

### 6.2 运行时采集组件必须支持

```text
1. 拦截 MyBatis Executor.query。
2. 拦截 MyBatis Executor.update。
3. 获取 MappedStatement.getId()。
4. 获取 MappedStatement.getSqlCommandType()。
5. 获取 BoundSql.getSql()。
6. 采集 HTTP 入口。
7. 支持手动设置 RPC / MQ / JOB 入口。
8. 输出运行时日志文件。
9. 支持配置开关。
10. 支持采样率。
11. 支持 SQL 脱敏或仅输出 SQL hash。
```

### 6.3 第一版不做

```text
1. 不自动修改 XML。
2. 不直接连接数据库。
3. 不做数据库权限处理。
4. 不接入 CI。
5. 不做 IDEA 插件。
6. 不做 Web 管理页面。
7. 不做完整静态调用链分析。
8. 不强依赖某个复杂 SQL Parser 完美解析 openGauss 方言。
```

---

## 7. 使用流程

### 7.1 准备受控表配置

文件名：

```text
logic-delete-tables.yml
```

样例：

```yaml
tables:
  - schema: public
    table: order_main
    deleteField: delete_flag
    normalValue: "0"
    deletedValue: "1"
    owner: order-team
    remark: 订单主表

  - schema: public
    table: order_item
    deleteField: delete_flag
    normalValue: "0"
    deletedValue: "1"
    owner: order-team
    remark: 订单明细表

  - schema: public
    table: customer_info
    deleteField: delete_flag
    normalValue: "0"
    deletedValue: "1"
    owner: crm-team
    remark: 客户信息表

whitelist:
  physicalDelete:
    - sqlId: com.xxx.TempMapper.deleteTempData
      reason: 临时数据清理
      owner: platform-team
      expireDate: 2026-12-31

  queryDeletedData:
    - sqlId: com.xxx.AdminMapper.queryDeletedOrders
      reason: 后台审计查询已删除数据
      owner: order-team
      expireDate: 2026-12-31

scan:
  mapperPattern:
    - "**/*.opengauss.xml"
  ignorePath:
    - "**/target/**"
    - "**/build/**"
    - "**/.git/**"
```

### 7.2 执行离线扫描

命令：

```bash
java -jar logic-delete-analyzer.jar \
  --project /path/to/project \
  --config /path/to/logic-delete-tables.yml \
  --output /path/to/report
```

输出：

```text
/path/to/report
├── logic-delete-summary.csv
├── logic-delete-detail.csv
├── logic-delete-risk.csv
├── logic-delete-report.html
├── affected-sql-ids.txt
└── scan-result.json
```

### 7.3 应用接入运行时采集

业务项目引入：

```xml
<dependency>
    <groupId>com.company.logicdelete</groupId>
    <artifactId>logic-delete-runtime-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

配置：

```yaml
logic-delete:
  runtime:
    enabled: true
    appName: order-service
    env: test
    outputType: file
    outputPath: ./logs/sql-runtime-trace.log
    sqlOutputMode: HASH_AND_SAMPLE
    sampleRate: 1.0
    collectHttpEntry: true
```

测试回归后得到：

```text
./logs/sql-runtime-trace.log
```

### 7.4 生成覆盖差集报告

命令：

```bash
java -jar logic-delete-analyzer.jar coverage \
  --affected /path/to/report/affected-sql-ids.txt \
  --runtime-log ./logs/sql-runtime-trace.log \
  --static-detail /path/to/report/logic-delete-detail.csv \
  --output /path/to/coverage-report
```

输出：

```text
/path/to/coverage-report
├── coverage-summary.csv
├── uncovered-sql-ids.csv
├── covered-sql-ids.csv
├── runtime-sql-ids.csv
└── coverage-report.html
```

---

## 8. 数据模型设计

### 8.1 ControlledTable

```java
public class ControlledTable {
    private String schema;
    private String table;
    private String normalizedTable;
    private String deleteField;
    private String normalValue;
    private String deletedValue;
    private String owner;
    private String remark;
}
```

归一化规则：

```text
1. table 转小写。
2. 去除首尾双引号。
3. 去除 schema 前缀后也保留一份短表名。
4. public.order_main 和 order_main 都应能匹配。
```

### 8.2 LogicDeleteConfig

```java
public class LogicDeleteConfig {
    private List<ControlledTable> tables;
    private WhiteListConfig whitelist;
    private ScanConfig scan;
}
```

### 8.3 WhiteListConfig

```java
public class WhiteListConfig {
    private List<WhiteListItem> physicalDelete;
    private List<WhiteListItem> queryDeletedData;
}
```

### 8.4 WhiteListItem

```java
public class WhiteListItem {
    private String sqlId;
    private String reason;
    private String owner;
    private LocalDate expireDate;
}
```

### 8.5 MapperStatementMeta

```java
public class MapperStatementMeta {
    private String filePath;
    private String fileName;
    private Integer startLine;
    private Integer endLine;
    private String namespace;
    private String id;
    private String fullSqlId;
    private String xmlTagName;
    private String rawXml;
    private String rawSqlText;
    private String expandedSqlText;
    private boolean hasDynamicSql;
    private boolean hasDynamicInclude;
    private boolean includeExpandSuccess;
}
```

### 8.6 SqlAnalysisResult

```java
public class SqlAnalysisResult {
    private String fullSqlId;
    private String filePath;
    private Integer startLine;
    private String sqlCommandType;
    private Set<String> referencedTables;
    private Set<String> matchedControlledTables;
    private boolean containsDeleteFlag;
    private boolean physicalDelete;
    private boolean parseSuccess;
    private List<RiskItem> risks;
    private String evidenceSql;
}
```

### 8.7 RiskItem

```java
public class RiskItem {
    private RiskLevel level;
    private String code;
    private String message;
    private String evidence;
}
```

### 8.8 RiskLevel

```java
public enum RiskLevel {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}
```

### 8.9 RuntimeSqlEvent

```java
public class RuntimeSqlEvent {
    private String appName;
    private String env;
    private String traceId;
    private String entryType;
    private String entryName;
    private String sqlId;
    private String sqlCommandType;
    private String sqlHash;
    private String sqlSample;
    private Long costMs;
    private String executeTime;
}
```

---

## 9. 离线扫描详细设计

### 9.1 文件扫描

输入参数：

```text
--project 项目根目录
--config 受控表配置
--output 输出目录
```

默认扫描：

```text
**/*.opengauss.xml
```

排除：

```text
**/target/**
**/build/**
**/.git/**
```

实现建议：

```java
public interface ProjectFileScanner {
    List<Path> scanMapperFiles(Path projectRoot, ScanConfig scanConfig);
}
```

第一版可以使用 Java NIO `Files.walk` 实现，不强依赖复杂 glob 库。

---

### 9.2 XML 解析

使用标准 XML 解析器：

```text
DocumentBuilderFactory
```

要求：

```text
1. 禁止加载外部 DTD。
2. 防止 XXE。
3. 保留元素顺序。
4. 能取到元素文本。
5. 能读取 namespace。
6. 能读取 select / insert / update / delete / sql 标签。
```

XXE 防护配置示例：

```java
DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
factory.setXIncludeAware(false);
factory.setExpandEntityReferences(false);
```

注意：很多 MyBatis XML 有 DOCTYPE。第一版如果禁用 DOCTYPE 后导致解析失败，需要提供兼容模式：

```text
--xml-mode safe
--xml-mode compatible
```

推荐默认：

```text
compatible
```

但仍要禁止网络访问外部 DTD。

---

### 9.3 Mapper namespace 解析

XML 示例：

```xml
<mapper namespace="com.xxx.OrderMapper">
    <select id="queryOrder">
        select * from order_main where id = #{id}
    </select>
</mapper>
```

解析结果：

```text
namespace = com.xxx.OrderMapper
id = queryOrder
fullSqlId = com.xxx.OrderMapper.queryOrder
```

---

### 9.4 SQL 节点解析

需要识别的节点：

```text
select
insert
update
delete
```

同时收集：

```text
sql
```

`<sql id="xxx">` 是可复用片段，不直接作为业务 sqlId 输出，但要供 `<include>` 展开使用。

---

### 9.5 include 展开规则

XML 示例：

```xml
<sql id="baseWhere">
    where delete_flag = 0
</sql>

<select id="queryOrder">
    select * from order_main
    <include refid="baseWhere"/>
</select>
```

展开结果：

```sql
select * from order_main
where delete_flag = 0
```

规则：

```text
1. refid 不带 namespace 时，默认当前 namespace。
2. refid 带 namespace 时，按完整 id 查找。
3. include 找不到时，标记 INCLUDE_NOT_FOUND。
4. refid 包含 ${} 时，标记 DYNAMIC_INCLUDE。
5. include 递归深度超过 10 层时，标记 INCLUDE_RECURSION_LIMIT。
```

实现接口：

```java
public interface IncludeExpander {
    ExpandResult expand(MapperStatementMeta statement, MapperXmlContext context);
}
```

---

### 9.6 动态 SQL 处理

动态标签包括：

```text
if
choose
when
otherwise
trim
where
set
foreach
bind
```

第一版不需要根据参数穷举所有 SQL 组合。

处理策略：

```text
1. 保留动态标签内的文本内容。
2. 将动态 SQL 拼接成一个近似 SQL 文本。
3. 只要出现动态标签，hasDynamicSql = true。
4. 只要出现 ${xxx}，标记 STRING_SUBSTITUTION。
5. 只要 from / join / update / delete 后面附近出现 ${xxx}，标记 DYNAMIC_TABLE_OR_COLUMN。
```

示例：

```xml
<select id="query">
    select *
    from ${tableName}
    where id = #{id}
</select>
```

风险：

```text
HIGH: DYNAMIC_TABLE_OR_COLUMN
message: SQL 中存在动态表名或动态列名，无法静态确认影响范围
```

---

## 10. SQL 文本预处理

### 10.1 去注释

需要去掉：

```sql
-- 单行注释
/* 多行注释 */
```

但不能删除字符串字面量里的内容。

输入：

```sql
select * from order_main -- from fake_table
where name = 'delete_flag'
```

输出：

```sql
select * from order_main
where name = 'delete_flag'
```

### 10.2 保护字符串字面量

SQL 中的表名如果出现在字符串里，不应视为表引用。

示例：

```sql
select 'order_main' as table_name
```

不应识别为引用 `order_main`。

实现建议：

```text
扫描 SQL 字符流：
1. 识别单引号字符串。
2. 识别双引号标识符。
3. 识别 -- 注释。
4. 识别 /* */ 注释。
5. 对字符串内容替换为空格占位。
6. 保留双引号标识符用于表名识别。
```

---

## 11. 表名识别策略

### 11.1 第一版采用混合策略

不建议第一版强依赖复杂 SQL Parser 完整解析 openGauss 方言。

第一版采用：

```text
XML 结构解析
+
SQL 词法扫描
+
关键字窗口识别
+
风险标记
```

优点：

```text
1. 能快速落地。
2. 对复杂 SQL 不会因为 parser 不支持方言而完全失败。
3. 能做到高召回。
4. 对无法确认的场景显式标 UNKNOWN。
```

---

### 11.2 需要识别的关键字

表引用通常出现在：

```text
FROM table
JOIN table
UPDATE table
DELETE FROM table
INSERT INTO table
MERGE INTO table
USING table
```

需要支持：

```text
schema.table
"schema"."table"
table alias
schema.table alias
public.order_main o
order_main o
order_main AS o
```

### 11.3 不应误判的场景

CTE 名称：

```sql
with order_main as (
    select * from other_table
)
select * from order_main
```

这里 `order_main` 可能是 CTE 名称，不一定是真实表。

第一版处理：

```text
1. 提取 WITH 后定义的 CTE 名称。
2. 如果 FROM/JOIN 后命中 CTE 名称，不作为真实表。
3. CTE 内部 SQL 仍然继续扫描真实表。
```

子查询：

```sql
select *
from (
    select * from order_main
) t
```

需要继续识别子查询中的 `order_main`。

函数：

```sql
select * from get_order_main()
```

第一版可以标记：

```text
MEDIUM: FUNCTION_LIKE_TABLE_REFERENCE
```

---

## 12. delete_flag 检查规则

### 12.1 第一版基础规则

只做基础文本级判断：

```text
只要 SQL 中包含对应受控表的 deleteField，即认为 containsDeleteFlag = true。
```

例如：

```sql
select * from order_main where delete_flag = 0
```

判定：

```text
containsDeleteFlag = true
```

### 12.2 风险提示

如果 SQL 引用了受控表，但没有出现 `delete_flag`：

```text
HIGH: MISSING_DELETE_FLAG
```

如果 SQL 引用了多个受控表，只出现一次 `delete_flag`：

```text
MEDIUM: MULTI_TABLE_DELETE_FLAG_NEED_REVIEW
```

示例：

```sql
select *
from order_main o
join order_item i on o.id = i.order_id
where o.delete_flag = 0
```

结果：

```text
order_main：可能已处理
order_item：需要人工确认
```

第一版不强制做别名级精确判断，但报告中必须提示。

---

## 13. 物理 DELETE 识别

规则：

```text
SQL 类型为 DELETE，且 DELETE FROM 后的表命中受控表，则 physicalDelete = true。
```

示例：

```sql
delete from order_main where id = #{id}
```

风险：

```text
HIGH: PHYSICAL_DELETE_ON_CONTROLLED_TABLE
```

白名单命中时：

```text
LOW: PHYSICAL_DELETE_WHITELISTED
```

白名单过期时：

```text
HIGH: PHYSICAL_DELETE_WHITELIST_EXPIRED
```

注意：

```text
本工具第一版不负责阻断执行。
只负责扫描报告和运行时采集。
```

---

## 14. INSERT 检查

如果受控表出现在 `INSERT INTO` 中：

```text
1. 检查 insert 列清单是否包含 delete_flag。
2. 如果没有列清单，标记 UNKNOWN。
3. 如果列清单不包含 delete_flag，标记 MEDIUM。
```

示例：

```sql
insert into order_main (id, name)
values (#{id}, #{name})
```

风险：

```text
MEDIUM: INSERT_MISSING_DELETE_FLAG
```

示例：

```sql
insert into order_main
values (...)
```

风险：

```text
UNKNOWN: INSERT_WITHOUT_COLUMN_LIST
```

---

## 15. UPDATE 检查

如果受控表出现在 `UPDATE` 中：

```text
1. 如果 SQL 中完全没有 delete_flag，标记 MEDIUM。
2. 如果是逻辑删除 SQL，即 set delete_flag = deletedValue，标记 INFO。
3. 如果是恢复 SQL，即 set delete_flag = normalValue，标记 MEDIUM，需要人工确认。
```

示例：

```sql
update order_main
set status = #{status}
where id = #{id}
```

风险：

```text
MEDIUM: UPDATE_WITHOUT_DELETE_FLAG_CONDITION
```

示例：

```sql
update order_main
set delete_flag = 1
where id = #{id}
```

结果：

```text
INFO: LOGICAL_DELETE_SQL
```

---

## 16. 报告设计

### 16.1 logic-delete-summary.csv

字段：

```text
table
owner
referencedSqlCount
selectCount
insertCount
updateCount
deleteCount
physicalDeleteCount
missingDeleteFlagCount
dynamicSqlCount
unknownRiskCount
highRiskCount
```

### 16.2 logic-delete-detail.csv

字段：

```text
table
filePath
namespace
sqlId
fullSqlId
xmlTag
sqlCommandType
startLine
endLine
referencedTables
matchedControlledTables
containsDeleteFlag
physicalDelete
hasDynamicSql
hasDynamicInclude
includeExpandSuccess
riskLevel
riskCodes
evidenceSql
```

### 16.3 logic-delete-risk.csv

只输出有风险的 SQL。

字段：

```text
riskLevel
riskCode
message
table
fullSqlId
filePath
startLine
evidence
suggestion
```

### 16.4 affected-sql-ids.txt

格式：

```text
com.xxx.OrderMapper.queryOrder
com.xxx.OrderMapper.updateOrder
com.xxx.OrderMapper.logicDeleteOrder
```

只输出命中受控表的完整 sqlId。

### 16.5 scan-result.json

用于后续扩展。

结构：

```json
{
  "scanTime": "2026-08-20T20:30:00",
  "project": "/path/to/project",
  "config": "/path/to/logic-delete-tables.yml",
  "summary": {
    "controlledTableCount": 23,
    "mapperFileCount": 128,
    "statementCount": 2048,
    "affectedSqlCount": 186,
    "highRiskCount": 17,
    "unknownRiskCount": 9
  },
  "details": []
}
```

---

## 17. HTML 报告设计

第一版 HTML 只需静态页面，不需要服务端。

页面结构：

```text
1. 总览
2. 按表汇总
3. 高风险 SQL
4. 未包含 delete_flag SQL
5. 物理 DELETE SQL
6. 动态 SQL / UNKNOWN SQL
7. 明细列表
```

每条明细至少显示：

```text
表名
sqlId
XML 文件
行号
SQL 类型
风险等级
风险原因
SQL 片段
```

颜色建议：

```text
HIGH：红色
UNKNOWN：橙色
MEDIUM：黄色
LOW：蓝色
INFO：灰色
```

---

## 18. 运行时采集组件设计

### 18.1 MyBatis Interceptor

拦截方法：

```java
@Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {
            MappedStatement.class,
            Object.class,
            RowBounds.class,
            ResultHandler.class
        }
    ),
    @Signature(
        type = Executor.class,
        method = "update",
        args = {
            MappedStatement.class,
            Object.class
        }
    )
})
public class LogicDeleteSqlTraceInterceptor implements Interceptor {

    private final RuntimeTraceProperties properties;
    private final RuntimeSqlEventReporter reporter;

    public LogicDeleteSqlTraceInterceptor(
            RuntimeTraceProperties properties,
            RuntimeSqlEventReporter reporter
    ) {
        this.properties = properties;
        this.reporter = reporter;
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!properties.isEnabled()) {
            return invocation.proceed();
        }

        Object[] args = invocation.getArgs();

        if (args == null || args.length == 0 || !(args[0] instanceof MappedStatement)) {
            return invocation.proceed();
        }

        MappedStatement ms = (MappedStatement) args[0];
        Object parameter = args.length > 1 ? args[1] : null;

        BoundSql boundSql = null;
        String rawSql = null;

        try {
            boundSql = ms.getBoundSql(parameter);
            rawSql = boundSql == null ? null : boundSql.getSql();
        } catch (Exception e) {
            rawSql = null;
        }

        long start = System.currentTimeMillis();

        try {
            Object result = invocation.proceed();

            long costMs = System.currentTimeMillis() - start;

            if (SampleDecider.hit(properties.getSampleRate())) {
                RuntimeSqlEvent event = buildEvent(ms, rawSql, costMs);
                reporter.report(event);
            }

            return result;
        } catch (Throwable ex) {
            long costMs = System.currentTimeMillis() - start;

            RuntimeSqlEvent event = buildEvent(ms, rawSql, costMs);
            event.setError(true);
            event.setErrorMessage(ex.getClass().getName() + ": " + ex.getMessage());
            reporter.report(event);

            throw ex;
        }
    }

    private RuntimeSqlEvent buildEvent(MappedStatement ms, String rawSql, long costMs) {
        EntrySnapshot entry = EntryContext.snapshot();

        RuntimeSqlEvent event = new RuntimeSqlEvent();
        event.setAppName(properties.getAppName());
        event.setEnv(properties.getEnv());
        event.setTraceId(entry.getTraceId());
        event.setEntryType(entry.getEntryType());
        event.setEntryName(entry.getEntryName());
        event.setSqlId(ms.getId());
        event.setSqlCommandType(ms.getSqlCommandType() == null ? null : ms.getSqlCommandType().name());
        event.setSqlHash(SqlHash.sha256(SqlNormalizer.normalize(rawSql)));
        event.setSqlSample(SqlSamplePolicy.sample(rawSql, properties));
        event.setCostMs(costMs);
        event.setExecuteTime(OffsetDateTime.now().toString());
        return event;
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
}
```

注意：

```text
1. 第一版只采集，不阻断。
2. 不输出参数值，避免敏感数据泄露。
3. SQL sample 默认做空白归一化。
4. SQL 太长时截断。
```

---

### 18.2 query 方法签名兼容

MyBatis Executor 可能存在多个 query 重载方法。

第一版先拦截：

```text
Executor.query(MappedStatement, Object, RowBounds, ResultHandler)
Executor.update(MappedStatement, Object)
```

后续可增加：

```text
Executor.query(MappedStatement, Object, RowBounds, ResultHandler, CacheKey, BoundSql)
```

---

### 18.3 EntryContext

用于保存当前请求入口。

```java
public final class EntryContext {

    private static final ThreadLocal<EntrySnapshot> HOLDER = new ThreadLocal<>();

    private EntryContext() {
    }

    public static void set(String entryType, String entryName, String traceId) {
        EntrySnapshot snapshot = new EntrySnapshot();
        snapshot.setEntryType(entryType);
        snapshot.setEntryName(entryName);
        snapshot.setTraceId(traceId);
        HOLDER.set(snapshot);
    }

    public static EntrySnapshot snapshot() {
        EntrySnapshot snapshot = HOLDER.get();
        if (snapshot == null) {
            EntrySnapshot unknown = new EntrySnapshot();
            unknown.setEntryType("UNKNOWN");
            unknown.setEntryName("UNKNOWN");
            unknown.setTraceId(TraceIdGenerator.generate());
            return unknown;
        }
        return snapshot;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
```

---

### 18.4 HTTP Filter

```java
public class LogicDeleteEntryTraceFilter implements Filter {

    private final RuntimeTraceProperties properties;

    public LogicDeleteEntryTraceFilter(RuntimeTraceProperties properties) {
        this.properties = properties;
    }

    @Override
    public void doFilter(
            ServletRequest request,
            ServletResponse response,
            FilterChain chain
    ) throws IOException, ServletException {

        if (!properties.isEnabled() || !properties.isCollectHttpEntry()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest http = (HttpServletRequest) request;

        String traceId = Optional.ofNullable(http.getHeader(properties.getTraceHeader()))
                .filter(s -> !s.trim().isEmpty())
                .orElseGet(TraceIdGenerator::generate);

        String entryName = http.getMethod() + " " + http.getRequestURI();

        try {
            EntryContext.set("HTTP", entryName, traceId);
            chain.doFilter(request, response);
        } finally {
            EntryContext.clear();
        }
    }
}
```

---

### 18.5 RPC / MQ / JOB 手动埋点

提供 API：

```java
public final class LogicDeleteTrace {

    public static void runWithEntry(
            String entryType,
            String entryName,
            Runnable runnable
    ) {
        String traceId = TraceIdGenerator.generate();
        try {
            EntryContext.set(entryType, entryName, traceId);
            runnable.run();
        } finally {
            EntryContext.clear();
        }
    }

    public static <T> T callWithEntry(
            String entryType,
            String entryName,
            Supplier<T> supplier
    ) {
        String traceId = TraceIdGenerator.generate();
        try {
            EntryContext.set(entryType, entryName, traceId);
            return supplier.get();
        } finally {
            EntryContext.clear();
        }
    }
}
```

使用示例：

```java
LogicDeleteTrace.runWithEntry("JOB", "OrderSyncJob.execute", () -> {
    orderSyncJob.execute();
});
```

MQ 示例：

```java
LogicDeleteTrace.runWithEntry("MQ", "OrderConsumer.onMessage", () -> {
    handleMessage(message);
});
```

RPC 示例：

```java
LogicDeleteTrace.callWithEntry("RPC", "OrderFacade.queryOrder", () -> {
    return orderFacade.queryOrder(request);
});
```

---

## 19. Runtime 配置

### 19.1 配置类

```java
@ConfigurationProperties(prefix = "logic-delete.runtime")
public class RuntimeTraceProperties {

    private boolean enabled = false;

    private String appName = "unknown-app";

    private String env = "unknown";

    private String outputType = "file";

    private String outputPath = "./logs/sql-runtime-trace.log";

    private String sqlOutputMode = "HASH_ONLY";

    private int maxSqlSampleLength = 2000;

    private double sampleRate = 1.0;

    private boolean collectHttpEntry = true;

    private String traceHeader = "X-Trace-Id";

    // getters and setters
}
```

### 19.2 sqlOutputMode

支持：

```text
NONE
HASH_ONLY
HASH_AND_SAMPLE
FULL
```

默认：

```text
HASH_ONLY
```

建议测试环境使用：

```text
HASH_AND_SAMPLE
```

不建议生产使用：

```text
FULL
```

---

## 20. Runtime 日志格式

第一版使用 JSON Lines。

每行一个 JSON：

```json
{
  "appName": "order-service",
  "env": "test",
  "traceId": "7d7f2f7e4c2a4b49",
  "entryType": "HTTP",
  "entryName": "GET /order/detail",
  "sqlId": "com.xxx.OrderMapper.queryById",
  "sqlCommandType": "SELECT",
  "sqlHash": "d4a1f7...",
  "sqlSample": "select * from order_main where id = ? and delete_flag = 0",
  "costMs": 13,
  "executeTime": "2026-08-20T20:30:00+08:00"
}
```

---

## 21. 覆盖差集设计

### 21.1 输入

```text
affected-sql-ids.txt
sql-runtime-trace.log
logic-delete-detail.csv
```

### 21.2 逻辑

```text
affectedSqlIds = 离线扫描命中受控表的 sqlId 集合
runtimeSqlIds = 运行时日志中出现过的 sqlId 集合

covered = affectedSqlIds ∩ runtimeSqlIds
uncovered = affectedSqlIds - runtimeSqlIds
unexpected = runtimeSqlIds - affectedSqlIds
```

### 21.3 输出 coverage-summary.csv

字段：

```text
affectedSqlCount
runtimeSqlCount
coveredSqlCount
uncoveredSqlCount
coverageRate
```

### 21.4 输出 uncovered-sql-ids.csv

字段：

```text
fullSqlId
table
sqlCommandType
riskLevel
riskCodes
filePath
startLine
suggestion
```

### 21.5 覆盖率计算

```text
coverageRate = coveredSqlCount / affectedSqlCount
```

注意：

```text
覆盖率只能说明测试触发过相关 sqlId。
覆盖率不能证明业务结果正确。
```

---

## 22. CLI 命令设计

### 22.1 scan 命令

默认命令：

```bash
java -jar logic-delete-analyzer.jar scan \
  --project /path/to/project \
  --config /path/to/logic-delete-tables.yml \
  --output /path/to/report
```

兼容省略 scan：

```bash
java -jar logic-delete-analyzer.jar \
  --project /path/to/project \
  --config /path/to/logic-delete-tables.yml \
  --output /path/to/report
```

参数：

```text
--project       必填，项目根目录
--config        必填，受控表配置
--output        必填，报告输出目录
--mapper        可选，mapper 文件模式，默认从配置读取
--xml-mode      可选，safe / compatible，默认 compatible
--verbose       可选，输出详细日志
```

### 22.2 coverage 命令

```bash
java -jar logic-delete-analyzer.jar coverage \
  --affected /path/to/affected-sql-ids.txt \
  --runtime-log /path/to/sql-runtime-trace.log \
  --static-detail /path/to/logic-delete-detail.csv \
  --output /path/to/coverage-report
```

参数：

```text
--affected       必填，离线扫描输出的受影响 sqlId 文件
--runtime-log    必填，运行时采集日志
--static-detail  可选，离线扫描明细，用于补充表名和风险信息
--output         必填，覆盖报告输出目录
```

---

## 23. 风险码定义

### 23.1 HIGH

```text
PHYSICAL_DELETE_ON_CONTROLLED_TABLE
受控表存在物理 DELETE。

MISSING_DELETE_FLAG
受控表查询或更新未发现 delete_flag。

DYNAMIC_TABLE_OR_COLUMN
存在 ${} 动态表名或动态列名，无法静态确认。

INCLUDE_NOT_FOUND
include 无法展开，且 SQL 可能涉及受控表。

PHYSICAL_DELETE_WHITELIST_EXPIRED
物理删除白名单已过期。
```

### 23.2 UNKNOWN

```text
SQL_PARSE_UNKNOWN
SQL 结构无法识别。

INSERT_WITHOUT_COLUMN_LIST
INSERT 没有列清单，无法判断 delete_flag 是否赋值。

DYNAMIC_INCLUDE
include refid 是动态表达式。

UNSUPPORTED_SQL_STRUCTURE
暂不支持的 SQL 结构。
```

### 23.3 MEDIUM

```text
UPDATE_WITHOUT_DELETE_FLAG_CONDITION
UPDATE 受控表但未发现 delete_flag 条件。

INSERT_MISSING_DELETE_FLAG
INSERT 受控表但列清单未包含 delete_flag。

MULTI_TABLE_DELETE_FLAG_NEED_REVIEW
SQL 涉及多个受控表，但 delete_flag 判断可能不完整。

LEFT_JOIN_DELETE_FLAG_NEED_REVIEW
LEFT JOIN 场景 delete_flag 位置需要人工确认。
```

### 23.4 LOW / INFO

```text
LOGICAL_DELETE_SQL
识别为逻辑删除 SQL。

HAS_DELETE_FLAG
SQL 中存在 delete_flag。

PHYSICAL_DELETE_WHITELISTED
物理 DELETE 命中有效白名单。
```

---

## 24. 关键实现细节

### 24.1 表名匹配

必须支持：

```text
order_main
ORDER_MAIN
public.order_main
PUBLIC.ORDER_MAIN
"order_main"
"public"."order_main"
```

统一转为：

```text
order_main
public.order_main
```

匹配策略：

```text
1. 完整 schema.table 优先。
2. 如果配置中 schema 为空，则按短表名匹配。
3. 如果配置中 schema 不为空，同时允许短表名匹配，但报告中提示 schema 未确认。
```

---

### 24.2 delete_flag 匹配

deleteField 也要忽略大小写：

```text
delete_flag
DELETE_FLAG
o.delete_flag
"O"."DELETE_FLAG"
```

第一版可以文本匹配：

```text
(?i)(^|[^a-zA-Z0-9_])delete_flag([^a-zA-Z0-9_]|$)
```

后续再做别名级精确判断。

---

### 24.3 SQL 类型判断

优先级：

```text
1. XML 标签名。
2. SQL 首个关键字。
```

示例：

```xml
<select id="deleteReturning">
    delete from order_main where id = #{id} returning *
</select>
```

这种 XML 标签是 select，但 SQL 实际是 delete。

报告中应显示：

```text
xmlTag = select
sqlCommandType = DELETE
risk = PHYSICAL_DELETE_ON_CONTROLLED_TABLE
```

---

### 24.4 SQL 归一化

用于 hash 和比较：

```text
1. 去注释。
2. 多个空白转一个空格。
3. 首尾 trim。
4. 转小写。
5. 参数占位统一为 ?。
```

示例：

```sql
SELECT  *
FROM order_main
WHERE id = #{id}
```

归一化：

```sql
select * from order_main where id = ?
```

---

## 25. 测试用例要求

### 25.1 XML 解析测试

覆盖：

```text
1. 普通 select。
2. 普通 insert。
3. 普通 update。
4. 普通 delete。
5. namespace + id 组合。
6. 同文件多个 sql 节点。
7. XML 包含 DOCTYPE。
8. XML 包含 CDATA。
```

### 25.2 include 展开测试

覆盖：

```text
1. 本 namespace include。
2. 跨 namespace include。
3. include 不存在。
4. include 循环。
5. include refid 动态。
```

### 25.3 动态 SQL 测试

覆盖：

```text
1. if。
2. choose / when / otherwise。
3. where。
4. trim。
5. foreach。
6. bind。
7. ${tableName}。
8. ${columnName}。
```

### 25.4 表名识别测试

覆盖：

```text
1. from order_main。
2. from public.order_main。
3. from "order_main"。
4. join order_item i。
5. left join order_item i。
6. update order_main。
7. delete from order_main。
8. insert into order_main。
9. merge into order_main。
10. using order_main。
11. 子查询。
12. CTE。
13. 注释里的表名不识别。
14. 字符串里的表名不识别。
```

### 25.5 delete_flag 检查测试

覆盖：

```text
1. where delete_flag = 0。
2. where o.delete_flag = 0。
3. and DELETE_FLAG = 0。
4. 多表只有一个 delete_flag。
5. 完全没有 delete_flag。
```

### 25.6 Runtime 测试

覆盖：

```text
1. query 被采集。
2. update 被采集。
3. delete 被采集。
4. insert 被采集。
5. HTTP entry 被采集。
6. UNKNOWN entry 被采集。
7. sqlOutputMode = NONE。
8. sqlOutputMode = HASH_ONLY。
9. sqlOutputMode = HASH_AND_SAMPLE。
10. sampleRate 生效。
```

---

## 26. 验收标准

### 26.1 离线扫描验收

给定测试项目：

```text
1. 能扫描所有 *.opengauss.xml。
2. 能输出所有 mapper namespace。
3. 能输出所有 select / insert / update / delete 的 fullSqlId。
4. 能识别配置中的受控表。
5. 能识别受控表物理 DELETE。
6. 能识别缺少 delete_flag 的 SQL。
7. 能识别 ${tableName} 动态 SQL。
8. 能生成 CSV / HTML / JSON / affected-sql-ids.txt。
```

### 26.2 运行时采集验收

启动测试 Spring Boot 应用后：

```text
1. 调用 HTTP 接口后生成 sql-runtime-trace.log。
2. 日志中包含 sqlId。
3. 日志中包含 SQL 类型。
4. 日志中包含 HTTP method + URI。
5. 日志中包含 traceId。
6. 日志中包含 sqlHash。
7. 配置 enabled=false 后不采集。
```

### 26.3 覆盖差集验收

给定：

```text
affected-sql-ids.txt 中有 10 个 sqlId
runtime log 中触发 7 个
```

输出：

```text
covered = 7
uncovered = 3
coverageRate = 70%
```

---

## 27. 开发顺序

### 阶段一：离线扫描最小可用版本

实现：

```text
1. 配置读取。
2. 文件扫描。
3. XML 解析。
4. namespace / id / fullSqlId 提取。
5. SQL 文本提取。
6. 表名大小写忽略匹配。
7. delete_flag 文本匹配。
8. 物理 DELETE 检测。
9. CSV 输出。
10. affected-sql-ids.txt 输出。
```

阶段一完成后，工具已经能给开发使用。

---

### 阶段二：报告增强

实现：

```text
1. HTML 报告。
2. risk.csv。
3. scan-result.json。
4. 动态 SQL 风险标记。
5. include 展开。
6. 白名单。
```

---

### 阶段三：运行时采集

实现：

```text
1. MyBatis Interceptor。
2. RuntimeSqlEvent。
3. FileRuntimeSqlEventReporter。
4. EntryContext。
5. HTTP Filter。
6. Spring Boot Starter 自动配置。
7. JSON Lines 日志输出。
```

---

### 阶段四：覆盖差集

实现：

```text
1. 读取 affected-sql-ids.txt。
2. 读取 sql-runtime-trace.log。
3. 提取 runtime sqlId。
4. 计算 covered / uncovered。
5. 输出 uncovered-sql-ids.csv。
6. 输出 coverage-report.html。
```

---

## 28. Codex 开发任务拆分

### Task 1：创建 Maven 多模块工程

创建：

```text
logic-delete-common
logic-delete-analyzer
logic-delete-runtime-core
logic-delete-runtime-starter
```

要求：

```text
1. Java 8 兼容优先。
2. 不依赖业务项目。
3. analyzer 可打 fat jar。
4. runtime-starter 可作为普通依赖引入 Spring Boot 项目。
```

---

### Task 2：实现 YAML 配置读取

输入：

```text
logic-delete-tables.yml
```

输出：

```java
LogicDeleteConfig
```

要求：

```text
1. 支持 tables。
2. 支持 whitelist。
3. 支持 scan.mapperPattern。
4. 支持 scan.ignorePath。
5. 配置错误时给出清晰错误信息。
```

---

### Task 3：实现 Mapper 文件扫描

输入：

```java
Path projectRoot
ScanConfig scanConfig
```

输出：

```java
List<Path> mapperFiles
```

要求：

```text
1. 默认只扫描 *.opengauss.xml。
2. 忽略 target / build / .git。
3. 路径分隔符兼容 Windows 和 Linux。
```

---

### Task 4：实现 MyBatis XML 解析

输入：

```java
Path mapperXml
```

输出：

```java
MapperXmlContext
```

要求：

```text
1. 提取 namespace。
2. 提取 select / insert / update / delete。
3. 提取 sql 片段。
4. 记录文件路径。
5. 尽量记录行号。
```

---

### Task 5：实现 include 展开

输入：

```java
MapperStatementMeta
MapperXmlContext
GlobalMapperRegistry
```

输出：

```java
ExpandResult
```

要求：

```text
1. 支持本 namespace refid。
2. 支持完整 namespace refid。
3. include 找不到要记录风险。
4. 动态 include 要记录风险。
```

---

### Task 6：实现 SQL 清洗和归一化

实现：

```java
SqlCommentStripper
SqlLiteralMasker
SqlNormalizer
SqlHash
```

要求：

```text
1. 去掉注释。
2. 避免识别字符串里的表名。
3. 归一化空白。
4. sha256 hash。
```

---

### Task 7：实现表名识别

实现：

```java
SqlTableExtractor
```

要求识别：

```text
FROM
JOIN
UPDATE
DELETE FROM
INSERT INTO
MERGE INTO
USING
```

输出：

```java
Set<String> referencedTables
```

---

### Task 8：实现风险分析

实现：

```java
LogicDeleteSqlAnalyzer
```

输入：

```java
MapperStatementMeta
ControlledTableRegistry
WhiteListConfig
```

输出：

```java
SqlAnalysisResult
```

要求：

```text
1. 判断是否命中受控表。
2. 判断是否包含 delete_flag。
3. 判断是否物理 DELETE。
4. 判断是否动态 SQL。
5. 判断是否白名单。
6. 生成 RiskItem。
```

---

### Task 9：实现 CSV / JSON / HTML 报告

实现：

```java
CsvReportWriter
JsonReportWriter
HtmlReportWriter
```

输出：

```text
logic-delete-summary.csv
logic-delete-detail.csv
logic-delete-risk.csv
affected-sql-ids.txt
scan-result.json
logic-delete-report.html
```

---

### Task 10：实现 CLI

支持：

```bash
java -jar logic-delete-analyzer.jar scan ...
java -jar logic-delete-analyzer.jar coverage ...
```

要求：

```text
1. 参数错误时输出 usage。
2. 执行完成后输出报告路径。
3. 异常时返回非 0 exit code。
```

---

### Task 11：实现 Runtime Interceptor

实现：

```java
LogicDeleteSqlTraceInterceptor
```

要求：

```text
1. 拦截 query。
2. 拦截 update。
3. 获取 sqlId。
4. 获取 SQL 类型。
5. 获取 BoundSql。
6. 输出 RuntimeSqlEvent。
```

---

### Task 12：实现 Runtime Reporter

实现：

```java
RuntimeSqlEventReporter
FileRuntimeSqlEventReporter
```

要求：

```text
1. JSON Lines 格式。
2. 文件不存在自动创建。
3. 写入失败不影响业务主流程。
4. 默认异步或轻量同步均可，第一版优先简单可靠。
```

---

### Task 13：实现 Spring Boot Starter

实现：

```java
LogicDeleteRuntimeAutoConfiguration
RuntimeTraceProperties
```

要求：

```text
1. enabled=true 时注册 Interceptor。
2. collectHttpEntry=true 时注册 HTTP Filter。
3. 配置属性支持 application.yml。
4. 不影响原有 MyBatis 配置。
```

---

### Task 14：实现 Coverage 报告

实现：

```java
CoverageAnalyzer
CoverageReportWriter
```

要求：

```text
1. 读取 affected-sql-ids.txt。
2. 读取 runtime JSON Lines。
3. 计算 covered / uncovered。
4. 输出 CSV 和 HTML。
```

---

## 29. README 最小内容

README 必须包含：

```text
1. 工具解决什么问题。
2. 工具不解决什么问题。
3. 配置文件示例。
4. 离线扫描命令。
5. 运行时 starter 接入方式。
6. 覆盖差集命令。
7. 报告字段说明。
8. 常见风险码说明。
9. 注意事项。
```

---

## 30. 注意事项

### 30.1 准确率原则

本工具必须遵循：

```text
能确认的明确输出。
不能确认的标 UNKNOWN。
解析失败不能当作安全。
动态 SQL 不能当作安全。
```

### 30.2 工具定位

本工具定位：

```text
范围分析工具
测试覆盖辅助工具
风险发现工具
```

不是：

```text
自动修复工具
数据库安全兜底工具
完整调用链证明工具
```

### 30.3 报告解读

报告中的：

```text
containsDeleteFlag = true
```

只表示 SQL 文本中出现了 delete_flag。

不等价于：

```text
逻辑删除语义一定正确。
```

尤其是：

```text
LEFT JOIN
EXISTS
NOT EXISTS
COUNT
GROUP BY
UNION
复杂子查询
```

仍需要人工审查。

---

## 31. 最小可运行版本定义

Codex 第一轮开发完成后，至少要能做到：

```text
1. 使用 logic-delete-tables.yml 配置 3 张测试表。
2. 扫描一个包含 5 个 mapper XML 的测试项目。
3. 输出所有命中的 fullSqlId。
4. 标出一个物理 DELETE 风险。
5. 标出一个缺少 delete_flag 风险。
6. 标出一个 ${tableName} 动态 SQL 风险。
7. 生成 affected-sql-ids.txt。
8. Spring Boot 测试项目引入 runtime starter 后能输出 sql-runtime-trace.log。
9. coverage 命令能输出未覆盖 sqlId。
```

达到以上条件，即认为第一版可运行。

---

## 32. 推荐第一版目录样例

```text
logic-delete-tool
├── README.md
├── pom.xml
├── examples
│   ├── logic-delete-tables.yml
│   ├── sample-project
│   │   └── src/main/resources/mapper/OrderMapper.opengauss.xml
│   └── sample-runtime-log/sql-runtime-trace.log
│
├── logic-delete-common
│   └── src/main/java/com/company/logicdelete/common
│
├── logic-delete-analyzer
│   └── src/main/java/com/company/logicdelete/analyzer
│
├── logic-delete-runtime-core
│   └── src/main/java/com/company/logicdelete/runtime
│
└── logic-delete-runtime-starter
    └── src/main/java/com/company/logicdelete/starter
```

---

## 33. 示例 XML 与预期结果

### 33.1 示例 XML

```xml
<mapper namespace="com.xxx.OrderMapper">

    <sql id="baseWhere">
        where delete_flag = 0
    </sql>

    <select id="queryById">
        select *
        from order_main
        where id = #{id}
          and delete_flag = 0
    </select>

    <select id="queryWithoutFlag">
        select *
        from order_main
        where id = #{id}
    </select>

    <delete id="deleteById">
        delete from order_main
        where id = #{id}
    </delete>

    <select id="dynamicTableQuery">
        select *
        from ${tableName}
        where id = #{id}
    </select>

</mapper>
```

### 33.2 预期结果

```text
com.xxx.OrderMapper.queryById
命中 order_main
包含 delete_flag
风险：INFO

com.xxx.OrderMapper.queryWithoutFlag
命中 order_main
未包含 delete_flag
风险：HIGH MISSING_DELETE_FLAG

com.xxx.OrderMapper.deleteById
命中 order_main
物理 DELETE
风险：HIGH PHYSICAL_DELETE_ON_CONTROLLED_TABLE

com.xxx.OrderMapper.dynamicTableQuery
存在动态表名
风险：HIGH DYNAMIC_TABLE_OR_COLUMN
```

---

## 34. 一句话目标

本工具第一版的核心目标是：

```text
用离线扫描找出受控逻辑删除表影响的 MyBatis sqlId；
用运行时采集证明测试实际触发了哪些 sqlId；
用差集报告告诉开发和测试哪些 sqlId 还没有被覆盖。
```

这就是当前阶段最能落地、投入产出比最高、准确率边界最清晰的技术方案。
