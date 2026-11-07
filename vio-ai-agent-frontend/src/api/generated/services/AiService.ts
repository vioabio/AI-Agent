/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { CancelablePromise } from '../core/CancelablePromise';
import { OpenAPI } from '../core/OpenAPI';
import { request as __request } from '../core/request';
export class AiService {
    /**
     * 手动停止正在运行的 Agent
     * @returns string OK
     * @throws ApiError
     */
    public static stopManus({
        sessionId,
    }: {
        /**
         * 会话 ID（来自 /manus/chat 返回的第一条消息）
         */
        sessionId: string,
    }): CancelablePromise<string> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/manus/stop',
            query: {
                'sessionId': sessionId,
            },
        });
    }
    /**
     * 同步对话（工具调用）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppToolsSync({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<string> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/tools/sync',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * SSE 流式对话（工具调用）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppToolsSse({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<Array<string>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/tools/sse',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * 同步对话（基础）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppSync({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<string> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/sync',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * SSE 流式对话（基础）
     * 注意：此端点返回 text/event-stream，前端需使用 EventSource 而非 axios 调用
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppSse({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<Array<string>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/sse',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * 同步对话（RAG 知识库增强）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppRagSync({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<string> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/rag/sync',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * SSE 流式对话（RAG 知识库增强）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppRagSse({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<Array<string>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/rag/sse',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * 同步对话（MCP 服务）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppMcpSync({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<string> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/mcp/sync',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
    /**
     * SSE 流式对话（MCP 服务）
     * @returns string OK
     * @throws ApiError
     */
    public static doChatWithGameAppMcpSse({
        message,
        chatId,
    }: {
        /**
         * 用户消息
         */
        message: string,
        /**
         * 会话 ID
         */
        chatId: string,
    }): CancelablePromise<Array<string>> {
        return __request(OpenAPI, {
            method: 'GET',
            url: '/ai/game_app/chat/mcp/sse',
            query: {
                'message': message,
                'chatId': chatId,
            },
        });
    }
}
