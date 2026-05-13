package com.manga.ai.subject.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class SubjectReplacementControllerTest {

    @Test
    void deleteTaskSupportsCanonicalAndLegacyDeletePaths() throws Exception {
        Method method = SubjectReplacementController.class.getDeclaredMethod("deleteTask", Long.class);
        DeleteMapping mapping = method.getAnnotation(DeleteMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactlyInAnyOrder("/{taskId}", "/{taskId}/delete");
    }
}
