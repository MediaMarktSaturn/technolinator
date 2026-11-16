package com.mediamarktsaturn.technolinator.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, stateful, in-process stand-in for the parts of the GitHub REST API of the repository {@code acme/shop}
 * which the failure reporting uses: issues, labels, pull-requests, their comments and the file contents.
 * <p>
 * Meant to be used with the real GitHub client ({@code new GitHubBuilder().withEndpoint(server.endpoint())}),
 * so the code under test is exercised exactly like it is against GitHub, including serialization and routes.
 * Everything written through the API is authored by {@link #BOT}, anything else is seeded by the test.
 */
final class FakeGitHubServer implements AutoCloseable {

    static final String OWNER = "acme";
    static final String NAME = "shop";
    static final String FULL_NAME = OWNER + "/" + NAME;
    static final String BOT = "technolinator[bot]";

    private static final String REPO_PATH = "/repos/" + FULL_NAME;
    private static final String HTML_URL = "https://github.com/" + FULL_NAME;
    private static final Instant EPOCH = Instant.parse("2026-01-01T00:00:00Z");

    static final class Issue {
        int number;
        String author;
        String title;
        String body;
        boolean open = true;
        String stateReason;
        boolean pullRequest;
        final Set<String> labels = new LinkedHashSet<>();
        final List<String> assignees = new ArrayList<>();
        Instant createdAt;

        boolean closed() {
            return !open;
        }
    }

    static final class Comment {
        long id;
        int issue;
        String author;
        String body;
    }

    static final class Pull {
        int number;
        String headRef;
        String headSha;
        String headRepoFullName;
        String baseRef;
        boolean open = true;
    }

    record Request(String method, String path, Map<String, String> query, JsonNode body) {
        boolean isWrite() {
            return !method.equals("GET");
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private record Failure(String method, Pattern path, int status, String message) {
    }

    private record Response(int status, JsonNode body) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;

    private final List<Request> requests = new ArrayList<>();
    private final List<Failure> failures = new ArrayList<>();
    private final Map<Integer, Issue> issues = new LinkedHashMap<>();
    private final Map<Long, Comment> comments = new LinkedHashMap<>();
    private final Map<Integer, Pull> pulls = new LinkedHashMap<>();
    private final Set<String> labels = new LinkedHashSet<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private int lastNumber;
    private long lastCommentId = 5_000;
    private boolean hasIssues = true;
    private boolean archived;
    private String defaultBranch = "main";
    private boolean creatorFilterIgnored;

    FakeGitHubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
    }

    String endpoint() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        ((java.util.concurrent.ExecutorService) server.getExecutor()).shutdownNow();
    }

    // ---- seeding and inspection, for tests ----

    synchronized void setHasIssues(boolean hasIssues) {
        this.hasIssues = hasIssues;
    }

    synchronized void setArchived(boolean archived) {
        this.archived = archived;
    }

    synchronized void setCodeOwners(String content) {
        files.put(".github/CODEOWNERS", content);
    }

    /**
     * Adds an issue like a person would have created it.
     */
    synchronized Issue seedIssue(String author, String title, String body, boolean open) {
        var issue = newIssue(author, title, body, false);
        issue.open = open;
        if (!open) {
            issue.stateReason = "completed";
        }
        return issue;
    }

    /**
     * Adds an open pull-request, with the head in the repository itself.
     */
    synchronized Pull openPullRequest(String headRef, String headSha) {
        return newPull(headRef, headSha, FULL_NAME);
    }

    /**
     * Adds an open pull-request, with the head in a fork.
     */
    synchronized Pull openForkPullRequest(String headRef, String headSha, String forkOwner) {
        return newPull(headRef, headSha, forkOwner + "/" + NAME);
    }

    synchronized Comment seedComment(int issueNumber, String author, String body) {
        return newComment(issueNumber, author, body);
    }

    /**
     * Closes an issue like a person does, without touching its description.
     */
    synchronized void closeManually(int issueNumber) {
        var issue = issues.get(issueNumber);
        issue.open = false;
        issue.stateReason = "completed";
    }

    /**
     * The next request matching the method and the path (regular expression) fails with the given status, once.
     */
    synchronized void failNext(String method, String pathRegex, int status) {
        failNext(method, pathRegex, status, "injected failure");
    }

    synchronized void failNext(String method, String pathRegex, int status, String message) {
        failures.add(new Failure(method, Pattern.compile(pathRegex), status, message));
    }

    /**
     * The issues are listed without regard to the requested creator, to check that clients don't rely on it.
     */
    synchronized void ignoreCreatorFilter() {
        this.creatorFilterIgnored = true;
    }

    /**
     * Issues, without pull-requests.
     */
    synchronized List<Issue> issues() {
        return issues.values().stream().filter(i -> !i.pullRequest).toList();
    }

    synchronized List<Issue> openIssues() {
        return issues().stream().filter(i -> i.open).toList();
    }

    synchronized Issue issue(int number) {
        return issues.get(number);
    }

    synchronized List<Comment> commentsOf(int number) {
        return comments.values().stream().filter(c -> c.issue == number).toList();
    }

    synchronized Set<String> labels() {
        return Set.copyOf(labels);
    }

    synchronized List<Request> requests() {
        return List.copyOf(requests);
    }

    synchronized List<Request> writes() {
        return requests.stream().filter(Request::isWrite).toList();
    }

    synchronized void clearRequests() {
        requests.clear();
    }

    // ---- HTTP ----

    private void handle(HttpExchange exchange) throws IOException {
        Response response;
        try {
            var uri = exchange.getRequestURI();
            var method = exchange.getRequestMethod();
            var path = uri.getPath();
            var query = parseQuery(uri.getRawQuery());
            final JsonNode body;
            try (var in = exchange.getRequestBody()) {
                var bytes = in.readAllBytes();
                body = bytes.length > 0 ? json.readTree(bytes) : null;
            }
            synchronized (this) {
                requests.add(new Request(method, path, query, body));
                response = injectedFailure(method, path).orElseGet(() -> route(method, path, query, body));
            }
        } catch (RuntimeException e) {
            response = new Response(500, message(e.toString()));
        }

        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        if (response.body() == null) {
            exchange.sendResponseHeaders(response.status(), -1);
        } else {
            var bytes = json.writeValueAsBytes(response.body());
            exchange.sendResponseHeaders(response.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private Optional<Response> injectedFailure(String method, String path) {
        for (var failure : failures) {
            if (failure.method().equals(method) && failure.path().matcher(path).find()) {
                failures.remove(failure);
                return Optional.of(new Response(failure.status(), message(failure.message())));
            }
        }
        return Optional.empty();
    }

    private static final Pattern ISSUES = Pattern.compile("/issues");
    private static final Pattern ISSUE = Pattern.compile("/issues/(\\d+)");
    private static final Pattern ISSUE_COMMENTS = Pattern.compile("/issues/(\\d+)/comments");
    private static final Pattern COMMENT = Pattern.compile("/issues/comments/(\\d+)");
    private static final Pattern LABEL = Pattern.compile("/labels/(.+)");
    private static final Pattern CONTENT = Pattern.compile("/contents/(.+)");
    private static final Pattern PULLS = Pattern.compile("/pulls");
    private static final Pattern PULL = Pattern.compile("/pulls/(\\d+)");

    private Response route(String method, String path, Map<String, String> query, JsonNode body) {
        if (path.equals(REPO_PATH) && method.equals("GET")) {
            return ok(repoJson(FULL_NAME));
        }
        if (path.startsWith("/users/") && method.equals("GET")) {
            return ok(userJson(path.substring("/users/".length())));
        }
        if (!path.startsWith(REPO_PATH + "/")) {
            return notFound();
        }
        var rest = path.substring(REPO_PATH.length());
        Matcher m;

        if (ISSUES.matcher(rest).matches()) {
            return switch (method) {
                case "GET" -> ok(listIssues(query));
                case "POST" -> created(issueJson(createIssue(body)));
                default -> notFound();
            };
        }
        if ((m = ISSUE.matcher(rest)).matches()) {
            var issue = issues.get(Integer.parseInt(m.group(1)));
            if (issue == null) {
                return notFound();
            }
            return switch (method) {
                case "GET" -> ok(issueJson(issue));
                case "PATCH" -> ok(issueJson(patchIssue(issue, body)));
                default -> notFound();
            };
        }
        if ((m = ISSUE_COMMENTS.matcher(rest)).matches()) {
            var number = Integer.parseInt(m.group(1));
            if (!issues.containsKey(number)) {
                return notFound();
            }
            return switch (method) {
                case "GET" -> ok(array(comments.values().stream().filter(c -> c.issue == number).map(this::commentJson).toList()));
                case "POST" -> created(commentJson(newComment(number, BOT, body.get("body").asText())));
                default -> notFound();
            };
        }
        if ((m = COMMENT.matcher(rest)).matches()) {
            var comment = comments.get(Long.parseLong(m.group(1)));
            if (comment == null) {
                return notFound();
            }
            return switch (method) {
                case "PATCH" -> {
                    comment.body = body.get("body").asText();
                    yield ok(commentJson(comment));
                }
                case "DELETE" -> {
                    comments.remove(comment.id);
                    yield new Response(204, null);
                }
                default -> notFound();
            };
        }
        if ((m = LABEL.matcher(rest)).matches() && method.equals("GET")) {
            var name = m.group(1);
            return labels.contains(name) ? ok(labelJson(name)) : notFound();
        }
        if (rest.equals("/labels") && method.equals("POST")) {
            labels.add(body.get("name").asText());
            return created(labelJson(body.get("name").asText()));
        }
        if ((m = CONTENT.matcher(rest)).matches() && method.equals("GET")) {
            var content = files.get(m.group(1));
            return content == null ? notFound() : ok(contentJson(m.group(1), content));
        }
        if (PULLS.matcher(rest).matches() && method.equals("GET")) {
            var state = query.getOrDefault("state", "open");
            return ok(array(pulls.values().stream()
                .filter(p -> state.equals("all") || state.equals(p.open ? "open" : "closed"))
                .map(this::pullJson).toList()));
        }
        if ((m = PULL.matcher(rest)).matches() && method.equals("GET")) {
            var pull = pulls.get(Integer.parseInt(m.group(1)));
            return pull == null ? notFound() : ok(pullJson(pull));
        }
        return notFound();
    }

    // ---- behaviour ----

    private ArrayNode listIssues(Map<String, String> query) {
        var creator = query.get("creator");
        var state = query.getOrDefault("state", "open");
        var ascending = "asc".equals(query.get("direction"));
        var matching = issues.values().stream()
            .filter(i -> creatorFilterIgnored || creator == null || creator.equalsIgnoreCase(i.author))
            .filter(i -> state.equals("all") || state.equals(i.open ? "open" : "closed"))
            .sorted(ascending ? Comparator.comparing(i -> i.createdAt) : Comparator.<Issue, Instant>comparing(i -> i.createdAt).reversed())
            .map(this::issueJson)
            .toList();
        return array(matching);
    }

    private Issue createIssue(JsonNode body) {
        var issue = newIssue(BOT, body.get("title").asText(), body.path("body").asText(""), false);
        body.path("labels").forEach(l -> {
            issue.labels.add(l.asText());
            // like GitHub, which creates labels used by an issue on the fly, if it is allowed to
            labels.add(l.asText());
        });
        body.path("assignees").forEach(a -> issue.assignees.add(a.asText()));
        return issue;
    }

    private Issue patchIssue(Issue issue, JsonNode body) {
        if (body.has("body")) {
            issue.body = body.get("body").asText();
        }
        if (body.has("title")) {
            issue.title = body.get("title").asText();
        }
        if (body.has("state")) {
            issue.open = body.get("state").asText().equals("open");
            issue.stateReason = issue.open ? null : body.path("state_reason").asText(null);
        }
        return issue;
    }

    private Issue newIssue(String author, String title, String body, boolean pullRequest) {
        var issue = new Issue();
        issue.number = ++lastNumber;
        issue.author = author;
        issue.title = title;
        issue.body = body;
        issue.pullRequest = pullRequest;
        issue.createdAt = EPOCH.plusSeconds(60L * issue.number);
        issues.put(issue.number, issue);
        return issue;
    }

    private Pull newPull(String headRef, String headSha, String headRepoFullName) {
        var issue = newIssue("contributor", "Change " + headRef, "", true);
        var pull = new Pull();
        pull.number = issue.number;
        pull.headRef = headRef;
        pull.headSha = headSha;
        pull.headRepoFullName = headRepoFullName;
        pull.baseRef = defaultBranch;
        pulls.put(pull.number, pull);
        return pull;
    }

    private Comment newComment(int issueNumber, String author, String body) {
        var comment = new Comment();
        comment.id = ++lastCommentId;
        comment.issue = issueNumber;
        comment.author = author;
        comment.body = body;
        comments.put(comment.id, comment);
        return comment;
    }

    // ---- JSON ----

    private ObjectNode repoJson(String fullName) {
        var owner = fullName.substring(0, fullName.indexOf('/'));
        var repo = json.createObjectNode();
        repo.put("id", 1);
        repo.put("name", NAME);
        repo.put("full_name", fullName);
        repo.set("owner", userJson(owner));
        repo.put("has_issues", hasIssues);
        repo.put("archived", archived);
        repo.put("fork", !fullName.equals(FULL_NAME));
        repo.put("default_branch", defaultBranch);
        repo.put("html_url", "https://github.com/" + fullName);
        repo.put("url", endpoint() + "/repos/" + fullName);
        repo.put("description", "The shop");
        return repo;
    }

    private ObjectNode userJson(String login) {
        var user = json.createObjectNode();
        user.put("login", login);
        user.put("id", Math.abs(login.hashCode()));
        user.put("type", login.endsWith("[bot]") ? "Bot" : "User");
        return user;
    }

    private ObjectNode issueJson(Issue issue) {
        var node = json.createObjectNode();
        node.put("id", 1_000 + issue.number);
        node.put("number", issue.number);
        node.put("state", issue.open ? "open" : "closed");
        if (issue.stateReason != null) {
            node.put("state_reason", issue.stateReason);
        }
        node.put("title", issue.title);
        node.put("body", issue.body);
        node.set("user", userJson(issue.author));
        node.put("created_at", issue.createdAt.toString());
        node.put("updated_at", issue.createdAt.toString());
        node.put("url", endpoint() + REPO_PATH + "/issues/" + issue.number);
        node.put("html_url", HTML_URL + "/issues/" + issue.number);
        var labelNodes = node.putArray("labels");
        issue.labels.forEach(l -> labelNodes.add(labelJson(l)));
        var assigneeNodes = node.putArray("assignees");
        issue.assignees.forEach(a -> assigneeNodes.add(userJson(a)));
        if (issue.pullRequest) {
            var pr = node.putObject("pull_request");
            pr.put("url", endpoint() + REPO_PATH + "/pulls/" + issue.number);
            pr.put("html_url", HTML_URL + "/pull/" + issue.number);
        }
        return node;
    }

    private ObjectNode commentJson(Comment comment) {
        var node = json.createObjectNode();
        node.put("id", comment.id);
        node.put("body", comment.body);
        node.set("user", userJson(comment.author));
        node.put("created_at", EPOCH.toString());
        node.put("updated_at", EPOCH.toString());
        node.put("author_association", "NONE");
        node.put("url", endpoint() + REPO_PATH + "/issues/comments/" + comment.id);
        node.put("html_url", HTML_URL + "/issues/" + comment.issue + "#issuecomment-" + comment.id);
        return node;
    }

    private ObjectNode pullJson(Pull pull) {
        var node = json.createObjectNode();
        node.put("id", 2_000 + pull.number);
        node.put("number", pull.number);
        node.put("state", pull.open ? "open" : "closed");
        node.put("title", "Change " + pull.headRef);
        node.set("user", userJson("contributor"));
        node.put("url", endpoint() + REPO_PATH + "/pulls/" + pull.number);
        node.put("html_url", HTML_URL + "/pull/" + pull.number);
        node.put("created_at", EPOCH.toString());
        var head = node.putObject("head");
        head.put("ref", pull.headRef);
        head.put("sha", pull.headSha);
        head.put("label", pull.headRepoFullName.split("/")[0] + ":" + pull.headRef);
        head.set("user", userJson(pull.headRepoFullName.split("/")[0]));
        head.set("repo", repoJson(pull.headRepoFullName));
        var base = node.putObject("base");
        base.put("ref", pull.baseRef);
        base.put("sha", "0000000000000000000000000000000000000000");
        base.put("label", OWNER + ":" + pull.baseRef);
        base.set("user", userJson(OWNER));
        base.set("repo", repoJson(FULL_NAME));
        return node;
    }

    private ObjectNode labelJson(String name) {
        var node = json.createObjectNode();
        node.put("id", Math.abs(name.hashCode()));
        node.put("name", name);
        node.put("color", "5319e7");
        node.put("url", endpoint() + REPO_PATH + "/labels/" + name);
        return node;
    }

    private ObjectNode contentJson(String path, String content) {
        var node = json.createObjectNode();
        node.put("type", "file");
        node.put("encoding", "base64");
        node.put("name", path.substring(path.lastIndexOf('/') + 1));
        node.put("path", path);
        node.put("sha", "abc");
        node.put("size", content.length());
        node.put("content", Base64.getMimeEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        node.put("url", endpoint() + REPO_PATH + "/contents/" + path);
        node.put("html_url", HTML_URL + "/blob/main/" + path);
        return node;
    }

    private ArrayNode array(List<? extends JsonNode> nodes) {
        var array = json.createArrayNode();
        nodes.forEach(array::add);
        return array;
    }

    private ObjectNode message(String message) {
        return json.createObjectNode().put("message", message);
    }

    private Response ok(JsonNode body) {
        return new Response(200, body);
    }

    private Response created(JsonNode body) {
        return new Response(201, body);
    }

    private Response notFound() {
        return new Response(404, message("Not Found"));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        var result = new LinkedHashMap<String, String>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (var pair : rawQuery.split("&")) {
            var idx = pair.indexOf('=');
            var key = URLDecoder.decode(idx < 0 ? pair : pair.substring(0, idx), StandardCharsets.UTF_8);
            var value = idx < 0 ? "" : URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }
}
