package com.vio.vioaiagent.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyMcpStdioTest {
    @TempDir Path directory;
    @Test void independentNodeClientDiscoversAndCallsRealDomainToolsThroughStdio() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = directory.resolve("probe.log");
        var process = new ProcessBuilder("node", "tools/campus/mcp-stdio-probe.mjs", java, classpath, McpStdioFixtureMain.class.getName(), directory.resolve("cache").toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "MCP probe exceeded bound");
            assertEquals(0, process.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("\"status\":\"PASS\""));
        } finally { process.destroyForcibly(); }
    }
    @Test void transportRejectsOversizedFramesBeforeJsonAndCountsUtf8Bytes() throws Exception {
        var end = new CountDownLatch(1);
        var input = new McpWireControl(new java.io.ByteArrayInputStream("水".repeat(9000).getBytes(java.nio.charset.StandardCharsets.UTF_8)), new java.io.ByteArrayOutputStream(), end).input;
        assertThrows(java.io.IOException.class, () -> input.readAllBytes());
        assertEquals(0, end.getCount());
    }
    @Test void sustainedConnectionRemainsUsableAfterResponseWindowWrapAndRepeatedSlowConsumers() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        Path log=directory.resolve("endurance.log");
        var process=new ProcessBuilder("node","tools/campus/mcp-stdio-probe.mjs",java,classpath,McpStdioFixtureMain.class.getName(),directory.resolve("endurance").toString(),"endurance")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(90,TimeUnit.SECONDS),"MCP endurance exceeded bound");
            assertEquals(0,process.exitValue(),Files.readString(log));
            assertTrue(Files.readString(log).contains("\"pingCycles\":5000"));
            assertTrue(Files.readString(log).contains("\"status\":\"PASS\""));
        } finally {process.destroyForcibly();}
    }
    @Test void realProcessStopsWorkersOnEofDuplicateIdAndOversizedFrame() throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        for(String mode:List.of("eof","duplicate","oversize","slow-consumer")) {
            Path log=directory.resolve(mode+".log");
            var process=new ProcessBuilder("node","tools/campus/mcp-stdio-probe.mjs",java,classpath,McpStdioFixtureMain.class.getName(),directory.resolve(mode).toString(),mode)
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),mode);assertEquals(0,process.exitValue(),Files.readString(log));}
            finally{process.destroyForcibly();}
        }
    }
}
