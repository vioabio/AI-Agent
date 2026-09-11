package com.vio.vioaiagent.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceDownloadPropertiesTest {

    @Test
    void normalizesMediaTypesAndResolvesWorkspacePlaceholder() {
        ResourceDownloadProperties properties = new ResourceDownloadProperties();
        properties.setDirectory("${user.dir}/tmp/download");
        properties.setAllowedMediaTypes(List.of(" IMAGE/ ", "application/pdf", "image/"));

        assertEquals(List.of("image/", "application/pdf"), properties.getAllowedMediaTypes());
        assertTrue(properties.directoryPath().isAbsolute());
        assertTrue(properties.validationErrors().isEmpty());
    }

    @Test
    void rejectsUnsafeQuotaAndMimeConfiguration() {
        ResourceDownloadProperties properties = new ResourceDownloadProperties();
        properties.setDirectory("");
        properties.setMaxBytes(0);
        properties.setMaxTotalBytes(1);
        properties.setAllowedMediaTypes(List.of("not-a-mime", ""));

        List<String> errors = properties.validationErrors();
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> error.contains("directory")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("maxBytes")));
        assertTrue(errors.stream().anyMatch(error -> error.contains("allowedMediaTypes")));
    }
}
