package com.vio.vioaiagent.observability;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import java.util.*;

/** Bounded, message-free classification at the ChatModel boundary. Never retains the Throwable. */
public final class ModelFailureDiagnostics {
    private ModelFailureDiagnostics() { }

    public static Map<String,Object> describe(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        boolean truncated = false;
        while (current != null) {
            if (chain.size() == 8 || !seen.add(current)) { truncated = true; break; }
            chain.add(current);
            try { current = current.getCause(); }
            catch (RuntimeException inaccessible) { truncated = true; break; }
        }
        for (Throwable error : chain) {
            if (error instanceof java.util.concurrent.CancellationException)
                return result("CANCELLED",null,"NONE",truncated);
            if (error instanceof ContextBudgetExceededException)
                return result("CONTEXT_BUDGET_BLOCKED",null,"NONE",truncated);
            if (error instanceof TaskBudgetExceededException)
                return result("TASK_BUDGET_BLOCKED",null,"NONE",truncated);
        }
        // Typed status wins over a string prefix, even when nested by Reactor or a client wrapper.
        for (Throwable error : chain) {
            Integer status = null;
            try {
                if (error instanceof RestClientResponseException response) status = response.getStatusCode().value();
                else if (error instanceof WebClientResponseException response) status = response.getStatusCode().value();
            } catch (RuntimeException inaccessible) { /* observation must not replace the original failure */ }
            if (status != null && status >= 400 && status <= 599)
                return result(httpCategory(status),status,"TYPED_HTTP_EXCEPTION",truncated);
        }
        for (Throwable error : chain) {
            // Spring AI RetryUtils wraps a status as "NNN - body" without retaining a typed cause.
            // Read only this fixed prefix and never serialize or log the rest of the message.
            if (error instanceof TransientAiException || error instanceof NonTransientAiException) {
                try {
                    String value = error.getMessage();
                    if (value != null && value.length() >= 6 && value.charAt(3) == ' '
                            && value.charAt(4) == '-' && value.charAt(5) == ' '
                            && (value.charAt(0) == '4' || value.charAt(0) == '5')
                            && value.charAt(1) >= '0' && value.charAt(1) <= '9'
                            && value.charAt(2) >= '0' && value.charAt(2) <= '9') {
                        int status = (value.charAt(0)-'0')*100+(value.charAt(1)-'0')*10+value.charAt(2)-'0';
                        return result(httpCategory(status),status,"SPRING_AI_STATUS_PREFIX",truncated);
                    }
                } catch (RuntimeException inaccessible) { /* do not retain the offending message */ }
            }
        }
        for (Throwable error : chain) {
            String category = null;
            if (error instanceof java.net.http.HttpTimeoutException || error instanceof java.net.SocketTimeoutException
                    || error instanceof java.util.concurrent.TimeoutException) category="TIMEOUT";
            else if (error instanceof java.net.UnknownHostException || error instanceof java.nio.channels.UnresolvedAddressException) category="DNS_FAILURE";
            else if (error instanceof javax.net.ssl.SSLException) category="TLS_FAILURE";
            else if (error instanceof java.net.ConnectException || error instanceof java.net.NoRouteToHostException) category="CONNECTION_FAILURE";
            else if (error instanceof org.springframework.core.codec.DecodingException
                    || error instanceof org.springframework.http.converter.HttpMessageNotReadableException) category="RESPONSE_DECODING_FAILURE";
            else if (error instanceof org.springframework.core.codec.EncodingException
                    || error instanceof org.springframework.http.converter.HttpMessageNotWritableException) category="REQUEST_ENCODING_FAILURE";
            else if (error instanceof com.fasterxml.jackson.core.JsonProcessingException) category="JSON_PROCESSING_FAILURE";
            if (category != null) return result(category,null,"NONE",truncated);
        }
        for (Throwable error : chain) {
            if (error instanceof java.io.IOException || error instanceof org.springframework.web.client.ResourceAccessException
                    || error instanceof org.springframework.web.reactive.function.client.WebClientRequestException)
                return result("TRANSPORT_FAILURE",null,"NONE",truncated);
        }
        for (Throwable error : chain) {
            if (error instanceof TransientAiException) return result("PROVIDER_TRANSIENT_UNKNOWN_STATUS",null,"NONE",truncated);
            if (error instanceof NonTransientAiException) return result("PROVIDER_REJECTED_UNKNOWN_STATUS",null,"NONE",truncated);
        }
        return result("UNKNOWN_FAILURE",null,"NONE",truncated);
    }

    public static Map<String,Object> cancelled() { return result("CANCELLED",null,"NONE",false); }
    private static String httpCategory(int status) {
        return switch (status) {
            case 400,422 -> "REQUEST_REJECTED";
            case 401 -> "AUTHENTICATION_FAILED";
            case 402 -> "PAYMENT_REQUIRED";
            case 403 -> "ACCESS_DENIED";
            case 404 -> "ENDPOINT_NOT_FOUND";
            case 408,504 -> "HTTP_TIMEOUT";
            case 413 -> "REQUEST_TOO_LARGE";
            case 429 -> "RATE_LIMITED";
            default -> status >= 500 ? "UPSTREAM_SERVER_ERROR" : "HTTP_CLIENT_ERROR";
        };
    }
    private static Map<String,Object> result(String category,Integer status,String source,boolean truncated) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("schemaVersion","model-failure-diagnostic-v1");result.put("scope","CHAT_MODEL_BOUNDARY");
        result.put("category",category);result.put("httpStatus",status);result.put("statusSource",source);
        result.put("causeChainTruncated",truncated);
        return Collections.unmodifiableMap(result);
    }
}
