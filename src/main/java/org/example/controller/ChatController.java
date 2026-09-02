package org.example.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.agent.tool.ToolInvocationRecorder;
import org.example.service.AiOpsService;
import org.example.service.ChatService;
import org.example.memory.MemoryContext;
import org.example.memory.MemoryManager;
import org.example.memory.MemoryStatus;
import org.example.memory.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;
    
    @Autowired
    private ChatService chatService;

    @Autowired
    private MemoryManager memoryManager;

    @Autowired
    private TokenEstimator tokenEstimator;

    @Autowired
    private ToolInvocationRecorder toolInvocationRecorder;

    @Autowired(required = false)
    private ToolCallbackProvider tools;

    @Value("${dashscope.chat.model:qwen-plus}")
    private String chatModelName;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {
            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            // 参数校验
            if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("问题内容不能为空")));
            }

            String sessionId = normalizeSessionId(request.getId());
            memoryManager.addUserMessage(sessionId, request.getQuestion());
            MemoryContext memoryContext = memoryManager.buildContextForQuery(sessionId, request.getQuestion());
            logger.info("会话记忆已加载 - SessionId: {}, 最近消息数: {}, 相关事实数: {}",
                    sessionId, memoryContext.getRecentMessages().size(), memoryContext.getRelevantFacts().size());

            // 创建 DashScope API 和 ChatModel
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

            // 记录可用工具
            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");
            
            // 构建系统提示词（包含历史消息）
            String systemPrompt = chatService.buildSystemPrompt(memoryContext);
            int promptTokens = tokenEstimator.estimate(systemPrompt + "\n" + request.getQuestion());
            memoryManager.recordPromptTokens(sessionId, promptTokens, memoryContext);
            logger.info("Prompt estimated tokens - SessionId: {}, Tokens: {}", sessionId, promptTokens);
            
            // 创建 ReactAgent
            ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
            
            // 执行对话
            String fullAnswer;
            toolInvocationRecorder.setCurrentSessionId(sessionId);
            try {
                fullAnswer = chatService.executeChat(agent, request.getQuestion());
            } finally {
                toolInvocationRecorder.clearCurrentSessionId();
            }
            
            // 更新会话历史
            memoryManager.addAssistantMessage(sessionId, fullAnswer);
            memoryManager.scheduleCompressionIfNeeded(sessionId);
            logger.info("已更新会话记忆 - SessionId: {}", sessionId);
            
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));

        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.ok(ApiResponse.error("会话ID不能为空"));
            }

            memoryManager.clearConversation(request.getId());
            return ResponseEntity.ok(ApiResponse.success("短期会话记忆已清空，长期事实已保留"));

        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                String sessionId = normalizeSessionId(request.getId());
                memoryManager.addUserMessage(sessionId, request.getQuestion());
                MemoryContext memoryContext = memoryManager.buildContextForQuery(sessionId, request.getQuestion());
                logger.info("ReactAgent 会话记忆已加载 - SessionId: {}, 最近消息数: {}, 相关事实数: {}",
                        sessionId, memoryContext.getRecentMessages().size(), memoryContext.getRelevantFacts().size());

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(memoryContext);
                int promptTokens = tokenEstimator.estimate(systemPrompt + "\n" + request.getQuestion());
                memoryManager.recordPromptTokens(sessionId, promptTokens, memoryContext);
                logger.info("Prompt estimated tokens - SessionId: {}, Tokens: {}", sessionId, promptTokens);
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(error.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.completeWithError(error);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}", 
                                request.getId(), fullAnswer.length());
                            
                            // 更新会话历史
                            memoryManager.addAssistantMessage(sessionId, fullAnswer);
                            memoryManager.scheduleCompressionIfNeeded(sessionId);
                            logger.info("已更新会话记忆 - SessionId: {}", sessionId);
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(dashScopeApi)
                                .defaultOptions(DashScopeChatOptions.builder()
                                .withModel(chatModelName)
                                .withTemperature(0.3)
                                .withMaxToken(8000)
                                .withTopP(0.9)
                                .build())
                        .build();

                ToolCallback[] toolCallbacks = tools == null ? new ToolCallback[0] : tools.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并拆解任务...\n")));
                
                // 调用 AiOpsService 执行分析流程
                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                // 提取最终报告
                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                // 输出最终报告
                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());
                    
                    // 发送分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n\n" + "=".repeat(60) + "\n"), MediaType.APPLICATION_JSON));
                    
                    // 发送完整的告警分析报告
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));
                    
                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);
                        
                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }
                    
                    // 发送结束分隔线
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));
                    
                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");

            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程失败: " + e.getMessage()), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * 获取会话信息
     */
    @PostMapping("/ai_ops_single")
    public ResponseEntity<ApiResponse<ChatResponse>> aiOpsSingle(@RequestBody(required = false) ChatRequest request) {
        String sessionId = normalizeSessionId(request == null ? null : request.getId());
        try {
            DashScopeChatModel chatModel = createAiOpsChatModel();
            ToolCallback[] toolCallbacks = tools == null ? new ToolCallback[0] : tools.getToolCallbacks();

            toolInvocationRecorder.setCurrentSessionId(sessionId);
            String report;
            try {
                String taskPrompt = request == null ? null : request.getQuestion();
                report = aiOpsService.executeSingleAgentAnalysis(chatModel, toolCallbacks, taskPrompt);
            } finally {
                toolInvocationRecorder.clearCurrentSessionId();
            }

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(report)));
        } catch (Exception e) {
            logger.error("Single-agent AIOps baseline failed", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    @PostMapping("/ai_ops_multi")
    public ResponseEntity<ApiResponse<ChatResponse>> aiOpsMulti(@RequestBody(required = false) ChatRequest request) {
        String sessionId = normalizeSessionId(request == null ? null : request.getId());
        try {
            DashScopeChatModel chatModel = createAiOpsChatModel();
            ToolCallback[] toolCallbacks = tools == null ? new ToolCallback[0] : tools.getToolCallbacks();

            Optional<OverAllState> overAllStateOptional;
            toolInvocationRecorder.setCurrentSessionId(sessionId);
            try {
                String taskPrompt = request == null ? null : request.getQuestion();
                overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks, taskPrompt);
            } finally {
                toolInvocationRecorder.clearCurrentSessionId();
            }

            if (overAllStateOptional.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success(ChatResponse.error("Multi-agent orchestration returned empty state")));
            }

            Optional<String> finalReportOptional = aiOpsService.extractFinalReport(overAllStateOptional.get(), chatModel);
            return finalReportOptional
                    .map(report -> ResponseEntity.ok(ApiResponse.success(ChatResponse.success(report))))
                    .orElseGet(() -> ResponseEntity.ok(ApiResponse.success(ChatResponse.error("Final report not found"))));
        } catch (Exception e) {
            logger.error("Multi-agent AIOps JSON endpoint failed", e);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.error(e.getMessage())));
        }
    }

    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            MemoryStatus status = memoryManager.getStatus(sessionId);
            SessionInfoResponse response = new SessionInfoResponse();
            response.setSessionId(sessionId);
            response.setMessagePairCount(status.getRecentMessageCount() / 2);
            response.setCreateTime(status.getLastUpdatedAt());
            response.setRecentMessageCount(status.getRecentMessageCount());
            response.setSummaryCount(status.getSummaryCount());
            response.setLongTermFactCount(status.getLongTermFactCount());
            response.setOpenTaskCount(status.getOpenTaskCount());
            response.setEstimatedTokens(status.getEstimatedTokens());
            response.setCompressionStatus(status.getCompressionStatus());
            response.setLastCompressionRatio(status.getLastCompressionRatio());
            response.setLastCompressionDurationMs(status.getLastCompressionDurationMs());
            response.setLastPromptTokens(status.getLastPromptTokens());
            response.setLastPromptTokensBeforeCompression(status.getLastPromptTokensBeforeCompression());
            response.setLastPromptTokensAfterCompression(status.getLastPromptTokensAfterCompression());
            response.setLastPromptTokenReductionRatio(status.getLastPromptTokenReductionRatio());
            return ResponseEntity.ok(ApiResponse.success(response));

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 辅助方法 ====================

    private String   normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return sessionId;
    }

    private DashScopeChatModel createAiOpsChatModel() {
        DashScopeApi dashScopeApi = chatService.createDashScopeApi();
        return DashScopeChatModel.builder()
                .dashScopeApi(dashScopeApi)
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel(chatModelName)
                        .withTemperature(0.3)
                        .withMaxToken(8000)
                        .withTopP(0.9)
                        .build())
                .build();
    }

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
        private int recentMessageCount;
        private int summaryCount;
        private int longTermFactCount;
        private int openTaskCount;
        private int estimatedTokens;
        private String compressionStatus;
        private double lastCompressionRatio;
        private long lastCompressionDurationMs;
        private int lastPromptTokens;
        private int lastPromptTokensBeforeCompression;
        private int lastPromptTokensAfterCompression;
        private double lastPromptTokenReductionRatio;
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    /**
     * 统一 SSE 流式消息格式
     * 适用于所有 SSE 流式返回模式的对话接口
     */
    @Setter
    @Getter
    public static class SseMessage {
        private String type;  // content: 内容块, error: 错误, done: 完成
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }


    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(500);
            response.setMessage(message);
            return response;
        }

    }
}
