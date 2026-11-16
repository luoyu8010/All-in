# All In 1.2.51-test（57）本地诊断候选

基于56保留全部兼容修补，本版补齐Antigravity调用对照记录和冷却提示，便于调查“普通测试成功、Agent调用失败”。不是客户403/429根因已经解决的声明，尚未公开发行。

## 查看与关联

在应用的“最近请求”中展开对应行，可看到新增的调用对照信息。标准模型接口与普通模型测试的响应头新增 `X-AllIn-Request-Id`；普通测试JSON也返回同一 `requestId`。该ID由网关生成，不采用客户端传入值；心跳工作线程交接后仍能对应账本。调用方需自行保存响应ID，网关不能强迫Eta写入其数据库。

新增 `callEvidence` 是可选、schemaVersion=1的白名单快照，落入现有账本并在最近请求/用量API读回。旧记录没有数据时显示“旧记录或该路径未采集”，不回填推测值。

记录范围为Antigravity的 `/api/test-model`、`/v1/chat/completions`、`/v1/messages`、`/v1/responses`、`/responses` 的实际转发尝试。入口分类只说明接口用途，不能证明调用方就是Eta；面板流式自测同样走模型API。

## 记录含义

- `path` / `method` / `entry`：固定白名单接口路径、POST、普通测试或模型API。
- `requestId`：网关生成的调用ID。尝试行与逻辑终态可能重复同一ID，终态不计新尝试。
- `accountRef`：从供应商与账号本地ID派生的哈希标识，仅用于对照是否同一账号，不包含邮箱、访问凭据。已有账本的兼容accountId字段不被改写。
- `sentModel` / `physicalSends`：实际尝试发送的具体变体与生成请求发送计数；不是供应商执行或成功交付的证明。本地冷却未发送时model为null、发送数0。原有model统计口径保持，另列此字段避免把家族名当具体变体。
- `messageCount` / `systemPresent` / `toolCount` / `stream` / `effort`：规范化后的结构信息。明确标记为normalized_request，不冒充未改写的原始HTTP载荷；不存提示词、工具名称、参数或消息正文。
- `upstreamStatus` / `googleStatus` / `googleReason`：实际收到的HTTP状态与允许列表内的Google状态/原因码。不是完整抓包；未取得的内容保留null。该字段主要覆盖HTTP响应，流内后置错误仍结合原账本错误与终态判断。
- `upstreamRetryAfterSeconds`：上游等待提示经安全校验后的秒数；没有或无效为null，不用本地默认60秒冒充。
- `localCooldownRemainingMs`：本次被本地冷却拦截时实测的剩余毫秒数。
- `cooldownOriginRequestId` / `cooldownOriginStatus` / `cooldownOriginGoogleStatus` / `cooldownOriginRetryAfterSeconds`：链接到触发当前冷却的先前请求及其等待提示，缺失则未知。

新增快照有长度/类型/枚举限制，回读重新过滤未知字段，不保留任意metadata/headers。快照冻结后不随迟到回调变化；其体积计入现有账本内存预算。额外冷却来源缓存最多512项，缓存满时来源可缺失，实际冷却规则不受影响。

## 冷却保护保持

普通测试遇到既有本地冷却时，显示“本地限流冷却”和剩余时间，说明此前收到限流后网关暂缓发送，避免继续提示预算设置错误。仍遵守已有等待、账号/模型隔离、取消及未知执行不重放规则；不缩短/清除冷却，不自动重登、换身份或发送新推理。

## 本地验证

28项相关JVM/HTTP任务、47项面板回归、Android API34隔离240项运行时通过。新增网关入口30检查、白名单/冻结/持久化/隐私61检查，Android实际HTTP三步对照新增14检查。Release Lint与原证书包及覆盖升级结果见交付目录的最终报告。全部Google响应是合成字节，未使用真实账号或进行真实Google推理；不扩张为完整项目全量或客户现场已恢复。
