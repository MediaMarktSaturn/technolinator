package com.mediamarktsaturn.technolinator.report;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CodeOwnersTest {

    @Test
    void lastCatchAllRuleWins() {
        var owners = CodeOwners.parse(Optional.of("""
            # comment
            * @first
            /docs/ @docs-team
            *   @alice @bob   # trailing comment
            """));

        assertThat(owners.users()).containsExactly("alice", "bob");
        assertThat(owners.teams()).isEmpty();
    }

    @Test
    void teamsAreOnlyMentioned() {
        var owners = CodeOwners.parse(Optional.of("* @alice @org/platform dev@example.com"));

        assertThat(owners.users()).containsExactly("alice");
        assertThat(owners.teams()).containsExactly("@org/platform");
    }

    @Test
    void assigneesAreLimited() {
        var owners = CodeOwners.parse(Optional.of("* @a1 @a2 @a3 @a4 @a5"));

        assertThat(owners.users()).containsExactly("a1", "a2", "a3");
    }

    @Test
    void ownersWhichCouldInjectMarkdownAreIgnored() {
        var owners = CodeOwners.parse(Optional.of("* @alice @org/team](http://evil) @bad<b> @org/ok @alice"));

        assertThat(owners.users()).containsExactly("alice");
        assertThat(owners.teams()).containsExactly("@org/ok");
    }

    @Test
    void enterpriseManagedUsersAreTaken() {
        var owners = CodeOwners.parse(Optional.of("* @alice_acme @acme_corp/platform"));

        assertThat(owners.users()).containsExactly("alice_acme");
        assertThat(owners.teams()).containsExactly("@acme_corp/platform");
    }

    @Test
    void noCatchAllOrNoFile() {
        assertThat(CodeOwners.parse(Optional.of("/src/ @alice"))).isEqualTo(CodeOwners.none());
        assertThat(CodeOwners.parse(Optional.empty())).isEqualTo(CodeOwners.none());
    }
}
