# Agent 离线回归评测

运行 `mvn -f server/pom.xml -Dtest=AgentGoldenEvaluationTest test`，结果写入 `server/target/evaluation/report.json` 与 `report.md`。普通 `mvn test` / `mvn verify` 也会执行该评测，CI 保存报告为 `agent-evaluation` 构件。

固定样例位于 `server/src/test/resources/evaluation/golden-cases.json`。目前有 4 个检索样例和 17 个证据校验样例，包括中文语音、数字 OCR、视觉描述、组合来源、缺失小节、伪造引用、空引用、错误来源和负数时间戳。

评测直接调用实际的 `HybridRetriever` 排序与 `EvidenceValidator` 校验。外部模型与 Qdrant 返回值由固定输入替代，不需要账户、密钥、网络或运行中的中间件，不会产生模型费用。

| 指标 | 含义 | 当前门槛 |
|---|---|---:|
| Recall@K | 标注相关分块中进入前 K 名的比例，按样例取平均 | 1.0 |
| MRR | 首个相关分块排名倒数，按样例取平均 | 1.0 |
| Evidence verdict accuracy | 对应通过 / 拒绝标签的判定准确率 | 1.0 |

证据判定必须与全部标签一致，不能通过降低门槛放过错误接受的证据。检索门槛随样例版本记录。失败时仍会写出逐样例结果，便于检查哪个改动影响了召回、排序或判定。

这些分数表示小规模固定输入的回归一致性，不能代表真实模型的准确率、语义推理能力或线上质量。它不会评估提示词、模型输出波动、实际 Embedding / Qdrant 召回效果或完整 ASR 链路。上线前仍需使用真实音视频、人工标注和实际模型做端到端评测。

增加样例时，给样例分配唯一 ID，注明真实上下文、预期相关分块或预期通过状态。检索分数应与查询和标签保持一致；先审查标签再更新门槛，不因新实现的结果反向修改标签。含凭证或用户私有素材的样本不要进入仓库。
