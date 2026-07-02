# 05 - RAG 知识库进阶

## 概述

本节以 Spring AI 框架为例，学习 RAG 知识库应用开发的核心特性和高级知识点，并且掌握 RAG 最佳实践和调优技巧。

具体内容包括：

- RAG 核心特性（ETL、向量数据库、文档检索器、上下文查询增强器）
- RAG 最佳实践和调优
- RAG 高级知识（混合检索策略、大模型幻觉、高级 RAG 架构）

---

## 核心知识点

- **ETL 三大组件**：DocumentReader（抽取）、DocumentTransformer（转换）、DocumentWriter（加载）
- **TextSplitter**：文本分割器，TokenTextSplitter 是基于 Token 的文本分割器
- **MetadataEnricher**：元数据增强器，包括 KeywordMetadataEnricher 和 SummaryMetadataEnricher
- **PGVector**：PostgreSQL 的向量扩展，企业主流 RAG 方案之一
- **BatchingStrategy**：批处理策略，将大量文档分解为较小批次
- **预检索/检索/检索后三阶段**：查询转换、文档检索、文档排序与压缩
- **混合检索策略**：并行混合检索、级联混合检索、动态混合检索
- **大模型幻觉**：事实性幻觉、逻辑性幻觉、自洽性幻觉
- **高级 RAG 架构**：C-RAG、Self-RAG、RAPTOR、多智能体 RAG

---

## 一、RAG 核心特性

### 文档收集和切割 - ETL

ETL（抽取、转换、加载）是 RAG 文档处理的核心流程，Spring AI 提供了完整支持。

#### Document（文档）

Spring AI 中的文档不仅包含文本，还可以包含一系列元信息和多媒体附件。

#### ETL 三大组件

1. **DocumentReader（抽取）**：从数据源加载文档到内存
2. **DocumentTransformer（转换）**：根据需求将文档转换为适合后续处理的格式
3. **DocumentWriter（写入）**：将文档以特定格式保存到存储中

#### 抽取（Extract）- DocumentReader

```java
public interface DocumentReader extends Supplier<List<Document>> {
    default List<Document> read() {
        return get();
    }
}
```

Spring AI 内置的 DocumentReader 实现：
- JsonReader：读取 JSON 文档
- TextReader：读取纯文本文件
- MarkdownReader：读取 Markdown 文件
- PDFReader：读取 PDF 文档（PagePdfDocumentReader、ParagraphPdfDocumentReader）
- HtmlReader：读取 HTML 文档
- TikaDocumentReader：基于 Apache Tika 处理多种格式文档

**JsonReader 使用示例：**

```java
@Component
class MyJsonReader {
    private final Resource resource;

    MyJsonReader(@Value("classpath:products.json") Resource resource) {
        this.resource = resource;
    }

    List<Document> loadBasicJsonDocuments() {
        JsonReader jsonReader = new JsonReader(this.resource);
        return jsonReader.get();
    }

    List<Document> loadJsonWithSpecificFields() {
        JsonReader jsonReader = new JsonReader(this.resource, "description", "features");
        return jsonReader.get();
    }

    List<Document> loadJsonWithPointer() {
        JsonReader jsonReader = new JsonReader(this.resource);
        return jsonReader.get("/items");
    }
}
```

#### 转换（Transform）- DocumentTransformer

```java
public interface DocumentTransformer extends Function<List<Document>, List<Document>> {
    default List<Document> transform(List<Document> documents) {
        return apply(documents);
    }
}
```

转换器分为三类：

**1）TextSplitter 文本分割器**

TokenTextSplitter 是基于 Token 的文本分割器，考虑了语义边界来创建有意义的文本段落。

```java
@Component
class MyTokenTextSplitter {

    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    public List<Document> splitCustomized(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter(1000, 400, 10, 5000, true);
        return splitter.apply(documents);
    }
}
```

参数说明：
- `defaultChunkSize`：每个文本块的目标大小（token，默认 800）
- `minChunkSizeChars`：每个文本块的最小大小（字符，默认 350）
- `minChunkLengthToEmbed`：块的最小长度（默认 5）
- `maxNumChunks`：最大块数（默认 10000）
- `keepSeparator`：是否保留分隔符（默认 true）

**2）MetadataEnricher 元数据增强器**

- KeywordMetadataEnricher：使用 AI 提取关键词并添加到元数据
- SummaryMetadataEnricher：使用 AI 生成文档摘要并添加到元数据

```java
@Component
class MyDocumentEnricher {

    private final ChatModel chatModel;

    MyDocumentEnricher(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    List<Document> enrichDocumentsByKeyword(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(this.chatModel, 5);
        return enricher.apply(documents);
    }

    List<Document> enrichDocumentsBySummary(List<Document> documents) {
        SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(chatModel,
            List.of(SummaryType.PREVIOUS, SummaryType.CURRENT, SummaryType.NEXT));
        return enricher.apply(documents);
    }
}
```

**3）ContentFormatter 内容格式化工具**

用于统一文档内容格式，支持文档格式化、元数据过滤和自定义模板。

```java
DefaultContentFormatter formatter = DefaultContentFormatter.builder()
    .withMetadataTemplate("{key}: {value}")
    .withMetadataSeparator("\n")
    .withTextTemplate("{metadata_string}\n\n{content}")
    .withExcludedInferenceMetadataKeys("embedding", "vector_id")
    .withExcludedEmbedMetadataKeys("source_url", "timestamp")
    .build();

String formattedText = formatter.format(document, MetadataMode.INFERENCE);
```

#### 加载（Load）- DocumentWriter

```java
public interface DocumentWriter extends Consumer<List<Document>> {
    default void write(List<Document> documents) {
        accept(documents);
    }
}
```

内置实现：
- FileDocumentWriter：将文档写入到文件系统
- VectorStoreWriter：将文档写入到向量数据库

**ETL 完整流程示例：**

```java
PDFReader pdfReader = new PagePdfDocumentReader("knowledge_base.pdf");
List<Document> documents = pdfReader.read();

TokenTextSplitter splitter = new TokenTextSplitter(500, 50);
List<Document> splitDocuments = splitter.apply(documents);

SummaryMetadataEnricher enricher = new SummaryMetadataEnricher(chatModel,
    List.of(SummaryType.CURRENT));
List<Document> enrichedDocuments = enricher.apply(splitDocuments);

vectorStore.write(enrichedDocuments);

// 链式写法
vectorStore.write(enricher.apply(splitter.apply(pdfReader.read())));
```

---

### 向量转换和存储

#### VectorStore 接口

```java
public interface VectorStore extends DocumentWriter {

    default String getName() {
        return this.getClass().getSimpleName();
    }

    void add(List<Document> documents);
    void delete(List<String> idList);
    void delete(Filter.Expression filterExpression);
    default void delete(String filterExpression) { ... };

    List<Document> similaritySearch(String query);
    List<Document> similaritySearch(SearchRequest request);

    default <T> Optional<T> getNativeClient() {
        return Optional.empty();
    }
}
```

#### SearchRequest 搜索请求构建

```java
SearchRequest request = SearchRequest.builder()
    .query("什么是程序员鱼皮的编程导航学习网 codefather.cn？")
    .topK(5)
    .similarityThreshold(0.7)
    .filterExpression("category == 'web' AND date > '2025-05-03'")
    .build();

List<Document> results = vectorStore.similaritySearch(request);
```

配置选项：
- `query`：搜索的查询文本
- `topK`：返回的最大结果数，默认为 4
- `similarityThreshold`：相似度阈值，低于此值的结果会被过滤
- `filterExpression`：基于文档元数据的过滤表达式

#### 向量存储的工作原理

1. 嵌入转换：文档被添加时，使用嵌入模型将文本转换为向量
2. 相似度计算：查询文本同样被转换为向量，计算与存储中所有向量的相似度
3. 相似度度量：余弦相似度、欧氏距离、点积
4. 过滤与排序：根据相似度阈值过滤结果，按相似度排序返回

#### 基于 PGVector 实现向量存储

PGVector 是 PostgreSQL 的扩展，为 PostgreSQL 提供存储和检索高维向量数据的能力。

**步骤 1：引入依赖**

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
    <version>1.0.0-M7</version>
</dependency>
```

**步骤 2：编写配置**

```yaml
spring:
  datasource:
    url: jdbc:postgresql://公网地址/yu_ai_agent
    username: 用户名
    password: 密码
  ai:
    vectorstore:
      pgvector:
        index-type: HNSW
        dimensions: 1536
        distance-type: COSINE_DISTANCE
        max-document-batch-size: 10000
```

**步骤 3：灵活初始化 PgVectorStore（多 EmbeddingModel 场景）**

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-pgvector-store</artifactId>
    <version>1.0.0-M6</version>
</dependency>
```

```java
@Configuration
public class PgVectorVectorStoreConfig {

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();
        return vectorStore;
    }
}
```

启动类需排除自动加载：

```java
@SpringBootApplication(exclude = PgVectorStoreAutoConfiguration.class)
public class YuAiAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(YuAiAgentApplication.class, args);
    }
}
```

**整合文档加载与 PGVectorStore：**

```java
@Configuration
public class PgVectorVectorStoreConfig {

    @Resource
    private LoveAppDocumentLoader loveAppDocumentLoader;

    @Bean
    public VectorStore pgVectorVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel dashscopeEmbeddingModel) {
        VectorStore vectorStore = PgVectorStore.builder(jdbcTemplate, dashscopeEmbeddingModel)
                .dimensions(1536)
                .distanceType(COSINE_DISTANCE)
                .indexType(HNSW)
                .initializeSchema(true)
                .schemaName("public")
                .vectorTableName("vector_store")
                .maxDocumentBatchSize(10000)
                .build();

        List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
        vectorStore.add(documents);
        return vectorStore;
    }
}
```

#### 扩展知识 - 批处理策略（BatchingStrategy）

```java
public interface BatchingStrategy {
    List<List<Document>> batch(List<Document> documents);
}
```

```java
@Configuration
public class EmbeddingConfig {
    @Bean
    public BatchingStrategy customTokenCountBatchingStrategy() {
        return new TokenCountBatchingStrategy(
            EncodingType.CL100K_BASE,
            8000,
            0.1
        );
    }
}
```

---

### 文档过滤和检索

Spring AI 提供模块化 RAG 架构，将文档过滤检索阶段拆分为：检索前、检索时、检索后。

#### 预检索：优化用户查询

**查询重写 - RewriteQueryTransformer：**

```java
Query query = new Query("啥是程序员鱼皮啊啊啊啊？");

QueryTransformer queryTransformer = RewriteQueryTransformer.builder()
        .chatClientBuilder(chatClientBuilder)
        .build();

Query transformedQuery = queryTransformer.transform(query);
```

**查询翻译 - TranslationQueryTransformer：**

```java
Query query = new Query("hi, who is coder yupi? please answer me");

QueryTransformer queryTransformer = TranslationQueryTransformer.builder()
        .chatClientBuilder(chatClientBuilder)
        .targetLanguage("chinese")
        .build();

Query transformedQuery = queryTransformer.transform(query);
```

**查询压缩 - CompressionQueryTransformer：**

```java
Query query = Query.builder()
        .text("编程导航有啥内容？")
        .history(new UserMessage("谁是程序员鱼皮？"),
                new AssistantMessage("编程导航的创始人 codefather.cn"))
        .build();

QueryTransformer queryTransformer = CompressionQueryTransformer.builder()
        .chatClientBuilder(chatClientBuilder)
        .build();

Query transformedQuery = queryTransformer.transform(query);
```

**多查询扩展 - MultiQueryExpander：**

```java
MultiQueryExpander queryExpander = MultiQueryExpander.builder()
    .chatClientBuilder(chatClientBuilder)
    .numberOfQueries(3)
    .build();
List<Query> queries = queryExpander.expand(new Query("啥是程序员鱼皮？他会啥？"));
```

#### 检索：提高查询相关性

**DocumentRetriever 文档检索器：**

```java
DocumentRetriever retriever = VectorStoreDocumentRetriever.builder()
    .vectorStore(vectorStore)
    .similarityThreshold(0.7)
    .topK(5)
    .filterExpression(new FilterExpressionBuilder()
        .eq("type", "web")
        .build())
    .build();
List<Document> documents = retriever.retrieve(new Query("谁是程序员鱼皮"));
```

#### 检索后：优化文档处理

包括根据相关性对文档排序、删除不相关或冗余文档、压缩文档内容以减少噪音。

---

### 查询增强和关联

#### QuestionAnswerAdvisor 查询增强

```java
ChatResponse response = ChatClient.builder(chatModel)
        .build().prompt()
        .advisors(new QuestionAnswerAdvisor(vectorStore))
        .user(userText)
        .call()
        .chatResponse();
```

配置精细参数：

```java
var qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder().similarityThreshold(0.8d).topK(6).build())
        .build();
```

动态过滤表达式：

```java
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
        .searchRequest(SearchRequest.builder().build())
        .build())
    .build();

String content = this.chatClient.prompt()
    .user("看着我的眼睛，回答我！")
    .advisors(a -> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "type == 'web'"))
    .call()
    .content();
```

自定义提示词模板：

```java
QuestionAnswerAdvisor qaAdvisor = QuestionAnswerAdvisor.builder(vectorStore)
    .promptTemplate(customPromptTemplate)
    .build();
```

#### RetrievalAugmentationAdvisor 查询增强

```java
Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
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

结合查询转换器：

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
```

#### ContextualQueryAugmenter 空上下文处理

允许空上下文查询：

```java
Advisor retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
        .documentRetriever(VectorStoreDocumentRetriever.builder()
                .similarityThreshold(0.50)
                .vectorStore(vectorStore)
                .build())
        .queryAugmenter(ContextualQueryAugmenter.builder()
                .allowEmptyContext(true)
                .build())
        .build();
```

自定义提示模板：

```java
QueryAugmenter queryAugmenter = ContextualQueryAugmenter.builder()
        .promptTemplate(customPromptTemplate)
        .emptyContextPromptTemplate(emptyContextPromptTemplate)
        .build();
```

---

## 二、RAG 最佳实践和调优

### 文档收集和切割

#### 1. 优化原始文档

文档质量决定了 AI 回答能力的上限。

**内容结构化：**
- 原始文档保持排版清晰、结构合理
- 文档各级标题层次分明
- 减少层级嵌套

**内容规范化：**
- 语言统一：确保文档语言与用户提示词一致
- 表述统一：同一概念使用统一表达方式
- 减少噪音：避免水印、表格和图片等影响解析的元素

**格式标准化：**
- 优先使用 Markdown、DOC/DOCX 等文本格式
- 图片需链接化处理

#### 2. 文档切片

合适的文档切片尺寸需根据具体情况灵活调整：
- 切片过短导致语义缺失
- 切片过长引入无关信息
- 语义截断导致召回时缺失内容

最佳策略：结合智能分块算法和人工二次校验。

**通过 TokenTextSplitter 调整切分规则：**

```java
@Component
class MyTokenTextSplitter {
    public List<Document> splitDocuments(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter();
        return splitter.apply(documents);
    }

    public List<Document> splitCustomized(List<Document> documents) {
        TokenTextSplitter splitter = new TokenTextSplitter(200, 100, 10, 5000, true);
        return splitter.apply(documents);
    }
}
```

**使用切分器：**

```java
@Resource
private MyTokenTextSplitter myTokenTextSplitter;

@Bean
VectorStore loveAppVectorStore(EmbeddingModel dashscopeEmbeddingModel) {
    SimpleVectorStore simpleVectorStore = SimpleVectorStore.builder(dashscopeEmbeddingModel)
            .build();

    List<Document> documents = loveAppDocumentLoader.loadMarkdowns();
    List<Document> splitDocuments = myTokenTextSplitter.splitCustomized(documents);
    simpleVectorStore.add(splitDocuments);
    return simpleVectorStore;
}
```

#### 3. 元数据标注

**手动添加元信息：**

```java
documents.add(new Document(
    "案例编号：LR-2023-001\n" +
    "项目概述：...\n" +
    "设计要点：...",
    Map.of(
        "type", "interior",
        "year", "2025",
        "month", "05",
        "style", "modern"
    )));
```

**利用 DocumentReader 批量添加：**

```java
String status = fileName.substring(fileName.length() - 6, fileName.length() - 4);
MarkdownDocumentReaderConfig config = MarkdownDocumentReaderConfig.builder()
        .withAdditionalMetadata("filename", fileName)
        .withAdditionalMetadata("status", status)
        .build();
```

**自动添加元信息（KeywordMetadataEnricher）：**

```java
@Component
class MyKeywordEnricher {
    @Resource
    private ChatModel dashscopeChatModel;

    List<Document> enrichDocuments(List<Document> documents) {
        KeywordMetadataEnricher enricher = new KeywordMetadataEnricher(this.dashscopeChatModel, 5);
        return enricher.apply(documents);
    }
}
```

### 向量转换和存储

- 根据费用成本、数据规模、性能、开发成本选择向量存储方案
- 选择合适的嵌入模型

### 文档过滤和检索

**检索器配置（相似度阈值、返回文档数量、过滤规则）：**

```java
DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
        .vectorStore(loveAppVectorStore)
        .similarityThreshold(0.5)
        .topK(3)
        .build();
```

**基于元数据过滤的工厂类：**

```java
@Slf4j
public class LoveAppRagCustomAdvisorFactory {
    public static Advisor createLoveAppRagCustomAdvisor(VectorStore vectorStore, String status) {
        Filter.Expression expression = new FilterExpressionBuilder()
                .eq("status", status)
                .build();
        DocumentRetriever documentRetriever = VectorStoreDocumentRetriever.builder()
                .vectorStore(vectorStore)
                .filterExpression(expression)
                .similarityThreshold(0.5)
                .topK(3)
                .build();
        return RetrievalAugmentationAdvisor.builder()
                .documentRetriever(documentRetriever)
                .build();
    }
}
```

### 查询增强和关联

**错误处理机制 - ContextualQueryAugmenter：**

```java
RetrievalAugmentationAdvisor.builder()
    .queryAugmenter(
        ContextualQueryAugmenter.builder()
            .allowEmptyContext(false)
            .build()
    )
```

**自定义错误处理工厂：**

```java
public class LoveAppContextualQueryAugmenterFactory {
    public static ContextualQueryAugmenter createInstance() {
        PromptTemplate emptyContextPromptTemplate = new PromptTemplate("""
                你应该输出下面的内容：
                抱歉，我只能回答恋爱相关的问题，别的没办法帮到您哦，
                有问题可以联系编程导航客服 https://codefather.cn
                """);
        return ContextualQueryAugmenter.builder()
                .allowEmptyContext(false)
                .emptyContextPromptTemplate(emptyContextPromptTemplate)
                .build();
    }
}
```

---

## 三、扩展知识 - RAG 高级知识

### 混合检索策略

| 检索方法 | 原理 | 优势 | 劣势 |
|---------|------|------|------|
| 向量检索 | 基于嵌入向量相似度搜索 | 理解语义关联，适合概念性查询 | 对关键词不敏感，召回可能不准确 |
| 全文检索 | 基于倒排索引匹配关键词 | 精确匹配关键词，高召回率 | 不理解语义，同义词难以匹配 |
| 结构化检索 | 基于元数据或结构化字段查询 | 精确过滤，支持复杂条件组合 | 依赖良好的元数据，灵活性有限 |
| 知识图谱检索 | 利用实体间关系进行图遍历 | 发现隐含关系，回答复杂问题 | 构建成本高，需要专业知识 |

**三种混合检索模式：**

1. **并行混合检索**：同时使用多种检索方法获取结果，使用重排模型融合
2. **级联混合检索**：层层筛选，先用一种方法广泛召回，再用另一种精确过滤
3. **动态混合检索**：通过"路由器"根据查询类型自动选择最合适的检索方法

### 大模型幻觉

大模型幻觉指模型生成看似合理但实际上不准确或完全虚构的内容。

**三种表现形式：**
- 事实性幻觉：生成与事实不符的内容
- 逻辑性幻觉：推理过程存在逻辑错误
- 自洽性幻觉：生成内容自身存在矛盾

**减少幻觉的方法：**
- RAG：引入外部知识源
- 引用标注：让模型明确指出信息来源
- 提示工程优化：采用"思维链"提高推理透明度
- 事实验证模型：检查生成内容的准确性
- 人机协作审核流程

### RAG 应用评估

**评估的 3 个关键问题：**
1. 系统检索的信息是否相关？
2. 生成的回答是否准确？
3. 整体用户体验如何？

**评估指标：**

检索质量：
- 召回率、精确率、平均精度均值（MAP）、规范化折扣累积增益（NDCG）

生成回答质量：
- 事实准确性、答案完整性、上下文相关性、引用准确性

**评估流程：**
1. 生成评估数据集
2. 运行评估检索过程的程序
3. 评估回答质量（自动评估 + 人工评估）
4. 综合分析与优化

### 高级 RAG 架构

1. **自纠错 RAG（C-RAG）**：采用"检索-生成-验证-纠正"的闭环流程，适合医疗、法律等对准确性要求极高的领域
2. **自省式 RAG（Self-RAG）**：让模型判断何时需要检索、何时可以直接回答
3. **检索树 RAG（RAPTOR）**：将大问题分解成小问题分别检索，再整合答案，适合多方面复杂问题
4. **多智能体 RAG 系统**：组合拥有各类特长的智能体，通过通信协议交换信息，协同处理复杂任务

---

## 技术要点总结

1. **ETL 是 RAG 文档处理的核心**：DocumentReader 负责抽取，DocumentTransformer 负责转换（TextSplitter、MetadataEnricher、ContentFormatter），DocumentWriter 负责写入
2. **PGVector 是企业主流 RAG 方案**：直接在 PostgreSQL 上安装扩展即可实现向量相似度搜索，成本低
3. **BatchingStrategy 解决大批量文档嵌入问题**：TokenCountBatchingStrategy 确保每个批次不超过嵌入模型的最大 token 限制
4. **检索三阶段优化**：预检索（查询重写/翻译/压缩/多查询扩展）、检索（DocumentRetriever 过滤）、检索后（排序/去重/压缩）
5. **混合检索策略优于单一检索**：向量检索、全文检索、结构化检索、知识图谱检索各有优劣，组合使用效果更好
6. **大模型幻觉可通过 RAG + 引用标注 + 思维链等方式减轻**
7. **RAG 评估需建立科学的评估体系**：检索质量指标 + 生成回答质量指标 + 人工评估
8. **高级 RAG 架构（C-RAG、Self-RAG、RAPTOR）可满足更复杂场景的需求**