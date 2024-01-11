package com.mediamarktsaturn.technolinator.sbom;

import com.mediamarktsaturn.technolinator.CustomTestProfiles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(CustomTestProfiles.EmptyAllowedEnvSubstitutions.class)
class CdxgenClientEmptyAllowedEnvSubstitutionsTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testEmptyAllowedEnvSubstitutionsIsParsedWithoutError() {
        assertThat(cut.allowedEnvSubstitutions).isEmpty();
    }
}

@QuarkusTest
@TestProfile(CustomTestProfiles.AllowedEnvSubstitutions.class)
class CdxgenClientAllowedEnvSubstitutionsTest {

    @Inject
    CdxgenClient cut;

    @Test
    void testEmptyAllowedEnvSubstitutionsIsParsedWithoutError() {
        assertThat(cut.allowedEnvSubstitutions).hasSize(2);
        assertThat(cut.allowedEnvSubstitutions).containsExactlyInAnyOrder("substitution.1", "substitution.2");
    }
}
