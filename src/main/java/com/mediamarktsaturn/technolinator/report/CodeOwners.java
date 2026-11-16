package com.mediamarktsaturn.technolinator.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Owners of a whole repository, as declared by the catch-all rule of a CODEOWNERS file.
 *
 * @param users logins of users, which can be assigned to issues
 * @param teams {@code org/team} slugs with the leading @, which can only be mentioned
 */
public record CodeOwners(List<String> users, List<String> teams) {

    static final int MAX_ASSIGNEES = 3;

    public static CodeOwners none() {
        return new CodeOwners(List.of(), List.of());
    }

    /**
     * The last matching rule wins in CODEOWNERS files, so the last catch-all rule is taken.
     */
    public static CodeOwners parse(Optional<String> content) {
        if (content.isEmpty()) {
            return none();
        }
        List<String> owners = List.of();
        for (var rawLine : content.get().split("\\R")) {
            var line = rawLine.strip();
            var comment = line.indexOf('#');
            if (comment >= 0) {
                line = line.substring(0, comment).strip();
            }
            if (line.isEmpty()) {
                continue;
            }
            var parts = line.split("\\s+");
            if (parts.length > 1 && ("*".equals(parts[0]) || "/*".equals(parts[0]) || "/".equals(parts[0]))) {
                owners = List.of(parts).subList(1, parts.length);
            }
        }

        var users = new ArrayList<String>();
        var teams = new ArrayList<String>();
        for (var owner : owners) {
            if (!owner.startsWith("@") || owner.length() < 2) {
                // e-mail addresses can't be assigned
                continue;
            }
            if (owner.contains("/")) {
                teams.add(owner);
            } else {
                users.add(owner.substring(1));
            }
        }
        return new CodeOwners(users.stream().limit(MAX_ASSIGNEES).toList(), List.copyOf(teams));
    }
}
