package com.mediamarktsaturn.ghbot.git;

import static org.assertj.core.api.Assertions.assertThat;

import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

public class TechnolinatorConfigTest {

    static final ObjectMapper configMapper = new ObjectMapper(new YAMLFactory());

    @Test
    void testEmptyConfig() throws JsonProcessingException {
        var config = configMapper.readValue("---", TechnolinatorConfig.class);
        assertThat(config).isNull();
    }

    @Test
    void testDisabledConfig() throws JsonProcessingException {
        @Language("yml")
        var value = """
            enable: false
            """;
        var config = configMapper.readValue(value, TechnolinatorConfig.class);
        assertThat(config).isNotNull().satisfies(c -> {
            assertThat(c.enable()).isFalse();
            assertThat(c.maven()).isNull();
            assertThat(c.gradle()).isNull();
            assertThat(c.project()).isNull();
            assertThat(c.analysis()).isNull();
            assertThat(c.env()).isNull();
        });
    }

    @Test
    void testFullConfig() throws JsonProcessingException {
        @Language("yml")
        var value = """
            enable: true
            project:
                name: awesomeProject
            analysis:
                location: projectLocation
                recursive: false
            gradle:
                multiProject: true
                args:
                    - -Pone
                    - -Dtwo
            maven:
                args:
                    - one
                    - two
            env:
                PROP: value1
                yea: haa
            jdk:
                version: 19
            """;
        var config = configMapper.readValue(value, TechnolinatorConfig.class);
        assertThat(config).isNotNull().satisfies(c -> {
            assertThat(c.enable()).isTrue();
            assertThat(c.gradle()).satisfies(g -> {
                assertThat(g.multiProject()).isTrue();
                assertThat(g.args()).containsExactly("-Pone", "-Dtwo");
            });
            assertThat(c.maven().args()).containsExactly("one", "two");
            assertThat(c.project().name()).isEqualTo("awesomeProject");
            assertThat(c.analysis()).satisfies(a -> {
                assertThat(a.location()).isEqualTo("projectLocation");
                assertThat(a.recursive()).isFalse();
            });
            assertThat(c.env())
                .containsEntry("PROP", "value1")
                .containsEntry("yea", "haa");
            assertThat(c.jdk()).satisfies(j -> {
                assertThat(j.version()).hasToString("19");
            });
        });
    }
}
