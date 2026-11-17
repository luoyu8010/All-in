# 缓存用量与命中率

缓存由供应商执行，All In 读取其用量报告。使用时间长不保证命中；没有可靠缓存计数时显示未知，不以0%代替。

## 计数来源

- 标准Chat/Responses：prompt_tokens_details.cached_tokens、input_tokens_details.cached_tokens；缓存写入为cache_write_tokens。
- 兼容字段：DeepSeek prompt_cache_hit_tokens、顶层cached_tokens、num_cached_tokens，以及Anthropic缓存读取/创建字段和其驼峰别名。
- 同一回包的别名不相加。有效的非零标准字段优先，标准零值允许回退到明确报告的兼容字段；缺失、null、负数、小数和非数字不作为已知计数。
- Antigravity保留Google promptTokenCount与cachedContentTokenCount；普通和流式统一转换为包含缓存输入的Chat计数。流末用量快照仍会保留。没有返回的字段不补零。
- Trae固定插件把全零缓存报告视作未知；沿用此规则，不从积分扣费推算缓存命中。

## 分母

命中率为已报告的缓存读取量除以这些记录对应的完整输入量。Chat/Responses完整输入已经包含缓存；Anthropic input_tokens不含缓存读取与写入，需要加回两者。Chat回包使用Anthropic字段时，按固定Magpie口径：有标准缓存字段时保持完整输入；没有标准字段且缓存合计超过prompt_tokens时，按不含缓存的输入处理。这是兼容口径，不承诺能消除上游所有歧义。

写入缓存不等于读取缓存。仅报告写入、未报告读取时，保留写入数量，命中率维持未知。缓存统计的尝试覆盖率独立显示，不把未知请求塞进命中率分母。多次物理发送分开累计输入、缓存和完整性，避免重复累计或混用计数口径。

## 界面与历史

明确报告零命中时显示0%；没有已知分母/缓存读取时显示“—”。有正命中但一位小数会舍入为零时显示“<0.1%”。界面不再把完全未确认的缓存读量标成已确认的零。

旧账本没有cr字段的记录按未知处理，重新读取时生效；原文件不重写。历史明确保存的cr:0仍保留，无法据此重建过去被丢弃的真实命中数。修补不会凭空恢复旧缓存量。

## 已接入供应商范围

公共Chat解析适用于ZCode、WorkBuddy国内/国际、Raccoon、Loomy、DeepSeek、SiliconFlow、OpenAI、OpenRouter、腾讯混元、Cline和Copilot的相应路径；Codex与Responses桥保留标准缓存详情；Anthropic、MiniMax Code、ZCode赠送与WorkBuddy原生Messages保留原生计数。Qoder使用其官方回包已给的缓存数据，不猜缺失值。CatPaw与DeepSeek网页协议缺少可用缓存明细时保持未知。六个placeholder不作为已实现协议验收。

本说明针对本地源码修补。真实供应商的具体回包/模型差异仍需现场记录确认；合成回归不等于全部真实账号已经在线验收。
