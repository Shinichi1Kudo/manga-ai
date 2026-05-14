package com.manga.ai.common.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class CommonControllerTest {

    @Test
    void controllerExposesSiteLogoEndpoint() throws Exception {
        RequestMapping rootMapping = CommonController.class.getAnnotation(RequestMapping.class);
        Method siteLogo = CommonController.class.getDeclaredMethod("getSiteLogo");

        assertThat(rootMapping).isNotNull();
        assertThat(rootMapping.value()).containsExactly("/v1/common");
        assertThat(siteLogo.getAnnotation(GetMapping.class).value()).containsExactly("/site-logo");
    }
}
