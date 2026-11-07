package com.vio.vioaiagent.observability;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.*;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelFailureDiagnosticsTest {
    @Test void typedHttpAndSpringAiStatusPrefixAreDistinctAndNeverRetainBodiesOrHeaders() throws Exception {
        var headers=new HttpHeaders();headers.set("Authorization","Bearer PRIVATE_HEADER");
        var typed=HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,"PRIVATE_STATUS",headers,
                "PRIVATE_BODY".getBytes(java.nio.charset.StandardCharsets.UTF_8),java.nio.charset.StandardCharsets.UTF_8);
        var result=ModelFailureDiagnostics.describe(new IllegalStateException("PRIVATE_WRAPPER",typed));
        assertEquals(429,result.get("httpStatus"));assertEquals("RATE_LIMITED",result.get("category"));
        assertEquals("TYPED_HTTP_EXCEPTION",result.get("statusSource"));
        for(int code:new int[]{400,401,402,403,404,408,413,422,429,500,502,503,504}) {
            var value=ModelFailureDiagnostics.describe(new TransientAiException(code+" - PRIVATE_BODY"));
            assertEquals(code,value.get("httpStatus"));assertEquals("SPRING_AI_STATUS_PREFIX",value.get("statusSource"));
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value).contains("PRIVATE"));
        }
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result).contains("PRIVATE"));
        assertThrows(UnsupportedOperationException.class,()->result.put("secret","x"));
    }
    @Test void arbitraryMessagesAndUnstructuredAiErrorsCannotInventHttpStatus() {
        for(Throwable error:List.of(new IllegalStateException("429 - PRIVATE_BODY"),
                new TransientAiException("provider said 503 - PRIVATE_BODY"),
                new NonTransientAiException("PRIVATE_BODY"))) {
            assertNull(ModelFailureDiagnostics.describe(error).get("httpStatus"));
        }
        assertEquals("PROVIDER_TRANSIENT_UNKNOWN_STATUS",ModelFailureDiagnostics.describe(new TransientAiException("PRIVATE")).get("category"));
        assertEquals("PROVIDER_REJECTED_UNKNOWN_STATUS",ModelFailureDiagnostics.describe(new NonTransientAiException("PRIVATE")).get("category"));
    }
    @Test void knownLocalCausesAreClassifiedWithoutEndpointsOrExceptionText() {
        Throwable[] errors={new java.net.UnknownHostException("PRIVATE_HOST"),new java.net.ConnectException("PRIVATE_IP"),
                new java.net.SocketTimeoutException("PRIVATE_URL"),new javax.net.ssl.SSLException("PRIVATE_CERT"),
                new java.io.IOException("PRIVATE_BODY"),new org.springframework.core.codec.DecodingException("PRIVATE_JSON"),
                new org.springframework.core.codec.EncodingException("PRIVATE_INPUT")};
        String[] categories={"DNS_FAILURE","CONNECTION_FAILURE","TIMEOUT","TLS_FAILURE","TRANSPORT_FAILURE","RESPONSE_DECODING_FAILURE","REQUEST_ENCODING_FAILURE"};
        for(int i=0;i<errors.length;i++) {
            var value=ModelFailureDiagnostics.describe(new IllegalStateException("PRIVATE_WRAPPER",errors[i]));
            assertEquals(categories[i],value.get("category"));assertNull(value.get("httpStatus"));assertFalse(value.toString().contains("PRIVATE"));
        }
    }
    @Test void localBudgetsAndCancellationAreNotMisreportedAsProviderOutages() {
        assertEquals("CANCELLED",ModelFailureDiagnostics.describe(new java.util.concurrent.CancellationException("PRIVATE")).get("category"));
        assertEquals("CONTEXT_BUDGET_BLOCKED",ModelFailureDiagnostics.describe(new ContextBudgetExceededException()).get("category"));
        assertEquals("TASK_BUDGET_BLOCKED",ModelFailureDiagnostics.describe(new TaskBudgetExceededException(TaskBudgetExceededException.Reason.CALL_LIMIT)).get("category"));
    }
    @Test void cyclesDepthAndInaccessibleCausesStayBoundedAndUnknown() {
        Throwable deep=new TransientAiException("503 - PRIVATE_BODY");
        for(int i=0;i<9;i++)deep=new IllegalStateException("PRIVATE",deep);
        var value=ModelFailureDiagnostics.describe(deep);assertEquals(true,value.get("causeChainTruncated"));assertNull(value.get("httpStatus"));
        var cycle=new RuntimeException(){@Override public synchronized Throwable getCause(){return this;}};
        assertEquals(true,ModelFailureDiagnostics.describe(cycle).get("causeChainTruncated"));
        var inaccessible=new RuntimeException(){@Override public synchronized Throwable getCause(){throw new IllegalStateException("PRIVATE");}};
        assertEquals("UNKNOWN_FAILURE",ModelFailureDiagnostics.describe(inaccessible).get("category"));
        assertEquals("UNKNOWN_FAILURE",ModelFailureDiagnostics.describe(null).get("category"));
    }
}
