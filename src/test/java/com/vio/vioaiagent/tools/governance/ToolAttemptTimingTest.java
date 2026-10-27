package com.vio.vioaiagent.tools.governance;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ToolAttemptTimingTest {
    @Test void actualContentionSeparatesQueueAuthorizationAndCallbackExecution() throws Exception {
        try(var executor=new BoundedToolExecutor(new ToolExecutionProperties(1,1,100))) {
            var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
            var callers=Executors.newFixedThreadPool(2);
            try {
                var first=callers.submit(()->executor.call(()->{},()->{entered.countDown();release.await();return "first";},5000));
                assertTrue(entered.await(2,TimeUnit.SECONDS));var timing=new ToolAttemptTiming();
                var second=callers.submit(()->executor.call(()->pause(20),()->{pause(20);return "second";},5000,timing));
                await().atMost(java.time.Duration.ofSeconds(2)).until(()->executor.snapshot().queuedCount()==1);
                pause(25);release.countDown();assertEquals("first",first.get(2,TimeUnit.SECONDS));assertEquals("second",second.get(2,TimeUnit.SECONDS));
                var sample=timing.freeze();assertTrue((Long)sample.get("queueWaitMs")>=20);assertTrue((Long)sample.get("authorizationMs")>=15);
                assertTrue((Long)sample.get("executionMs")>=15);assertEquals(true,sample.get("callbackCompleted"));
                assertNotNull(ToolAttemptTiming.safeCopy(ToolAttemptTiming.summarize(List.of(timing))));
            } finally {release.countDown();callers.shutdownNow();}
        }
    }
    @Test void queuedTimeoutHasNoCallbackDurationAndCannotStartTheCallbackLater() throws Exception {
        try(var executor=new BoundedToolExecutor(new ToolExecutionProperties(1,1,100));var callers=Executors.newSingleThreadExecutor()) {
            var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
            try {
                var first=callers.submit(()->executor.call(()->{},()->{entered.countDown();release.await();return "first";},5000));
                assertTrue(entered.await(2,TimeUnit.SECONDS));var timing=new ToolAttemptTiming();
                assertThrows(BoundedToolExecutor.Stopped.class,()->executor.call(()->{},()->{calls.incrementAndGet();return "wrong";},40,timing));
                var frozen=timing.freeze();assertEquals(false,frozen.get("callbackStarted"));assertNull(frozen.get("executionMs"));
                assertTrue((Long)frozen.get("queueWaitMs")>=20);release.countDown();first.get(2,TimeUnit.SECONDS);
                assertEquals(0,calls.get());assertSame(frozen,timing.freeze());
            } finally {release.countDown();}
        }
    }
    @Test void runningTimeoutFreezesPartialDurationEvenWhenCallbackIgnoresInterrupt() throws Exception {
        var release=new CountDownLatch(1);var late=new CountDownLatch(1);
        try(var executor=new BoundedToolExecutor(new ToolExecutionProperties(1,0,5))) {
            var timing=new ToolAttemptTiming();
            var error=assertThrows(BoundedToolExecutor.Stopped.class,()->executor.call(()->{},()->{
                while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}
                late.countDown();return "late";
            },80,timing));
            assertTrue(error.callbackStarted());var frozen=timing.freeze();assertEquals(false,frozen.get("callbackCompleted"));
            assertNotNull(frozen.get("executionMs"));release.countDown();assertTrue(late.await(2,TimeUnit.SECONDS));assertEquals(frozen,timing.freeze());
        } finally {release.countDown();}
    }
    @Test void failedWorkerAuthorizationIsNotCallbackExecutionAndMalformedMetadataIsDiscarded() {
        try(var executor=new BoundedToolExecutor(new ToolExecutionProperties(1,0,100))) {
            var timing=new ToolAttemptTiming();var calls=new AtomicInteger();
            assertThrows(SecurityException.class,()->executor.call(()->{throw new SecurityException("fixture");},()->{calls.incrementAndGet();return "wrong";},1000,timing));
            assertEquals(0,calls.get());assertNull(timing.freeze().get("executionMs"));
            var raw=new LinkedHashMap<>(ToolAttemptTiming.summarize(List.of(timing)));raw.put("queueWaitMs",0.5);
            assertNull(ToolAttemptTiming.safeCopy(raw));assertNotNull(ToolAttemptTiming.safeCopy(ToolAttemptTiming.bypass("CACHED_RESULT")));
        }
    }
    private static void pause(long millis){try{Thread.sleep(millis);}catch(InterruptedException error){Thread.currentThread().interrupt();throw new RuntimeException(error);}}
}
