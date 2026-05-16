package com.manga.ai.shot.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ShotControllerTest {

    @Test
    void generationPrepareAndStartSupportCanonicalAndTrailingSlashPaths() throws Exception {
        RequestMapping rootMapping = ShotController.class.getAnnotation(RequestMapping.class);
        Method prepare = ShotController.class.getDeclaredMethod("prepareVideoGenerationWithReferences", Long.class, Map.class);
        Method start = ShotController.class.getDeclaredMethod("startPreparedVideoGenerationWithReferences", Long.class, Map.class);

        assertThat(rootMapping).isNotNull();
        assertThat(rootMapping.value()).containsExactly("/v1/shots");
        assertThat(prepare.getAnnotation(PostMapping.class).value())
                .containsExactlyInAnyOrder("/{shotId}/generation/prepare", "/{shotId}/generation/prepare/");
        assertThat(start.getAnnotation(PostMapping.class).value())
                .containsExactlyInAnyOrder("/{shotId}/generation/start", "/{shotId}/generation/start/");
    }
}
