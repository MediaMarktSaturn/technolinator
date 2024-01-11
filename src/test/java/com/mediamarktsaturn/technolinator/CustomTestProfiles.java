package com.mediamarktsaturn.technolinator;

import io.quarkus.test.junit.QuarkusTestProfile;

import java.util.Collections;
import java.util.Map;

public class CustomTestProfiles {

    public static class AllowedEnvSubstitutions implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Collections.singletonMap("app.allowed_env_substitutions", "substitution.1,substitution.2");
        }
    }

    public static class EmptyAllowedEnvSubstitutions implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Collections.singletonMap("app.allowed_env_substitutions", "   ");
        }
    }

    public static class CommitStatusWriteDisabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Collections.singletonMap("app.commit_status_write.enabled", "false");
        }
    }
}
