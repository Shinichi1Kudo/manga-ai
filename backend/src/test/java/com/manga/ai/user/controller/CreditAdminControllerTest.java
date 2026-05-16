package com.manga.ai.user.controller;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

import static org.assertj.core.api.Assertions.assertThat;

class CreditAdminControllerTest {

    @Test
    void controllerExposesCreditDashboardEndpoint() throws Exception {
        RequestMapping rootMapping = CreditAdminController.class.getAnnotation(RequestMapping.class);
        Method dashboard = CreditAdminController.class.getDeclaredMethod("dashboard", Integer.class, Integer.class, Integer.class, String.class);

        assertThat(rootMapping).isNotNull();
        assertThat(rootMapping.value()).containsExactly("/v1/admin/credits");
        assertThat(dashboard.getAnnotation(GetMapping.class).value()).containsExactly("/dashboard");

        Parameter hours = dashboard.getParameters()[0];
        Parameter recordPage = dashboard.getParameters()[1];
        Parameter recordPageSize = dashboard.getParameters()[2];
        Parameter nickname = dashboard.getParameters()[3];
        assertThat(hours.getAnnotation(RequestParam.class).required()).isFalse();
        assertThat(recordPage.getAnnotation(RequestParam.class).required()).isFalse();
        assertThat(recordPageSize.getAnnotation(RequestParam.class).required()).isFalse();
        assertThat(nickname.getAnnotation(RequestParam.class).required()).isFalse();
    }
}
