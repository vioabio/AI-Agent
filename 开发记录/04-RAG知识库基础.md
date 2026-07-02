# 04 - RAG 知识库基础

## 概述

本节重点通过为 AI 恋爱大师应用添加知识问答功能，入门并实战企业主流的 AI 开发场景 —— RAG 知识库，掌握基于 Spring AI 框架实现 RAG 的两种方式。

具体内容包括：

- AI 恋爱知识问答需求分析
- RAG 概念（重点理解核心步骤）
- RAG 实战：Spring AI + 本地知识库
- RAG 实战：Spring AI + 云知识库服务

---

## 核心知识点

- **RAG（Retrieval-Augmented Generation）**：检索增强生成，结合信息检索技术和 AI 内容生成的混合架构
- **RAG 四大核心步骤**：文档收集和切割、向量转换和存储、文档过滤和检索、查询增强和关联
- **Embedding**：将高维离散数据转换为低维连续向量的过程
- **向量数据库**：专门存储和检索向量数据的数据库系统
- **ETL（抽取、转换、加载）**：DocumentReader、DocumentTransformer、DocumentWriter
- **Spring AI Advisors**：QuestionAnswerAdvisor 和 RetrievalAugmentationAdvisor

---

## 一、AI 知识问答需求分析

### AI 知识问答应用场景

- 教育场景：AI 针对学生的薄弱环节提供个性化辅导
- 电商场景：AI 根据用户肤质推荐适合的护肤方案
- 法律咨询：AI 能解答法律疑问，节省律师时间
- 金融场景：AI 为客户提供个性化理财建议
- 医疗场景：AI 辅助医生进行初步诊断咨询

### 恋爱大师应用的潜在需求

1. **恋爱问题咨询**：用户询问恋爱相关问题，系统提供个性化建议
2. **恋爱知识学习与培训**：系统化地学习恋爱技巧、情感沟通等知识
3. **恋爱社区与互动**：AI 对用户生成的内容进行分析整理，引导讨论
4. **恋爱交友匹配**：基于用户性格特点、兴趣爱好帮助匹配恋爱对象

### 本项目具体需求

实现**定制化恋爱知识问答**功能，让 AI 恋爱大师不仅能回答用户的情感困惑，还能推荐自己出品的相关课程和服务。

### 如何让 AI 获取知识？

不给 AI 提供特定领域的知识库，AI 可能会面临：
- 知识有限：不知道你的最新课程和内容
- 编故事：当 AI 不知道答案时，可能会"自圆其说"编造内容
- 无法个性化：不了解你的特色服务和回答风格
- 不会推销：不知道该在什么时候推荐你的付费课程和服务

---

## 二、RAG 概念

### 什么是 RAG？

RAG（Retrieval-Augmented Generation，检索增强生成）是一种结合信息检索技术和 AI 内容生成的混合架构，可以解决大模型的知识时效性限制和幻觉问题。

简单来说，RAG 就像给 AI 配了一个"小抄本"，让 AI 回答问题前先查一查特定的知识库来获取知识，确保回答是基于真实资料而不是凭空想象。

**RAG 与传统的区别：**

| 特性 | 传统大语言模型 | RAG 增强模型 |
|------|-------------|-----------|
| 知识时效性 | 受训练数据截止日期限制 | 可接入最新知识库 |
| 领域专业性 | 泛化知识，专业深度有限 | 可接入专业领域知识 |
| 响应准确性 | 可能产生"幻觉" | 基于检索的事实依据 |
| 可控性 | 依赖原始训练 | 可通过知识库定制输出 |
| 资源消耗 | 较高（需要大模型参数） | 模型可更小，结合外部知识 |

### RAG 工作流程（4 个核心步骤）

#### 1. 文档收集和切割
- 文档收集：从各种来源（网页、PDF、数据库等）收集原始文档
- 文档预处理：清洗、标准化文本格式
- 文档切割：将长文档分割成适当大小的片段（chunks）
  - 基于固定大小（如 512 个 token）
  - 基于语义边界（如段落、章节）
  - 基于递归分割策略

#### 2. 向量转换和存储
- 向量转换：使用 Embedding 模型将文本块转换为高维向量表示
- 向量存储：将生成的向量和对应文本存入向量数据库，支持高效的相似性搜索

#### 3. 文档过滤和检索
- 查询处理：将用户问题也转换为向量表示
- 过滤机制：基于元数据、关键词或自定义规则进行过滤
- 相似度搜索：在向量数据库中查找与问题向量最相似的文档块（余弦相似度、欧氏距离等）
- 上下文组装：将检索到的多个文档块组装成连贯上下文

#### 4. 查询增强和关联
- 提示词组装：将检索到的相关文档与用户问题组合成增强提示
- 上下文融合：大模型基于增强提示生成回答
- 源引用：在回答中添加信息来源引用
- 后处理：格式化、摘要或其他处理以优化最终输出

### RAG 相关技术名词

- **Embedding 和 Embedding 模型**：将高维离散数据转换为低维连续向量的过程。不同 Embedding 模型产生的向量表示和维度数不同，维度越高表达能力更强，但占用更多存储空间。
- **向量数据库**：专门存储和检索向量数据的数据库系统，如 Milvus、Pinecone、PGVector、Redis Stack 等。
- **召回**：信息检索中从大规模数据集中快速筛选出可能相关的候选项子集，强调速度和广度。
- **精排和 Rank 模型**：使用计算复杂度更高的算法对少量候选项进行精确排序。
- **混合检索策略**：结合关键词检索、语义检索、知识图谱等多种检索方法的优势。

---

## 三、RAG 实战：Spring AI + 本地知识库

标准的 RAG 开发步骤：
1. 文档收集和切割
2. 向量转换和存储
3. 切片过滤和检索
4. 查询增强和关联

简化后的 RAG 开发步骤：
1. 文档准备
2. 文档读取
3. 向量转换和存储
4. 查询增强

### 步骤 1：文档准备

准备用于给 AI 知识库提供知识的文档，推荐 Markdown 格式，尽量结构化。准备了 3 篇《恋爱常见问题和回答》文档（单身篇、恋爱篇、已婚篇）。

### 步骤 2：文档读取

Spring AI 提供了对 ETL 的支持，ETL 的 3 大核心组件：
- **DocumentReader**：读取文档，得到文档列表
- **DocumentTransformer**：转换文档，得到处理后的文档列表
- **DocumentWriter**：将文档列表保存到存储中

**引入依赖：**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-markdown-document-reader</artifactId>
    <version>1.0.0-M6</version>
</dependency>
```

**编写文档加载器类：**

```java
@Component
@Slf4j
class LoveAppDocumentLoader {

    private final ResourcePatternResolver resourcePatternResolver;

    LoveAppDocumentLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<Document> loadMarkdowns() {
        List<Document> allDocuments = new ArrayList<>();
        try {
            Resource[] resources = resourcePatternResolver.getResources("classpath:document/*.md");
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
                        .withHorizontalRuleCreateDocument(true)
                        .withIncludeCodeBlock(false)
                        .withIncludeBlockquote(false)
                        .withAdditionalMetadata("filename", fileName)
                        .build();
                MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, config);
                allDocuments.addAll(reader.get());
            }
        } catch (IOException e) {
            log.error("Markdown 文档加载失败", e);
        }
        return allDocuments;
    }
}
```

### 步骤 3：向量转换和存储

使用 Spring AI 内置的、基于内存读写的向量数据库 SimpleVectorStore 来保存文档。

```java
@Configuration
public class LoveAppVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Bean
    VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
        SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
                .build();

        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        simpleVectorStore.add(documents);
        return simpleVectorStore;
    }
}
```

### 步骤 4：查询增强

Spring AI 通过 Advisor 特性提供 RAG 功能：
- **QuestionAnswerAdvisor**：问答拦截器，更简单易用
- **RetrievalAugmentationAdvisor**：检索增强拦截器，更灵活强大

查询增强原理：当用户问题发送给 AI 模型时，Advisor 会查询向量数据库获取相关文档，将返回的响应附加到用户文本中，为 AI 模型提供上下文。

**使用 QuestionAnswerAdvisor 实现 RAG 对话：**

```java
@Resource
private VectorStore loveAppVectorStore;

public String doChatWithRag(String message, String chatId) {
    ChatResponse chatResponse = chatClient
            .prompt()
            .user(message)
            .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
            .advisors(new MyLoggerAdvisor())
            .advisors(new QuestionAnswerAdvisor(loveAppVectorStore))
            .call()
            .chatResponse();
    String content = chatResponse.getResult().getOutput().getText();
    log.info("content: {}", content);
    return content;
}
```

**测试代码：**

```java
@Test
void doChatWithRag() {
    String chatId = UUID.randomUUID().toString();
    String message = "我已经结婚了，但是婚后关系不太亲密，怎么办？";
    String answer = loveApp.doChatWithRag(message, chatId);
    Assertions.assertNotNull(answer);
}
```

---

## 四、RAG 实战：Spring AI + 云知识库服务

使用云知识库服务（如阿里云百炼）可以简化 RAG 开发，但缺点是需要额外费用以及数据隐私问题。

### 步骤 1：准备云知识库

1. 在阿里云百炼平台的"应用数据"模块中上传原始文档数据
2. 创建知识库，选择推荐配置
3. 导入数据到知识库，设置数据预处理规则（智能切分文档为切片）
4. 可手动编辑切片内容

### 步骤 2：RAG 开发

Spring AI Alibaba 利用 Spring AI 的 DocumentRetriever 特性，自定义文档检索方法，调用阿里灵积大模型 API 从云知识库中检索文档。

**创建文档检索器示例：**

```java
var dashScopeApi = new DashScopeApi("DASHSCOPE_API_KEY");

DocumentRetriever retriever = new DashScopeDocumentRetriever(dashScopeApi,
        DashScopeDocumentRetrieverOptions.builder()
                .withIndexName("你的知识库名称")
                .build());

List<Document> documentList = retriever.retrieve(new Query("谁是鱼皮"));
```

**使用 RetrievalAugmentationAdvisor 示例：**

```java
Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
        .queryTransformers(RewriteQueryTransformer.builder()
                .chatClientBuilder(chatClientBuilder.build().mutate())
                .build())
        .documentRetriever(VectorStoreDocumentRetriever.builder()
                .similarityThreshold(0.50)
                .vectorStore(vectorStore)
                .build())
        .build();

String answer = chatClient.prompt()
        .advisors(retrievalAugmentationAdvisor)
        .user(question)
        .call()
        .content();
```

**配置类初始化云知识库 Advisor：**

```java
@Configuration
@Slf4j
class LoveAppRagCloudAdvisorConfig {

    @Value("${spring.ai.dashscope.api-key}")
    private String dashScopeApiKey;

    @Bean
    public Advisor loveAppRagCloudAdvisor() {
        DashScopeApi dashScopeApi = new DashScopeApi(dashScopeApiKey);
        final String KNOWLEDGE_INDEX = "恋爱大师";
        DocumentRetriever documentRetriever = new DashScopeDocumentRetriever(dashScopeApi,
                DashScopeDocumentRetrieverOptions.builder()
                        .withIndexName(KNOWLEDGE_INDEX)
                        .build());
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }
}
```

**在 LoveApp 中使用云知识库 Advisor：**

```java
@Resource
private Advisor loveAppRagCloudAdvisor;

public String doChatWithRag(String message, String chatId) {
    ChatResponse chatResponse = chatClient
            .prompt()
            .user(message)
            .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId)
                    .param(CHAT_MEMORY_RETRIEVE_SIZE_KEY, 10))
            .advisors(new MyLoggerAdvisor())
            .advisors(loveAppRagCloudAdvisor)
            .call()
            .chatResponse();
    String content = chatResponse.getResult().getOutput().getText();
    log.info("content: {}", content);
    return content;
}
```

---

## 技术要点总结

1. **RAG 是解决大模型知识时效性和幻觉问题的核心技术**，通过检索外部知识库为 AI 提供准确上下文
2. **RAG 四大核心步骤**：文档收集切割 -> 向量转换存储 -> 文档过滤检索 -> 查询增强关联
3. **Spring AI 提供两种 RAG 实现方式**：
   - 基于本地知识库：使用 SimpleVectorStore 和 QuestionAnswerAdvisor
   - 基于云知识库：使用 DashScopeDocumentRetriever 和 RetrievalAugmentationAdvisor
4. **ETL 流程**：DocumentReader 读取文档 -> DocumentTransformer 转换 -> DocumentWriter 写入向量数据库
5. **QuestionAnswerAdvisor** 更适合简单场景，自动查询向量数据库并附加上下文
6. **RetrievalAugmentationAdvisor** 更灵活，支持自定义文档检索器、查询转换器等