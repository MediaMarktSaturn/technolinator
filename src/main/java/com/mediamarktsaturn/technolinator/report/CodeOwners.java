package com.mediamarktsaturn.technolinator.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Owners of a whole repository, as declared by the catch-all rule of a CODEOWNERS file.
 *
 * @param users logins of users, which can be assigned to issues
 * @param teams {@code org/team} slugs with the leading @, which can only be mentioned
 */
public record CodeOwners(List<String> users, List<String> teams) {

    static final int MAX_ASSIGNEES = 3;
    static final int MAX_TEAMS = 10;

    // logins of enterprise managed users carry the enterprise short code after an underscore
    private static final Pattern USER = Pattern.compile("@[A-Za-z0-9](?:[A-Za-z0-9_-]{0,38})");
    private static final Pattern TEAM = Pattern.compile("@[A-Za-z0-9](?:[A-Za-z0-9_-]{0,38})/[A-Za-z0-9._-]{1,100}");

    public static CodeOwners none() {
        return new CodeOwners(List.of(), List.of());
    }

    /**
     * The last matching rule wins in CODEOWNERS files, so the last catch-all rule is taken.
     * The file is written by the owners of the repository, but ends up in the issue, so only valid logins and team names are taken.
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
            // e-mail addresses can't be assigned
            if (USER.matcher(owner).matches()) {
                if (!users.contains(owner.substring(1))) {
                    users.add(owner.substring(1));
                }
            } else if (TEAM.matcher(owner).matches() && !teams.contains(owner)) {
                teams.add(owner);
            }
        }
        return new CodeOwners(users.stream().limit(MAX_ASSIGNEES).toList(), teams.stream().limit(MAX_TEAMS).toList());
    }
}
